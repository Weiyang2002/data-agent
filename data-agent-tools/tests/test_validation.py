"""M2：行级 / 分布级确定性校验测试。

这一组的重点是守住一条边界：**阈值只能从请求里来。**

Python 侧一旦出现任何临床数字（32、45、100），
「规范遵从率」这个指标就失去意义——系统看起来遵从了规范，
实际遵从的是硬编码，院内标准改了它也不会变。
"""

import pandas as pd
import pytest

from data_tools import columns as C
from data_tools.dataset_generate import generate_dataset
from data_tools.schemas.dataset import DatasetGenerateRequest, DefectSpec
from data_tools.schemas.validate import (
    DistributionThresholds,
    ValidateDistributionRequest,
    ValidateRowRequest,
    ValidityRule,
)
from data_tools.validation import validate_distribution, validate_row


def _dataset(name, defects, rows=3000, patients=200, seed=17):
    return generate_dataset(DatasetGenerateRequest(
        rows=rows, patients=patients, seed=seed,
        defects=defects, datasetName=name)).datasetPath


@pytest.fixture(scope="module")
def out_of_range_path():
    return _dataset("v_range", [
        DefectSpec(code="OUT_OF_RANGE", ratio=0.02, columns=[C.COL_TEMP, C.COL_SBP]),
    ])


# ── 行级 ──

def test_row_validation_uses_passed_in_thresholds(out_of_range_path):
    """阈值来自请求，发现里带回 ruleId 与来源文档。

    「引用标注」不是装饰：没有 ruleId，规范遵从率就无从统计，
    医生也无法核对系统凭什么判定 46.3℃ 越界。
    """
    response = validate_row(ValidateRowRequest(
        datasetPath=out_of_range_path,
        rules=[ValidityRule(
            ruleId="KR-1001", column=C.COL_TEMP, min=32.0, max=45.0, unit="℃",
            sourceDoc="院内生理指标有效性规范", sourceLocator="表1第1行")],
    ))
    assert response.checkedRules == 1
    finding = response.findings[0]
    assert finding.ruleId == "KR-1001"
    assert finding.column == C.COL_TEMP
    assert finding.violationCount > 0
    assert finding.expectedMin == 32.0 and finding.expectedMax == 45.0
    assert finding.sourceDoc == "院内生理指标有效性规范"
    assert "表1第1行" in finding.evidence
    assert len(finding.sampleRowIds) == len(finding.sampleValues)


def test_row_validation_result_changes_with_threshold(out_of_range_path):
    """换一组阈值，结论必须跟着变。

    这是「阈值真的来自参数」的唯一可靠证明。
    如果 Python 侧偷偷用了自己的常量，放宽阈值时违规数不会变。
    """
    def count(low, high):
        response = validate_row(ValidateRowRequest(
            datasetPath=out_of_range_path,
            rules=[ValidityRule(ruleId="KR-x", column=C.COL_TEMP, min=low, max=high)]))
        return response.findings[0].violationCount if response.findings else 0

    strict = count(36.0, 37.5)
    normal = count(32.0, 45.0)
    loose = count(-1000.0, 1000.0)

    assert strict > normal > loose == 0


def test_row_validation_ignores_missing_values(out_of_range_path):
    """空值不算越界。

    体温有 30% 缺失，如果把 NaN 当成违规，违规数会被缺失率淹没，
    而「该测未测」和「测出了不可能的值」是两类完全不同的问题。
    """
    response = validate_row(ValidateRowRequest(
        datasetPath=out_of_range_path,
        rules=[ValidityRule(ruleId="KR-x", column=C.COL_TEMP, min=-1000.0, max=1000.0)]))
    assert response.findings == []

    frame = pd.read_parquet(out_of_range_path)
    assert frame[C.COL_TEMP].isna().sum() > 0, "该数据集本来就有缺失，前一条断言才有意义"


def test_row_validation_rejects_empty_rules(out_of_range_path):
    """零规则校验必须报错。

    「校验了 0 条规则、发现 0 个问题」和「校验通过」在响应体上长得一模一样。
    知识库没命中时正确的行为是短路返回 NO_EVIDENCE，
    而不是发起一次空校验然后把「没查」当成「没问题」。
「查了没问题」和「压根没查」必须能区分。
    """
    with pytest.raises(ValueError, match="NO_EVIDENCE"):
        validate_row(ValidateRowRequest(datasetPath=out_of_range_path, rules=[]))


