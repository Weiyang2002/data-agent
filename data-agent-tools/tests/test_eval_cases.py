"""M1：评测用例目录测试。

这些测试保护的是**评测集本身的结构性质**，不是某个用例的具体内容。
用例内容会随 M4 的归因不断增补，但下面这些性质必须一直成立，
否则指标算出来是错的。
"""

import pytest
from fastapi.testclient import TestClient

from data_tools import eval_cases as E
from data_tools.defects import DEFECT_META
from data_tools.main import app

client = TestClient(app)


def test_catalog_is_valid():
    E.validate_catalog()


def test_case_count_meets_milestone_target():
    """M1 验收判据：30–50 个任务。"""
    assert 30 <= len(E.ALL_CASES) <= 50


def test_all_four_levels_are_covered():
    distribution = E.level_distribution()
    assert set(distribution) == {
        E.LEVEL_RULE, E.LEVEL_STRUCTURE, E.LEVEL_JUDGMENT, E.LEVEL_DISCOVERY
    }
    # 每层至少 5 个，否则该层的检出率统计没有意义
    assert all(count >= 5 for count in distribution.values()), distribution


def test_clarification_cases_are_balanced():
    """澄清恰当率必须双向可测。

    只有"该问"的用例，最优策略就是"什么都问"；
    必须同时存在"不该问"的用例来惩罚过度澄清。
    """
    judgment = [c for c in E.ALL_CASES if c.level == E.LEVEL_JUDGMENT]
    should_ask = [c for c in judgment if c.expectClarify]
    should_not_ask = [c for c in judgment if not c.expectClarify]
    assert should_ask, "必须有该澄清的用例"
    assert should_not_ask, "必须有不该澄清的用例，否则过度澄清无法被惩罚"


def test_discovery_cases_have_proactive_report_expectations():
    """L4 的定义：用户没问，但系统应主动报告。

    所以每个 L4 用例必须声明期望报告哪些缺陷，
    且这些缺陷在该用例的数据里确实被注入了。
    """
    for case in E.ALL_CASES:
        if case.level != E.LEVEL_DISCOVERY:
            continue
        assert case.expectProactiveReport, f"{case.caseId} 缺少 expectProactiveReport"
        injected_codes = {spec.code for spec in case.defects}
        missing = set(case.expectProactiveReport) - injected_codes
        assert not missing, f"{case.caseId} 期望报告 {missing} 但数据里没注入"


def test_discovery_defects_are_marked_not_user_asked():
    """L4 期望报告的缺陷，其元数据里 userAsked 必须为 False。

    两处定义不一致会让"问题发现率"这个指标算错。
    """
    for case in E.ALL_CASES:
        for code in case.expectProactiveReport:
            assert DEFECT_META[code].user_asked is False, (
                f"{case.caseId} 把 {code} 当作 L4 发现类，"
                f"但 DEFECT_META 里 userAsked=True，两处定义冲突"
            )


def test_rule_level_cases_should_not_require_clarification():
    """L1 规则类有明确的知识库依据，应当自动决定，不该打扰用户。"""
    for case in E.ALL_CASES:
        if case.level == E.LEVEL_RULE:
            assert not case.expectClarify, f"{case.caseId} 是规则类，不应触发澄清"


def test_eval_cases_endpoint():
    response = client.get("/eval/cases")
    assert response.status_code == 200
    body = response.json()
    assert body["total"] == len(E.ALL_CASES)
    assert body["levelDistribution"]["L4_DISCOVERY"] >= 5


def test_defects_endpoint_lists_all_codes():
    response = client.get("/dataset/defects")
    assert response.status_code == 200
    codes = {item["code"] for item in response.json()["defects"]}
    assert codes == set(DEFECT_META)


def test_generate_endpoint_rejects_unknown_code():
    """未知缺陷码必须 400，不能静默产出少了一类缺陷的数据集。"""
    response = client.post("/dataset/generate", json={
        "rows": 100, "patients": 10, "seed": 1,
        "defects": [{"code": "NOPE", "ratio": 0.1}],
        "datasetName": "t_api_bad",
    })
    assert response.status_code == 400


def test_generate_endpoint_happy_path():
    response = client.post("/dataset/generate", json={
        "rows": 500, "patients": 50, "seed": 9,
        "defects": [
            {"code": "ROW_DUPLICATE", "ratio": 0.02},
            {"code": "COLUMN_MISALIGN", "ratio": 1.0},
        ],
        "datasetName": "t_api_ok",
    })
    assert response.status_code == 200
    body = response.json()
    assert body["rowCount"] > 500
    assert body["baseline"]["admissionsPerPatient"] == pytest.approx(1.36, abs=0.05)
    assert {d["code"] for d in body["injected"]} == {"ROW_DUPLICATE", "COLUMN_MISALIGN"}
    assert body["expectNoClarifications"]
