"""M1：缺陷注入器测试。

测试原则：**不测「注入器有没有被调用」，测「缺陷是否真的可观测」。**

一个注入器可以毫无报错地跑完却什么也没改（
MISSING_SEMANTIC 因为基线本身缺失率就高，注入后缺失率被推到 100%，
与 golden 声明的 99.16% 对不上）。
所以每个用例都直接读产出的 parquet，断言那个缺陷的**可观测信号**成立。
"""

import json

import pandas as pd
import pytest

from data_tools import columns as C
from data_tools.dataset_generate import generate_dataset
from data_tools.defects import APPLICATION_ORDER, DEFECT_META, INJECTORS
from data_tools.schemas.dataset import DatasetGenerateRequest, DefectSpec


def _generate(name, defects, rows=4000, patients=250, seed=7):
    request = DatasetGenerateRequest(
        rows=rows, patients=patients, seed=seed,
        defects=defects, datasetName=name,
    )
    response = generate_dataset(request)
    frame = pd.read_parquet(response.datasetPath)
    return response, frame


# ────────────────────────────────────────────────
# 基线数据本身的性质
# ────────────────────────────────────────────────

def test_baseline_admissions_per_patient_matches_real_data():
    """基线人均入院次数应贴近真实数据的 1.36（221,797 患者 / 301,604 次入院）。"""
    response, _ = _generate("t_baseline", [])
    assert response.baseline.admissionsPerPatient == pytest.approx(1.36, abs=0.02)
    assert response.baseline.admissionCount > response.baseline.patientCount


def test_baseline_has_no_duplicates_and_no_out_of_range():
    """不注入缺陷时，数据应当是干净的——否则评测会把基线噪音算成检出。"""
    _, frame = _generate("t_clean", [])
    assert frame.duplicated().sum() == 0
    temp = pd.to_numeric(frame[C.COL_TEMP], errors="coerce")
    low, high = C.VALIDITY_RANGE[C.COL_TEMP]
    assert ((temp < low) | (temp > high)).sum() == 0


# ────────────────────────────────────────────────
# 每类缺陷的可观测性
# ────────────────────────────────────────────────

def test_row_duplicate_produces_identical_rows():
    response, frame = _generate("t_dup", [DefectSpec(code="ROW_DUPLICATE", ratio=0.02)])
    injected = {d.code: d for d in response.injected}["ROW_DUPLICATE"]
    assert frame.duplicated().sum() == injected.affectedRows
    assert injected.expectLevel == "ROW"


def test_out_of_range_breaches_knowledge_base_threshold():
    response, frame = _generate(
        "t_range",
        [DefectSpec(code="OUT_OF_RANGE", ratio=0.01, columns=[C.COL_TEMP])],
    )
    temp = pd.to_numeric(frame[C.COL_TEMP], errors="coerce")
    low, high = C.VALIDITY_RANGE[C.COL_TEMP]
    breached = int(((temp < low) | (temp > high)).sum())
    injected = {d.code: d for d in response.injected}["OUT_OF_RANGE"]
    assert breached == injected.affectedRows


def test_column_misalign_collapses_admissions_per_patient_to_one():
    """招牌案例：人均入院次数塌成 1.00。

    这个缺陷的特殊性在于——代码不会报错、格式完全正常、没有任何异常值。
    从工程角度看处理"完全成功"，只有临床常识能发现 22 万病人
    不可能人人只住院一次。这是三层校验里常识级那一层的存在理由。
    """
    response, frame = _generate("t_misalign", [DefectSpec(code="COLUMN_MISALIGN", ratio=1.0)])
    patients = frame[C.COL_PATIENT].nunique()
    admissions = frame[C.COL_ADMISSION].nunique()

    assert response.baseline.admissionsPerPatient == pytest.approx(1.36, abs=0.02)
    assert admissions / patients == pytest.approx(1.0)
    assert {d.code: d for d in response.injected}["COLUMN_MISALIGN"].expectLevel == "COMMON_SENSE"


