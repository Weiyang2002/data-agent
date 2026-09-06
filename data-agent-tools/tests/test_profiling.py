"""M2：数据画像与确定性异常检出测试。

测试原则延续 M1：**不测「检测器有没有被调用」，测「注入的缺陷是否真的被检出」。**

所以每个用例都走完整回路——用缺陷注入器造一份带已知缺陷的数据，
再让画像去发现它。检测器的口径和注入器的口径必须对得上，
对不上的话 M3 的分层检出率就是假的。
"""

import pandas as pd
import pytest

from data_tools import columns as C
from data_tools.dataset_generate import generate_dataset
from data_tools.profiling import profile_dataset
from data_tools.schemas.dataset import DatasetGenerateRequest, DefectSpec
from data_tools.schemas.profile import ProfileRequest


def _profile_with(name, defects, rows=4000, patients=250, seed=11):
    dataset = generate_dataset(DatasetGenerateRequest(
        rows=rows, patients=patients, seed=seed, defects=defects, datasetName=name))
    response = profile_dataset(ProfileRequest(datasetPath=dataset.datasetPath))
    return dataset, response


def _codes(response, column=None):
    return {
        pattern.code for pattern in response.anomalyPatterns
        if column is None or pattern.column == column
    }


def _pattern(response, code, column=None):
    for item in response.anomalyPatterns:
        if item.code == code and (column is None or item.column == column):
            return item
    raise AssertionError(f"未检出 {code}（column={column}）；"
                         f"实际检出 {[(p.code, p.column) for p in response.anomalyPatterns]}")


# ── 隐私约束 —— 这一组是硬约束，不是功能测试 ──

def test_profile_never_returns_raw_rows():
    """返回体里不得出现任何患者/住院标识。

    「原始患者数据不得出内网」在代码里的守门测试。
    画像会被注入 Prompt，一旦标识列的取值被枚举进去，隐私约束就破了。
    保护机制是「高基数列不枚举取值」——这个测试盯住那条机制没被改坏。
    """
    _, response = _profile_with("p_privacy", [])

    identifiers = {
        column.name for column in response.columns
        if column.name in (C.COL_PATIENT, C.COL_ADMISSION)
    }
    assert identifiers == {C.COL_PATIENT, C.COL_ADMISSION}

    for column in response.columns:
        if column.name in identifiers:
            assert column.textValues is None, f"{column.name} 是标识列，不得枚举取值"
            assert column.textValuesTruncated is True

    payload = response.model_dump_json()
    assert "P000001" not in payload
    assert "A0000001" not in payload


def test_profile_rejects_sample_rows():
    """sampleRows 未实现时必须报错，不能静默当 0 处理（不变量 7）。"""
    dataset = generate_dataset(DatasetGenerateRequest(
        rows=500, patients=50, seed=3, defects=[], datasetName="p_sample"))
    with pytest.raises(ValueError, match="sampleRows"):
        profile_dataset(ProfileRequest(datasetPath=dataset.datasetPath, sampleRows=5))


def test_profile_missing_file_raises():
    with pytest.raises(FileNotFoundError):
        profile_dataset(ProfileRequest(datasetPath="./.eval/__不存在__.parquet"))


# ── 基础画像 ──

def test_profile_reports_schema_and_missing_rate():
    _, response = _profile_with("p_basic", [])

    assert response.columnCount == len(C.ALL_COLUMNS)
    by_name = {column.name: column for column in response.columns}

    # 缺失率应贴近 columns.py 里声明的真实数据缺失率
    assert by_name[C.COL_TEMP].missingRate == pytest.approx(C.MISSING_RATE[C.COL_TEMP], abs=0.03)
    assert by_name[C.COL_HR].missingRate == pytest.approx(C.MISSING_RATE[C.COL_HR], abs=0.03)

    temp = by_name[C.COL_TEMP].numeric
    assert temp is not None
    low, high = C.VALIDITY_RANGE[C.COL_TEMP]
    assert low <= temp.min <= temp.p50 <= temp.max <= high

    # 低基数文本列应当枚举取值
    oxygen = by_name[C.COL_OXYGEN]
    assert oxygen.textValues is not None
    assert oxygen.textValuesTruncated is False
    assert {item.value for item in oxygen.textValues} <= set(C.OXYGEN_VALUES)