def test_row_validation_rejects_unknown_column(out_of_range_path):
    """规则指向不存在的列是配置错误，不能当成「没有违规」。"""
    with pytest.raises(ValueError, match="列不存在"):
        validate_row(ValidateRowRequest(
            datasetPath=out_of_range_path,
            rules=[ValidityRule(ruleId="KR-x", column="不存在的列", min=0.0, max=1.0)]))


def test_row_validation_allowed_values():
    """文本列按允许取值集合校验。"""
    path = _dataset("v_text", [DefectSpec(code="NUMERIC_IN_TEXT", ratio=0.05)])
    response = validate_row(ValidateRowRequest(
        datasetPath=path,
        rules=[ValidityRule(ruleId="KR-2001", column=C.COL_CONSCIOUS,
                            allowedValues=C.CONSCIOUS_VALUES,
                            sourceDoc="院内文本列取值规范", sourceLocator="意识列")]))
    finding = response.findings[0]
    assert finding.violationCount > 0
    # 混进来的是数字编码
    assert all(value.isdigit() for value in finding.sampleValues)


# ── 分布级 ──

def test_distribution_validation_detects_injected_patterns():
    path = _dataset("v_dist", [
        DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.8),
        DefectSpec(code="FULLWIDTH_MIXED", ratio=0.15),
    ])
    response = validate_distribution(ValidateDistributionRequest(datasetPath=path))
    codes = {finding["code"] for finding in response.findings}
    assert {"TIMESTAMP_ALL_ZERO", "FULLWIDTH_MIXED"} <= codes


def test_distribution_thresholds_are_overridable():
    """阈值可由请求覆盖，覆盖后结论必须跟着变。"""
    path = _dataset("v_dist_thr", [DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.6)])

    default = validate_distribution(ValidateDistributionRequest(datasetPath=path))
    assert "TIMESTAMP_ALL_ZERO" in {item["code"] for item in default.findings}

    raised = validate_distribution(ValidateDistributionRequest(
        datasetPath=path,
        thresholds=DistributionThresholds(timestampAllZeroRatio=0.95)))
    assert "TIMESTAMP_ALL_ZERO" not in {item["code"] for item in raised.findings}


def test_distribution_validation_limits_columns():
    path = _dataset("v_dist_cols", [DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.8)])
    response = validate_distribution(ValidateDistributionRequest(
        datasetPath=path, columns=[C.COL_TEMP]))
    assert [item["name"] for item in response.columnStats] == [C.COL_TEMP]
    assert "TIMESTAMP_ALL_ZERO" not in {item["code"] for item in response.findings}


def test_distribution_validation_rejects_unknown_column():
    path = _dataset("v_dist_bad", [])
    with pytest.raises(ValueError, match="不存在"):
        validate_distribution(ValidateDistributionRequest(
            datasetPath=path, columns=["不存在的列"]))


def test_distribution_before_after_comparison():
    """处理前后用同一批检测器比对，这才是「修好了」的证据。

    去重前报 ROW_DUPLICATE、去重后不报，说明问题真的没了。
    如果两边用两套检测器，这个对比就什么都证明不了。
    """
    path = _dataset("v_dist_before", [DefectSpec(code="ROW_DUPLICATE", ratio=0.05)])
    before = validate_distribution(ValidateDistributionRequest(datasetPath=path))
    assert "ROW_DUPLICATE" in {item["code"] for item in before.findings}

    cleaned_path = path.replace(".parquet", ".cleaned.parquet")
    pd.read_parquet(path).drop_duplicates().to_parquet(cleaned_path, index=False)

    after = validate_distribution(ValidateDistributionRequest(datasetPath=cleaned_path))
    assert "ROW_DUPLICATE" not in {item["code"] for item in after.findings}
    assert after.totalRows < before.totalRows


def test_column_stats_carry_no_raw_rows():
    """分布级返回体同样不得夹带原始行。"""
    path = _dataset("v_dist_priv", [])
    response = validate_distribution(ValidateDistributionRequest(datasetPath=path))
    payload = response.model_dump_json()
    assert "P000001" not in payload
    assert "A0000001" not in payload