def test_time_inversion_puts_record_before_admission():
    _, frame = _generate("t_inversion", [DefectSpec(code="TIME_INVERSION", ratio=0.05)])
    admit = pd.to_datetime(frame[C.COL_ADMIT_TIME], errors="coerce")
    record = pd.to_datetime(frame[C.COL_RECORD_TIME], errors="coerce")
    assert (record < admit).sum() > 0


def test_timestamp_all_zero_is_only_visible_in_distribution():
    """分布级缺陷的定义性质：单看任何一行都正常，只有占比能暴露异常。"""
    _, frame = _generate("t_zero", [DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.8)])
    admit = pd.to_datetime(frame[C.COL_ADMIT_TIME], errors="coerce")
    zero_ratio = (admit.dt.normalize() == admit).mean()
    assert zero_ratio > 0.5
    # 每一行本身都是合法时间戳，行级校验查不出问题
    assert admit.notna().all()


def test_fullwidth_mixed_splits_one_value_into_two():
    _, frame = _generate("t_fullwidth", [DefectSpec(code="FULLWIDTH_MIXED", ratio=0.2)])
    values = frame[C.COL_OXYGEN].dropna().astype(str)
    assert values.str.contains("（无创）").any()
    assert values.str.contains(r"\(无创\)", regex=True).any()


def test_dup_concat_repeats_text_value():
    _, frame = _generate("t_concat", [DefectSpec(code="DUP_CONCAT", ratio=0.1)])
    values = frame[C.COL_OXYGEN].dropna().astype(str)
    assert any(v.count("未吸氧") > 1 for v in values.unique())


def test_numeric_in_text_pollutes_enum():
    _, frame = _generate("t_numeric", [DefectSpec(code="NUMERIC_IN_TEXT", ratio=0.05)])
    values = set(frame[C.COL_CONSCIOUS].dropna().astype(str).unique())
    assert {"1", "2"} & values


def test_date_format_mixed_yields_multiple_formats():
    _, frame = _generate("t_dateformat", [DefectSpec(code="DATE_FORMAT_MIXED", ratio=0.2)])
    values = frame[C.COL_ADMIT_TIME].dropna().astype(str)
    assert values.str.contains("/").any()      # 2026/08/09 或 26/08/09
    assert values.str.contains("-").any()      # ISO
    # 整列是字符串，这正是下游要处理的问题之一
    assert frame[C.COL_ADMIT_TIME].dtype == object


def test_window_all_null_blanks_a_contiguous_span():
    _, frame = _generate("t_window", [DefectSpec(code="WINDOW_ALL_NULL", ratio=0.05)])
    is_null = frame[C.COL_SPO2].isna().to_numpy()
    # 存在一段足够长的连续空白
    longest = current = 0
    for flag in is_null:
        current = current + 1 if flag else 0
        longest = max(longest, current)
    assert longest >= int(len(frame) * 0.05)


def test_missing_semantic_hits_exact_rate():
    """升压药 vs 体温：同为空值，语义相反。

    这条断言精确到 0.5% 是有意的——M1 第一版实现让缺失率变成了 100%，
    宽松的断言（比如 > 0.95）会放过这个 bug。
    """
    _, frame = _generate(
        "t_missing", [DefectSpec(code="MISSING_SEMANTIC", ratio=0.9916)])
    vaso_rate = frame[C.COL_VASOPRESSOR].isna().mean()
    temp_rate = frame[C.COL_TEMP].isna().mean()
    assert vaso_rate == pytest.approx(0.9916, abs=0.005)
    assert temp_rate == pytest.approx(0.3059, abs=0.03)
    # 两者差异必须显著，否则"缺失语义因列而异"这个案例就不成立
    assert vaso_rate - temp_rate > 0.6


# ────────────────────────────────────────────────
# golden 的完整性
# ────────────────────────────────────────────────