def test_key_candidates_found():
    """住院ID + 记录时间 应当能（近似）唯一定位一行。

    实测发现合成数据里不存在唯一性 100% 的组合——同一次入院的两条记录
    时间戳会撞车。这不是 bug 而是真实临床数据的常态，
    所以候选门槛定在 0.95 并如实返回 uniqueRatio，
    让「按这两列去重会丢多少行」成为医生可见的取舍。
    """
    _, response = _profile_with("p_key", [])
    combos = [set(candidate.columns) for candidate in response.keyCandidates]
    assert any(C.COL_ADMISSION in combo for combo in combos), \
        f"未找到含住院ID的主键候选：{combos}"
    assert all(0.95 <= candidate.uniqueRatio <= 1.0 for candidate in response.keyCandidates)


# ── 逐个缺陷的检出（口径必须与注入器对齐） ──

def test_detect_row_duplicate():
    dataset, response = _profile_with(
        "p_dup", [DefectSpec(code="ROW_DUPLICATE", ratio=0.05)])
    pattern = _pattern(response, "ROW_DUPLICATE")
    injected = next(item for item in dataset.injected if item.code == "ROW_DUPLICATE")
    assert pattern.level == "ROW"
    assert pattern.affectedRows == injected.affectedRows


def test_detect_timestamp_all_zero():
    _, response = _profile_with(
        "p_zero", [DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.8)])
    pattern = _pattern(response, "TIMESTAMP_ALL_ZERO", C.COL_ADMIT_TIME)
    assert pattern.level == "DISTRIBUTION"
    assert pattern.metric > 0.5


def test_timestamp_all_zero_not_reported_below_threshold():
    """低于阈值不报——避免制造误报，误报同样会拉低指标。"""
    _, response = _profile_with(
        "p_zero_low", [DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.1)])
    assert "TIMESTAMP_ALL_ZERO" not in _codes(response, C.COL_ADMIT_TIME)


def test_detect_fullwidth_mixed():
    _, response = _profile_with(
        "p_full", [DefectSpec(code="FULLWIDTH_MIXED", ratio=0.2)])
    pattern = _pattern(response, "FULLWIDTH_MIXED", C.COL_OXYGEN)
    assert pattern.affectedRows > 0


def test_detect_dup_concat():
    _, response = _profile_with(
        "p_concat", [DefectSpec(code="DUP_CONCAT", ratio=0.06)])
    pattern = _pattern(response, "DUP_CONCAT", C.COL_OXYGEN)
    assert pattern.affectedRows > 0


def test_detect_numeric_in_text():
    _, response = _profile_with(
        "p_numtext", [DefectSpec(code="NUMERIC_IN_TEXT", ratio=0.05)])
    pattern = _pattern(response, "NUMERIC_IN_TEXT", C.COL_CONSCIOUS)
    assert 0 < pattern.metric < 0.5


def test_detect_date_format_mixed():
    _, response = _profile_with(
        "p_datefmt", [DefectSpec(code="DATE_FORMAT_MIXED", ratio=0.3)])
    pattern = _pattern(response, "DATE_FORMAT_MIXED", C.COL_ADMIT_TIME)
    assert pattern.level == "ROW"
    assert pattern.affectedRows > 0


def test_detect_window_all_null():
    _, response = _profile_with(
        "p_window", [DefectSpec(code="WINDOW_ALL_NULL", ratio=0.05)])
    pattern = _pattern(response, "WINDOW_ALL_NULL", C.COL_SPO2)
    assert pattern.level == "STRUCTURE"
    # 注入了 5% 连续空白，即 200 行
    assert pattern.affectedRows >= 150


