"""数据集列定义与临床取值常量。列名在生成器、缺陷注入器、评测用例三处都要用，
集中管理避免拼写不一致。
"""

# ── 标识与时间列 ──
COL_PATIENT = "患者ID"
COL_ADMISSION = "住院ID"
COL_ADMIT_TIME = "入院时间"
COL_RECORD_TIME = "记录时间"
COL_OUTCOME = "结局"
COL_OUTCOME_TIME = "结局时间"

# ── 生理指标列（数值）──
COL_TEMP = "体温"
COL_SBP = "收缩压"
COL_DBP = "舒张压"
COL_HR = "心率"
COL_RR = "呼吸"
COL_SPO2 = "血氧"

# ── 文本列 ──
COL_CONSCIOUS = "意识"
COL_OXYGEN = "氧疗"
COL_VASOPRESSOR = "升压药"

NUMERIC_COLUMNS = [COL_TEMP, COL_SBP, COL_DBP, COL_HR, COL_RR, COL_SPO2]
TEXT_COLUMNS = [COL_CONSCIOUS, COL_OXYGEN, COL_VASOPRESSOR]
DATETIME_COLUMNS = [COL_ADMIT_TIME, COL_RECORD_TIME, COL_OUTCOME_TIME]

ALL_COLUMNS = [
    COL_PATIENT, COL_ADMISSION, COL_ADMIT_TIME, COL_RECORD_TIME,
    COL_TEMP, COL_SBP, COL_DBP, COL_HR, COL_RR, COL_SPO2,
    COL_CONSCIOUS, COL_OXYGEN, COL_VASOPRESSOR,
    COL_OUTCOME, COL_OUTCOME_TIME,
]

# ── 生理指标有效性阈值 ─────────────────────────────
# 与知识库 data_agent_knowledge_rule 的 VALIDITY 规则保持一致。
# 这里是"生成正常值"和"注入越界值"的依据，
# 注意：系统在检测时必须从知识库查这些阈值，不得硬编码。
VALIDITY_RANGE = {
    COL_TEMP: (32.0, 45.0),
    COL_SBP: (0.0, 300.0),
    COL_DBP: (0.0, 200.0),
    COL_HR: (0.0, 300.0),
    COL_RR: (0.0, 80.0),
    COL_SPO2: (0.0, 100.0),
}

# 正常值的生成分布（均值, 标准差），落在有效区间内
NORMAL_DISTRIBUTION = {
    COL_TEMP: (36.8, 0.6),
    COL_SBP: (125.0, 20.0),
    COL_DBP: (75.0, 12.0),
    COL_HR: (82.0, 15.0),
    COL_RR: (18.0, 3.5),
    COL_SPO2: (97.0, 2.0),
}

# ── 缺失率：跨度 10.07% ~ 99.16%）──
MISSING_RATE = {
    COL_TEMP: 0.3059,          # 真实缺失——该测未测，应插值或标记
    COL_SBP: 0.2814,
    COL_DBP: 0.2903,
    COL_HR: 0.1007,
    COL_RR: 0.3512,
    COL_SPO2: 0.2245,
    COL_CONSCIOUS: 0.1832,
    COL_OXYGEN: 0.1204,
    COL_VASOPRESSOR: 0.9916,   # 隐含阴性——绝大多数患者未使用，应填 0
}

# ── 文本列取值（含真实数据里的问题取值）────────────
CONSCIOUS_VALUES = ["清醒", "嗜睡", "浅昏迷", "深昏迷", "谵妄", "TWD"]
CONSCIOUS_WEIGHTS = [0.72, 0.11, 0.06, 0.03, 0.04, 0.04]

# "持续氧气吸入（一天）" 是真实数据里占比最大的取值（33.62%），
# 也是与 NEWS 标准冲突的招牌 case：
# 用户映射把它编为 0（较低呼吸支持），而 NEWS 规定任何吸氧计 2 分。
OXYGEN_VALUES = [
    "未吸氧",
    "鼻导管吸氧",
    "面罩吸氧(无创)",
    "持续氧气吸入（一天）",
    "呼吸机辅助通气",
]
OXYGEN_WEIGHTS = [0.38, 0.16, 0.07, 0.3362, 0.0538]

VASOPRESSOR_VALUES = ["多巴胺", "去甲肾上腺素", "肾上腺素"]
VASOPRESSOR_WEIGHTS = [0.5, 0.35, 0.15]

# 结局定义：非计划转入 ICU / 非计划插管 / 死亡，多结局取时间最早者
OUTCOME_VALUES = ["无", "转ICU", "插管", "死亡"]
OUTCOME_WEIGHTS = [0.93, 0.04, 0.02, 0.01]