def test_golden_contains_expect_no_clarifications():
    """没有这个字段，过度澄清无法被惩罚，系统会退化成「什么都问」。"""
    response, _ = _generate("t_golden", [
        DefectSpec(code="OUT_OF_RANGE", ratio=0.01),
        DefectSpec(code="ROW_DUPLICATE", ratio=0.01),
        DefectSpec(code="MISSING_SEMANTIC", ratio=0.9916),
    ])
    assert response.expectClarifications, "该问的清单不能为空"
    assert response.expectNoClarifications, "不该问的清单不能为空——澄清恰当率需要双向计分"

    golden = json.loads(open(response.goldenPath, encoding="utf-8").read())
    assert "expectNoClarifications" in golden
    assert golden["baseline"]["admissionsPerPatient"] > 1.0


def test_golden_marks_l4_discovery_defects():
    """L4 发现类：用户没问，但系统应该主动报告。

    这是本评测集区别于常见 Agent 评测的地方——
    常见评测只考"问什么答得对不对"。
    """
    response, _ = _generate("t_l4", [
        DefectSpec(code="COLUMN_MISALIGN", ratio=1.0),
        DefectSpec(code="TIMESTAMP_ALL_ZERO", ratio=0.8),
    ])
    not_asked = [d.code for d in response.injected if not d.userAsked]
    assert "COLUMN_MISALIGN" in not_asked
    assert "TIMESTAMP_ALL_ZERO" in not_asked


# ────────────────────────────────────────────────
# 可复现性 —— M4 归因的前提
# ────────────────────────────────────────────────

def test_same_seed_produces_identical_dataset():
    """同 seed 必须完全一致。

    不能复现就无法判断"指标变化是我的改动带来的，还是这轮数据恰好不同"，
    M4 的收敛闭环会直接失效。
    """
    defects = [
        DefectSpec(code="ROW_DUPLICATE", ratio=0.02),
        DefectSpec(code="OUT_OF_RANGE", ratio=0.01),
        DefectSpec(code="COLUMN_MISALIGN", ratio=1.0),
        DefectSpec(code="FULLWIDTH_MIXED", ratio=0.1),
    ]
    first, frame_a = _generate("t_seed_a", defects, seed=123)
    second, frame_b = _generate("t_seed_b", defects, seed=123)

    pd.testing.assert_frame_equal(frame_a, frame_b)
    assert [d.rowIds for d in first.injected] == [d.rowIds for d in second.injected]
    assert first.baseline.model_dump() == second.baseline.model_dump()


def test_different_seed_produces_different_dataset():
    defects = [DefectSpec(code="OUT_OF_RANGE", ratio=0.01)]
    _, frame_a = _generate("t_seed_c", defects, seed=1)
    _, frame_b = _generate("t_seed_d", defects, seed=2)
    assert not frame_a.equals(frame_b)


# ────────────────────────────────────────────────
# 注册表一致性
# ────────────────────────────────────────────────

def test_every_defect_has_meta_injector_and_order():
    """三张表必须完全对齐，漏一个就会在运行时才炸。"""
    assert set(DEFECT_META) == set(INJECTORS)
    assert set(DEFECT_META) == set(APPLICATION_ORDER)
    assert len(APPLICATION_ORDER) == len(set(APPLICATION_ORDER)), "注入顺序不能有重复"


def test_row_duplicate_is_applied_last():
    """ROW_DUPLICATE 必须最后执行：它改变行数，
    先前记录的位置索引会失效。"""
    assert APPLICATION_ORDER[-1] == "ROW_DUPLICATE"


def test_unknown_defect_code_is_rejected():
    with pytest.raises(ValueError, match="不支持的缺陷类型"):
        generate_dataset(DatasetGenerateRequest(
            rows=100, patients=10, seed=1,
            defects=[DefectSpec(code="NOT_A_REAL_DEFECT", ratio=0.1)],
            datasetName="t_bad",
        ))