def test_window_all_null_not_reported_on_random_missing():
    """基线数据的随机缺失不该被当成结构性空白段。

    这个反向用例比正向用例更重要：所有列都有 10%–35% 的随机缺失，
    检测器一旦太敏感就会对每一列都报一次，M3 的误报率直接爆表。
    """
    _, response = _profile_with("p_window_neg", [])
    assert "WINDOW_ALL_NULL" not in _codes(response)


def test_detect_high_missing_reports_fact_not_semantics():
    """极高缺失率只报告事实，code 不是 MISSING_SEMANTIC。

    Python 能确定的是「99% 是空」，不能确定「空的意思是没用药还是没记录」。
    后者是临床判断，属于 Java 侧。这个测试盯住这条边界不被越过。
    """
    _, response = _profile_with(
        "p_missing", [DefectSpec(code="MISSING_SEMANTIC", ratio=0.99)])
    pattern = _pattern(response, "HIGH_MISSING_RATE", C.COL_VASOPRESSOR)
    assert pattern.level == "CLARIFY"
    assert pattern.metric >= 0.9
    assert "MISSING_SEMANTIC" not in _codes(response)


# ── 明确不在画像里做的 ──

def test_out_of_range_is_not_detected_by_profile():
    """越界不在画像里检出：阈值必须来自知识库，不能硬编码在 Python。

    columns.py 里确实有一份 VALIDITY_RANGE，但那是**生成数据**用的，
    检测侧一旦引用它，就变成「用自己的答案考自己」，
    而且真实院内阈值改了以后系统不会跟着变。
    """
    _, response = _profile_with(
        "p_range", [DefectSpec(code="OUT_OF_RANGE", ratio=0.05)])
    assert "OUT_OF_RANGE" not in _codes(response)


def test_column_misalign_is_not_detected_by_profile():
    """错位不在画像里检出，但画像必须提供发现它所需要的统计量。

    人均入院次数 = distinct(住院ID) / distinct(患者ID)。
    这个比值是常识级校验的输入，由 Java 侧代码算出来，
    再由 LLM 判断「1.00 在临床上合理吗」。
    画像的职责到 distinctCount 为止。
    """
    _, response = _profile_with(
        "p_misalign", [DefectSpec(code="COLUMN_MISALIGN", ratio=1.0)])
    assert "COLUMN_MISALIGN" not in _codes(response)

    by_name = {column.name: column for column in response.columns}
    patients = by_name[C.COL_PATIENT].distinctCount
    admissions = by_name[C.COL_ADMISSION].distinctCount
    assert patients > 0
    # 错位注入后两者相等，人均入院次数塌成 1.00
    assert admissions / patients == pytest.approx(1.0, abs=0.001)


def test_profile_survives_full_defect_dataset():
    """全缺陷数据集上跑一遍，确认多缺陷叠加时检测器不会互相干扰。"""
    defects = [
        DefectSpec(code="MISSING_SEMANTIC", ratio=0.99),
        DefectSpec(code="WINDOW_ALL_NULL", ratio=0.03),
        DefectSpec(code="FULLWIDTH_MIXED", ratio=0.1),
        DefectSpec(code="DUP_CONCAT", ratio=0.03),
        DefectSpec(code="NUMERIC_IN_TEXT", ratio=0.02),
        DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.8),
        DefectSpec(code="DATE_FORMAT_MIXED", ratio=0.15),
        DefectSpec(code="ROW_DUPLICATE", ratio=0.02),
    ]
    _, response = _profile_with("p_full", defects, rows=6000, patients=400)
    detected = _codes(response)
    expected = {
        "ROW_DUPLICATE", "DATE_FORMAT_MIXED", "TIMESTAMP_ALL_ZERO",
        "FULLWIDTH_MIXED", "DUP_CONCAT", "NUMERIC_IN_TEXT",
        "WINDOW_ALL_NULL", "HIGH_MISSING_RATE",
    }
    assert expected <= detected, f"漏检 {expected - detected}"
