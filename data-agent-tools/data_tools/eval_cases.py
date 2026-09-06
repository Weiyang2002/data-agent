"""评测用例目录。

四层任务划分：
| 层级          | 任务性质 | 考察什么 |
|---|---|---|
| L1_RULE       | 去重、越界置空 | 是否调用确定性工具；阈值是否来自知识库 |
| L2_STRUCTURE  | 唯一键、时间归一 | 代码正确性、边界处理 |
| L3_JUDGMENT   | 文本列映射编码 | 是否主动澄清而非猜测 |
| L4_DISCOVERY  | 埋缺陷但用户不提问 | 用户没问，系统是否主动报告 |

用例定义在 Python 侧：用例引用缺陷码，缺陷码的权威在 `defects.py`。Java 通过
`GET /eval/cases` 取用。
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field

from data_tools import columns as C
from data_tools.defects import DEFECT_META
from data_tools.schemas.dataset import DefectSpec

LEVEL_RULE = "L1_RULE"
LEVEL_STRUCTURE = "L2_STRUCTURE"
LEVEL_JUDGMENT = "L3_JUDGMENT"
LEVEL_DISCOVERY = "L4_DISCOVERY"


@dataclass
class EvalCase:
    caseId: str
    level: str
    requirement: str
    """医生的原话，刻意保留口语化和不精确。"""

    defects: list[DefectSpec]
    focus: str
    """这个用例考察什么，失败归因时按 focus 聚类。"""

    expectClarify: bool = False
    """是否应当触发澄清。False 的用例检验系统会不会过度澄清。"""

    expectProactiveReport: list[str] = field(default_factory=list)
    """用户没提但系统应主动报告的缺陷码（L4 专用）。"""


def _spec(code: str, ratio: float, columns: list[str] | None = None) -> DefectSpec:
    return DefectSpec(code=code, ratio=ratio, columns=columns)


# ── L1 规则类：有明确依据，应自动处理，不该问 ──
_L1_CASES = [
    EvalCase("L1-01", LEVEL_RULE, "把完全重复的记录去掉",
             [_spec("ROW_DUPLICATE", 0.02)],
             "是否调用确定性去重工具而非让模型逐行判断"),
    EvalCase("L1-02", LEVEL_RULE, "体温有明显不合理的值，帮我处理一下",
             [_spec("OUT_OF_RANGE", 0.01, [C.COL_TEMP])],
             "阈值是否来自知识库 VALIDITY 规则，而非模型编造的 35-42"),
    EvalCase("L1-03", LEVEL_RULE, "收缩压超出正常范围的置空",
             [_spec("OUT_OF_RANGE", 0.008, [C.COL_SBP])],
             "多列阈值是否分别检索，而非套用同一组数字"),
    EvalCase("L1-04", LEVEL_RULE, "把体温和收缩压的异常值都清掉",
             [_spec("OUT_OF_RANGE", 0.01, [C.COL_TEMP, C.COL_SBP])],
             "一次需求涉及多列时是否逐列检索知识库"),
    EvalCase("L1-05", LEVEL_RULE, "去重，然后把越界的生理值置空",
             [_spec("ROW_DUPLICATE", 0.02), _spec("OUT_OF_RANGE", 0.01)],
             "多步骤任务的顺序是否正确（先去重再校验，避免重复计数）"),
    EvalCase("L1-06", LEVEL_RULE, "心率有异常值",
             [_spec("OUT_OF_RANGE", 0.006, [C.COL_HR])],
             "需求表述模糊（只说“有异常”没说怎么办）时是否仍走知识库"),
    EvalCase("L1-07", LEVEL_RULE, "呼吸频率清洗一下",
             [_spec("OUT_OF_RANGE", 0.005, [C.COL_RR])],
             "口语化需求的意图识别"),
    EvalCase("L1-08", LEVEL_RULE, "血氧不可能超过100，处理掉",
             [_spec("OUT_OF_RANGE", 0.004, [C.COL_SPO2])],
             "用户自带判断依据时，是否与知识库交叉验证"),
    EvalCase("L1-09", LEVEL_RULE, "数据里有重复行和越界值，都清理干净",
             [_spec("ROW_DUPLICATE", 0.03), _spec("OUT_OF_RANGE", 0.012)],
             "复合需求的任务拆解"),
    EvalCase("L1-10", LEVEL_RULE, "帮我把数据去个重",
             [_spec("ROW_DUPLICATE", 0.05)],
             "高重复率下的执行成功率与性能"),
]

# ── L2 结构类：代码正确性与边界处理 ──
_L2_CASES = [
    EvalCase("L2-01", LEVEL_STRUCTURE, "日期格式乱七八糟，统一一下",
             [_spec("DATE_FORMAT_MIXED", 0.2)],
             "多格式解析的完整性，是否漏掉两位年份格式"),
    EvalCase("L2-02", LEVEL_STRUCTURE, "用住院ID加入院时间生成唯一键",
             [],
             "复合主键构造与唯一性校验"),
    EvalCase("L2-03", LEVEL_STRUCTURE, "把入院时间和记录时间都转成标准格式",
             [_spec("DATE_FORMAT_MIXED", 0.15, [C.COL_ADMIT_TIME, C.COL_RECORD_TIME])],
             "多个时间列的批量归一"),
    EvalCase("L2-04", LEVEL_STRUCTURE, "按住院次统计每次入院的记录条数",
             [],
             "分组聚合的正确性"),
    EvalCase("L2-05", LEVEL_STRUCTURE, "每8小时切一个窗口",
             [_spec("WINDOW_ALL_NULL", 0.03)],
             "窗口切分的边界处理；空窗口是否被识别"),
    EvalCase("L2-06", LEVEL_STRUCTURE, "日期格式统一后再去重",
             [_spec("DATE_FORMAT_MIXED", 0.2), _spec("ROW_DUPLICATE", 0.02)],
             "步骤依赖：格式不统一时去重会漏（同一时刻的不同写法）"),
    EvalCase("L2-07", LEVEL_STRUCTURE, "把结局时间在入院时间之前的记录标出来",
             [_spec("TIME_INVERSION", 0.02)],
             "时序比较在混合格式下是否仍正确"),
    EvalCase("L2-08", LEVEL_STRUCTURE, "统计每个患者的住院次数",
             [],
             "去重计数的正确性——这是常识级校验的数据来源"),
]

# ── L3 判断类：必须澄清，不得猜测 ──
_L3_CASES = [
    EvalCase("L3-01", LEVEL_JUDGMENT, "升压药这列大部分是空的，帮我填一下",
             [_spec("MISSING_SEMANTIC", 0.9916)],
             "招牌案例：99.16% 缺失是隐含阴性该填 0，不是真实缺失该插值。"
             "系统必须澄清而非直接插值",
             expectClarify=True),
    EvalCase("L3-02", LEVEL_JUDGMENT, "把氧疗这列编码成数值",
             [_spec("FULLWIDTH_MIXED", 0.1), _spec("DUP_CONCAT", 0.03)],
             "招牌案例：用户映射把持续吸氧编为 0，"
             "NEWS 规定任何吸氧计 2 分。应检测冲突并澄清采用哪种口径",
             expectClarify=True),
    EvalCase("L3-03", LEVEL_JUDGMENT, "意识这列转成 AVPU 编码",
             [_spec("NUMERIC_IN_TEXT", 0.02)],
             "TWD 取值语义未知（真实数据 4,986 条）——该问不该猜",
             expectClarify=True),
    EvalCase("L3-04", LEVEL_JUDGMENT, "体温缺失的地方补上",
             [],
             "体温 30.59% 是真实缺失，插值 vs 标记 missing 需要研究设计信息",
             expectClarify=True),
    EvalCase("L3-05", LEVEL_JUDGMENT, "算一下每条记录的 NEWS 评分",
             [],
             "体温列同时有 VALIDITY 和 SEVERITY_SCORING 两类规则——"
             "检索歧义，应按规则类型定位而非语义相似度",
             expectClarify=False),
    EvalCase("L3-06", LEVEL_JUDGMENT, "把体温超出阈值的置空",
             [_spec("OUT_OF_RANGE", 0.01, [C.COL_TEMP])],
             "反向用例：这里“阈值”无歧义（知识库有唯一 VALIDITY 规则），"
             "**不应该**触发澄清。过度澄清同样是失败",
             expectClarify=False),
    EvalCase("L3-07", LEVEL_JUDGMENT, "所有文本列都转成数值编码",
             [_spec("FULLWIDTH_MIXED", 0.1), _spec("NUMERIC_IN_TEXT", 0.02)],
             "多列需澄清时，是否按数据覆盖率降序提问、是否合并同类项",
             expectClarify=True),
]

# ── L4 发现类：用户不问，系统应主动报告 ──
_L4_CASES = [
    EvalCase("L4-01", LEVEL_DISCOVERY, "帮我统计一下患者数和住院次数",
             [_spec("COLUMN_MISALIGN", 1.0)],
             "招牌案例：人均入院 1.00。代码无报错、格式正常、无异常值，"
             "工程上完全成功。只有常识能发现问题",
             expectProactiveReport=["COLUMN_MISALIGN"]),
    EvalCase("L4-02", LEVEL_DISCOVERY, "把入院时间转成日期类型",
             [_spec("TIMESTAMP_ALL_ZERO", 0.85)],
             "时间部分恒为 00:00:00，占比 85%。单行看完全正常，"
             "只有分布统计能暴露",
             expectProactiveReport=["TIMESTAMP_ALL_ZERO"]),
    EvalCase("L4-03", LEVEL_DISCOVERY, "算一下每次住院的平均记录条数",
             [_spec("TIME_INVERSION", 0.03)],
             "记录时间早于入院时间——用户问的是别的事",
             expectProactiveReport=["TIME_INVERSION"]),
    EvalCase("L4-04", LEVEL_DISCOVERY, "看看氧疗这列有哪些取值",
             [_spec("FULLWIDTH_MIXED", 0.15), _spec("DUP_CONCAT", 0.05)],
             "用户只想看枚举，系统应发现全半角混用与重复拼接导致取值虚高",
             expectProactiveReport=["FULLWIDTH_MIXED", "DUP_CONCAT"]),
    EvalCase("L4-05", LEVEL_DISCOVERY, "把血氧列提取出来做个描述统计",
             [_spec("WINDOW_ALL_NULL", 0.05)],
             "连续一段完全空白，均值方差看不出来",
             expectProactiveReport=["WINDOW_ALL_NULL"]),
    EvalCase("L4-06", LEVEL_DISCOVERY, "去个重就行",
             [_spec("ROW_DUPLICATE", 0.02), _spec("COLUMN_MISALIGN", 1.0)],
             "用户只要去重，但数据里同时藏着错位。"
             "检验系统会不会因为“用户没问”就不报",
             expectProactiveReport=["COLUMN_MISALIGN"]),
    EvalCase("L4-07", LEVEL_DISCOVERY, "统计各科室的记录量",
             [_spec("TIMESTAMP_ALL_ZERO", 0.8), _spec("TIME_INVERSION", 0.02)],
             "同时存在两类问题，检验是否只报第一个就停",
             expectProactiveReport=["TIMESTAMP_ALL_ZERO", "TIME_INVERSION"]),
]

ALL_CASES: list[EvalCase] = _L1_CASES + _L2_CASES + _L3_CASES + _L4_CASES


def list_cases() -> list[dict]:
    return [asdict(case) for case in ALL_CASES]


def level_distribution() -> dict[str, int]:
    counts: dict[str, int] = {}
    for case in ALL_CASES:
        counts[case.level] = counts.get(case.level, 0) + 1
    return counts


def validate_catalog() -> None:
    """启动时自检：用例引用的缺陷码必须真实存在，caseId 不得重复。"""
    seen: set[str] = set()
    for case in ALL_CASES:
        if case.caseId in seen:
            raise ValueError(f"重复的 caseId: {case.caseId}")
        seen.add(case.caseId)
        for spec in case.defects:
            if spec.code not in DEFECT_META:
                raise ValueError(f"{case.caseId} 引用了不存在的缺陷码: {spec.code}")
        for code in case.expectProactiveReport:
            if code not in DEFECT_META:
                raise ValueError(f"{case.caseId} 的 expectProactiveReport 引用了不存在的缺陷码: {code}")
