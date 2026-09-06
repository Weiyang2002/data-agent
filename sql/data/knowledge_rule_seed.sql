-- ════════════════════════════════════════════════════════════
-- 知识库首批录入（M2）
--
-- ════════════════════════════════════════════════════════════

USE `data_agent`;

-- 幂等：先清掉本文件负责的 ID 段，再整段重插。
-- 用 DELETE + INSERT 而不是 ON DUPLICATE KEY UPDATE，
-- 是为了让「本文件删掉一条规则」也能真的生效——
-- 增量更新做不到这一点，删掉的规则会永远留在库里。
DELETE FROM `data_agent_knowledge_rule` WHERE `id` BETWEEN 1000 AND 4999;


-- ════════════════════════════════════════════════════════════
-- 一、VALIDITY —— 有效性区间 / 合法取值（11 条）
--
-- 这一组是 L1 规则类任务的依据。医生说「体温有明显不合理的值」时，
-- 系统必须查到这里的 [32, 45]，而不是让模型凭记忆报一个 35–42。
-- 两者都「像是对的」，但只有前者能回答「你凭什么」。
-- ════════════════════════════════════════════════════════════

INSERT INTO `data_agent_knowledge_rule`
(`id`, `column_name`, `column_alias`, `rule_type`, `scoring_system`, `payload`, `source_doc`, `source_locator`)
VALUES
(1001, '体温', '["T","TEMP","temperature","Temp","体温(℃)"]', 'VALIDITY', '',
 '{"min": 32.0, "max": 45.0, "unit": "℃", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第1行'),

(1002, '收缩压', '["SBP","sbp","systolic","收缩压(mmHg)","高压"]', 'VALIDITY', '',
 '{"min": 0.0, "max": 300.0, "unit": "mmHg", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第2行'),

(1003, '舒张压', '["DBP","dbp","diastolic","舒张压(mmHg)","低压"]', 'VALIDITY', '',
 '{"min": 0.0, "max": 200.0, "unit": "mmHg", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第3行'),

(1004, '心率', '["HR","hr","heart_rate","脉搏","P","pulse"]', 'VALIDITY', '',
 '{"min": 0.0, "max": 300.0, "unit": "次/分", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第4行'),

(1005, '呼吸', '["RR","rr","resp","respiratory_rate","呼吸频率","R"]', 'VALIDITY', '',
 '{"min": 0.0, "max": 80.0, "unit": "次/分", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第5行'),

(1006, '血氧', '["SpO2","spo2","SPO2","血氧饱和度","氧饱和度","SaO2"]', 'VALIDITY', '',
 '{"min": 0.0, "max": 100.0, "unit": "%", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第6行'),

(1007, '尿量', '["UO","urine_output","24h尿量"]', 'VALIDITY', '',
 '{"min": 0.0, "max": 10000.0, "unit": "ml/24h", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第7行'),

(1008, '体重', '["WT","weight","BW","体重(kg)"]', 'VALIDITY', '',
 '{"min": 0.5, "max": 300.0, "unit": "kg", "onViolation": "SET_NULL"}',
 '院内生理指标有效性规范', '表1 第8行'),

-- 文本列的「有效性」是合法取值集合，不是数值区间。
-- 用同一个 rule_type 承载两种形状，是因为提问方式相同：
-- 「这一列什么样的值算合法」。payload 的形状差异由 Java 侧按字段判断。
(1009, '意识', '["AVPU","consciousness","神志","意识状态","GCS描述"]', 'VALIDITY', '',
 '{"allowedValues": ["清醒","嗜睡","浅昏迷","深昏迷","谵妄"], "onViolation": "FLAG_FOR_REVIEW"}',
 '院内数据字典', '意识列 取值域'),

(1010, '氧疗', '["oxygen","O2","氧疗方式","吸氧","呼吸支持"]', 'VALIDITY', '',
 '{"allowedValues": ["未吸氧","鼻导管吸氧","面罩吸氧(无创)","持续氧气吸入（一天）","呼吸机辅助通气"], "onViolation": "FLAG_FOR_REVIEW"}',
 '院内数据字典', '氧疗列 取值域'),

(1011, '升压药', '["vasopressor","血管活性药","升压药物"]', 'VALIDITY', '',
 '{"allowedValues": ["多巴胺","去甲肾上腺素","肾上腺素"], "onViolation": "FLAG_FOR_REVIEW"}',
 '院内数据字典', '升压药列 取值域');


-- ════════════════════════════════════════════════════════════
-- 二、SEVERITY_SCORING —— 预警评分分档（22 条）
--
-- ★ 这一组是「命中多条必须澄清」的主战场。
--
-- 查 (体温, SEVERITY_SCORING) 会命中 NEWS / MEWS / SEWS 三条，
-- 三套系统对同一个体温给出的分数完全不同。
-- 系统**不挑一条用**，而是问医生「本次分析用哪套评分系统」——
-- 挑错一套，整个队列的危重度分层就全错了，而且没有任何报错。
--
-- 反过来，查 (体温, VALIDITY) 只会命中 1 条，那就自动决定、记录依据。
-- 同一列上两种规则类型、两种处理方式，这就是三级分流的意义。
--
-- bands 语义：命中第一个满足 min<=v<=max 的档；min/max 缺省表示该侧无界。
-- ════════════════════════════════════════════════════════════

-- ── NEWS2（7 条）────────────────────────────────────
INSERT INTO `data_agent_knowledge_rule`
(`id`, `column_name`, `column_alias`, `rule_type`, `scoring_system`, `payload`, `source_doc`, `source_locator`)
VALUES
(2001, '呼吸', '["RR","rr","resp","呼吸频率"]', 'SEVERITY_SCORING', 'NEWS',
 '{"unit": "次/分", "bands": [{"max": 8, "score": 3}, {"min": 9, "max": 11, "score": 1}, {"min": 12, "max": 20, "score": 0}, {"min": 21, "max": 24, "score": 2}, {"min": 25, "score": 3}]}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table 呼吸频率行'),

(2002, '血氧', '["SpO2","spo2","血氧饱和度"]', 'SEVERITY_SCORING', 'NEWS',
 '{"unit": "%", "scale": "Scale 1（无高碳酸血症呼吸衰竭）", "bands": [{"max": 91, "score": 3}, {"min": 92, "max": 93, "score": 2}, {"min": 94, "max": 95, "score": 1}, {"min": 96, "score": 0}]}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table SpO2 Scale 1 行'),

(2003, '氧疗', '["oxygen","O2","呼吸支持"]', 'SEVERITY_SCORING', 'NEWS',
 '{"mappings": [{"category": "Air", "score": 0}, {"category": "Any O2", "score": 2}], "note": "NEWS2 只区分「未吸氧」与「吸氧」两档，不区分吸氧方式；任何形式的氧疗一律计 2 分"}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table Air or oxygen 行'),

(2004, '收缩压', '["SBP","sbp","systolic"]', 'SEVERITY_SCORING', 'NEWS',
 '{"unit": "mmHg", "bands": [{"max": 90, "score": 3}, {"min": 91, "max": 100, "score": 2}, {"min": 101, "max": 110, "score": 1}, {"min": 111, "max": 219, "score": 0}, {"min": 220, "score": 3}]}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table 收缩压行'),

(2005, '心率', '["HR","hr","pulse","脉搏"]', 'SEVERITY_SCORING', 'NEWS',
 '{"unit": "次/分", "bands": [{"max": 40, "score": 3}, {"min": 41, "max": 50, "score": 1}, {"min": 51, "max": 90, "score": 0}, {"min": 91, "max": 110, "score": 1}, {"min": 111, "max": 130, "score": 2}, {"min": 131, "score": 3}]}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table 脉搏行'),

(2006, '意识', '["ACVPU","AVPU","consciousness","神志"]', 'SEVERITY_SCORING', 'NEWS',
 '{"mappings": [{"category": "Alert", "score": 0}, {"category": "Confusion", "score": 3}, {"category": "Voice", "score": 3}, {"category": "Pain", "score": 3}, {"category": "Unresponsive", "score": 3}], "note": "NEWS2 用 ACVPU，新发意识模糊(C)与 V/P/U 同样计 3 分"}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table 意识水平行'),

(2007, '体温', '["T","TEMP","temperature"]', 'SEVERITY_SCORING', 'NEWS',
 '{"unit": "℃", "bands": [{"max": 35.0, "score": 3}, {"min": 35.1, "max": 36.0, "score": 1}, {"min": 36.1, "max": 38.0, "score": 0}, {"min": 38.1, "max": 39.0, "score": 1}, {"min": 39.1, "score": 2}]}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table 体温行'),

-- ── MEWS（5 条，Subbe 2001 版本）─────────────────────
-- 只录 5 项：Subbe 的 MEWS 就是 SBP / HR / RR / 体温 / AVPU 五参数。
-- 有些文献版本额外带尿量，那是更早的 Morgan EWS，不能混为一谈——
-- 混录会让「用的是哪一版 MEWS」变成一个说不清的问题。
(2011, '收缩压', '["SBP","sbp"]', 'SEVERITY_SCORING', 'MEWS',
 '{"unit": "mmHg", "bands": [{"max": 70, "score": 3}, {"min": 71, "max": 80, "score": 2}, {"min": 81, "max": 100, "score": 1}, {"min": 101, "max": 199, "score": 0}, {"min": 200, "score": 2}]}',
 'MEWS (Subbe CP et al., QJM 2001)', 'Table 1 收缩压行'),

(2012, '心率', '["HR","hr","pulse"]', 'SEVERITY_SCORING', 'MEWS',
 '{"unit": "次/分", "bands": [{"max": 40, "score": 2}, {"min": 41, "max": 50, "score": 1}, {"min": 51, "max": 100, "score": 0}, {"min": 101, "max": 110, "score": 1}, {"min": 111, "max": 129, "score": 2}, {"min": 130, "score": 3}]}',
 'MEWS (Subbe CP et al., QJM 2001)', 'Table 1 心率行'),

(2013, '呼吸', '["RR","rr"]', 'SEVERITY_SCORING', 'MEWS',
 '{"unit": "次/分", "bands": [{"max": 8, "score": 2}, {"min": 9, "max": 14, "score": 0}, {"min": 15, "max": 20, "score": 1}, {"min": 21, "max": 29, "score": 2}, {"min": 30, "score": 3}]}',
 'MEWS (Subbe CP et al., QJM 2001)', 'Table 1 呼吸行'),

(2014, '体温', '["T","TEMP"]', 'SEVERITY_SCORING', 'MEWS',
 '{"unit": "℃", "bands": [{"max": 34.9, "score": 2}, {"min": 35.0, "max": 38.4, "score": 0}, {"min": 38.5, "score": 2}]}',
 'MEWS (Subbe CP et al., QJM 2001)', 'Table 1 体温行'),

(2015, '意识', '["AVPU"]', 'SEVERITY_SCORING', 'MEWS',
 '{"mappings": [{"category": "Alert", "score": 0}, {"category": "Voice", "score": 1}, {"category": "Pain", "score": 2}, {"category": "Unresponsive", "score": 3}], "note": "MEWS 用 AVPU 四级，V/P/U 分别计 1/2/3 分——与 NEWS2 的「一律 3 分」不同，这正是两套系统不可互换的例证"}',
 'MEWS (Subbe CP et al., QJM 2001)', 'Table 1 神经系统行'),

-- ── SEWS（6 条，Paterson 2006 版本）──────────────────
(2021, '呼吸', '["RR","rr"]', 'SEVERITY_SCORING', 'SEWS',
 '{"unit": "次/分", "bands": [{"max": 8, "score": 3}, {"min": 9, "max": 17, "score": 0}, {"min": 18, "max": 20, "score": 1}, {"min": 21, "max": 29, "score": 2}, {"min": 30, "score": 3}]}',
 'SEWS (Paterson R et al., Clin Med 2006)', 'Standardised EWS chart 呼吸行'),

(2022, '血氧', '["SpO2","spo2"]', 'SEVERITY_SCORING', 'SEWS',
 '{"unit": "%", "bands": [{"max": 84, "score": 3}, {"min": 85, "max": 89, "score": 2}, {"min": 90, "max": 92, "score": 1}, {"min": 93, "score": 0}]}',
 'SEWS (Paterson R et al., Clin Med 2006)', 'Standardised EWS chart 血氧行'),

(2023, '体温', '["T","TEMP"]', 'SEVERITY_SCORING', 'SEWS',
 '{"unit": "℃", "bands": [{"max": 33.9, "score": 3}, {"min": 34.0, "max": 34.9, "score": 2}, {"min": 35.0, "max": 37.4, "score": 0}, {"min": 37.5, "score": 2}]}',
 'SEWS (Paterson R et al., Clin Med 2006)', 'Standardised EWS chart 体温行'),

(2024, '收缩压', '["SBP","sbp"]', 'SEVERITY_SCORING', 'SEWS',
 '{"unit": "mmHg", "bands": [{"max": 69, "score": 3}, {"min": 70, "max": 79, "score": 2}, {"min": 80, "max": 99, "score": 1}, {"min": 100, "max": 199, "score": 0}, {"min": 200, "score": 2}]}',
 'SEWS (Paterson R et al., Clin Med 2006)', 'Standardised EWS chart 收缩压行'),

(2025, '心率', '["HR","hr"]', 'SEVERITY_SCORING', 'SEWS',
 '{"unit": "次/分", "bands": [{"max": 29, "score": 3}, {"min": 30, "max": 39, "score": 2}, {"min": 40, "max": 49, "score": 1}, {"min": 50, "max": 99, "score": 0}, {"min": 100, "max": 109, "score": 1}, {"min": 110, "max": 129, "score": 2}, {"min": 130, "score": 3}]}',
 'SEWS (Paterson R et al., Clin Med 2006)', 'Standardised EWS chart 心率行'),

(2026, '意识', '["AVPU"]', 'SEVERITY_SCORING', 'SEWS',
 '{"mappings": [{"category": "Alert", "score": 0}, {"category": "Voice", "score": 1}, {"category": "Pain", "score": 2}, {"category": "Unresponsive", "score": 3}]}',
 'SEWS (Paterson R et al., Clin Med 2006)', 'Standardised EWS chart 神经系统行'),

-- ── CART（4 条，Churpek 2012）───────────────────────
-- CART 用舒张压而不是收缩压，且带年龄项——这是它与其他三套的结构差异。
-- 年龄不在本项目数据集里，规则照录：知识库覆盖面不该被当前数据集裁剪，
-- 否则换一份数据就要重新录规则。
(2031, '呼吸', '["RR","rr"]', 'SEVERITY_SCORING', 'CART',
 '{"unit": "次/分", "bands": [{"max": 20, "score": 0}, {"min": 21, "max": 23, "score": 8}, {"min": 24, "max": 25, "score": 12}, {"min": 26, "max": 29, "score": 15}, {"min": 30, "score": 22}]}',
 'CART (Churpek MM et al., Crit Care Med 2012)', 'Table 2 呼吸行'),

(2032, '心率', '["HR","hr"]', 'SEVERITY_SCORING', 'CART',
 '{"unit": "次/分", "bands": [{"max": 109, "score": 0}, {"min": 110, "max": 139, "score": 4}, {"min": 140, "score": 13}]}',
 'CART (Churpek MM et al., Crit Care Med 2012)', 'Table 2 心率行'),

(2033, '舒张压', '["DBP","dbp","diastolic"]', 'SEVERITY_SCORING', 'CART',
 '{"unit": "mmHg", "bands": [{"max": 34, "score": 13}, {"min": 35, "max": 39, "score": 6}, {"min": 40, "max": 49, "score": 4}, {"min": 50, "score": 0}], "note": "CART 用舒张压而非收缩压，与 NEWS/MEWS/SEWS 不同"}',
 'CART (Churpek MM et al., Crit Care Med 2012)', 'Table 2 舒张压行'),

(2034, '年龄', '["age","AGE","岁"]', 'SEVERITY_SCORING', 'CART',
 '{"unit": "岁", "bands": [{"max": 54, "score": 0}, {"min": 55, "max": 69, "score": 4}, {"min": 70, "score": 9}]}',
 'CART (Churpek MM et al., Crit Care Med 2012)', 'Table 2 年龄行');


-- ════════════════════════════════════════════════════════════
-- 三、TEXT_MAPPING —— 文本取值到标准编码（6 条）
--
-- ★ 3002 是本项目的招牌 case（v1 §5.4）。
--
-- 院内数据字典把「持续氧气吸入（一天）」编成 0（较低呼吸支持），
-- 而 NEWS2 规定任何形式的氧疗一律计 2 分。这个取值占全部数据的 33.62%，
-- 编错一次，三分之一的数据危重度被系统性低估。
--
-- 系统的处理方式是**确定性比对**而不是让 LLM 发现：
--   LLM 只做「持续氧气吸入 属于 NEWS 的哪一类」这一步语义归类，
--   「用户给 0、NEWS 给 2、所以冲突」是代码判断的。
-- ════════════════════════════════════════════════════════════

INSERT INTO `data_agent_knowledge_rule`
(`id`, `column_name`, `column_alias`, `rule_type`, `scoring_system`, `payload`, `source_doc`, `source_locator`)
VALUES
(3001, '意识', '["AVPU","consciousness","神志"]', 'TEXT_MAPPING', 'AVPU',
 '{"mappings": [{"value": "清醒", "code": "A", "meaning": "Alert"}, {"value": "嗜睡", "code": "V", "meaning": "Responds to Voice"}, {"value": "浅昏迷", "code": "P", "meaning": "Responds to Pain"}, {"value": "深昏迷", "code": "U", "meaning": "Unresponsive"}]}',
 'AVPU 意识分级标准', 'AVPU 四级定义'),

(3002, '氧疗', '["oxygen","O2","呼吸支持"]', 'TEXT_MAPPING', 'NEWS',
 '{"mappings": [{"value": "未吸氧", "category": "Air", "score": 0}, {"value": "鼻导管吸氧", "category": "Any O2", "score": 2}, {"value": "面罩吸氧(无创)", "category": "Any O2", "score": 2}, {"value": "持续氧气吸入（一天）", "category": "Any O2", "score": 2}, {"value": "呼吸机辅助通气", "category": "Any O2", "score": 2}], "note": "★ 招牌冲突点：院内字典把「持续氧气吸入（一天）」编为 0，NEWS2 要求计 2 分。该取值占全量数据 33.62%"}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table Air or oxygen 行'),

(3003, '意识', '["AVPU","consciousness"]', 'TEXT_MAPPING', 'NEWS',
 '{"mappings": [{"value": "清醒", "category": "Alert", "score": 0}, {"value": "谵妄", "category": "Confusion", "score": 3}, {"value": "嗜睡", "category": "Voice", "score": 3}, {"value": "浅昏迷", "category": "Pain", "score": 3}, {"value": "深昏迷", "category": "Unresponsive", "score": 3}]}',
 'NEWS2 (Royal College of Physicians, 2017)', 'NEWS2 scoring table 意识水平行'),

-- 以下三条是院内自定义映射，与上面的标准映射并存。
-- 查 (意识, TEXT_MAPPING) 会命中 3001/3003/3004 三条 —— 必须澄清用哪一套。
(3004, '意识', '["AVPU","consciousness"]', 'TEXT_MAPPING', '',
 '{"mappings": [{"value": "清醒", "code": 0}, {"value": "嗜睡", "code": 1}, {"value": "谵妄", "code": 2}, {"value": "浅昏迷", "code": 3}, {"value": "深昏迷", "code": 4}], "unmapped": ["TWD"], "note": "TWD 在院内字典里无定义，占比约 4%，需向医生澄清其含义后再编码"}',
 '院内数据字典（占位，上线前须替换为实际文档）', '意识列 编码表'),

(3005, '氧疗', '["oxygen","O2"]', 'TEXT_MAPPING', '',
 '{"mappings": [{"value": "未吸氧", "code": 0}, {"value": "鼻导管吸氧", "code": 1}, {"value": "面罩吸氧(无创)", "code": 2}, {"value": "持续氧气吸入（一天）", "code": 0}, {"value": "呼吸机辅助通气", "code": 3}], "note": "★ 注意 code=0 的「持续氧气吸入（一天）」与规则 3002 的 NEWS 分值 2 直接冲突"}',
 '院内数据字典（占位，上线前须替换为实际文档）', '氧疗列 编码表'),

(3006, '升压药', '["vasopressor","血管活性药"]', 'TEXT_MAPPING', '',
 '{"mappings": [{"value": "多巴胺", "code": 1}, {"value": "去甲肾上腺素", "code": 1}, {"value": "肾上腺素", "code": 1}], "note": "只定义「有值时怎么编码」。★ 空值的语义不在此规则内 —— 见 MISSING_SEMANTICS 段的说明"}',
 '院内数据字典（占位，上线前须替换为实际文档）', '升压药列 编码表');


-- ════════════════════════════════════════════════════════════
-- 四、MISSING_SEMANTICS —— 缺失语义（5 条）
--
-- ★★ 本段最重要的是**没有录进来的那三列**。
--
-- 录了：体温 / 收缩压 / 心率 / 呼吸 / 血氧 —— 生理指标的空值语义明确，
--       就是「该测未测」，处理方式是插值或显式标记。查到 1 条，自动决定。
--
-- 没录：升压药 / 意识 / 氧疗 —— 空值语义需要临床判断。
--       升压药 99.16% 缺失，是「绝大多数患者没用升压药」（该填 0），
--       还是「用药记录压根没接进来」（该插值或整列弃用）？
--       两种处理方式结论完全相反，而数据本身无法区分。
--
-- 所以查 (升压药, MISSING_SEMANTICS) 会命中 0 条 → NO_EVIDENCE → 触发澄清。
-- **这不是知识库不完整，这是知识库诚实。**
-- 一个「什么都答得上来」的知识库，遇到这种情况只会给出一个自信的错误答案。
-- ════════════════════════════════════════════════════════════

INSERT INTO `data_agent_knowledge_rule`
(`id`, `column_name`, `column_alias`, `rule_type`, `scoring_system`, `payload`, `source_doc`, `source_locator`)
VALUES
(4001, '体温', '["T","TEMP","temperature"]', 'MISSING_SEMANTICS', '',
 '{"semantics": "TRUE_MISSING", "recommendedAction": "INTERPOLATE_OR_FLAG", "rationale": "生理指标缺失代表该时点未测量，不代表数值为 0；实测缺失率 30.59%"}',
 '临床数据处理Agent 设计文档 v1', '§6.1 缺失率分析'),

(4002, '收缩压', '["SBP","sbp"]', 'MISSING_SEMANTICS', '',
 '{"semantics": "TRUE_MISSING", "recommendedAction": "INTERPOLATE_OR_FLAG", "rationale": "同体温；实测缺失率 28.14%"}',
 '临床数据处理Agent 设计文档 v1', '§6.1 缺失率分析'),

(4003, '心率', '["HR","hr"]', 'MISSING_SEMANTICS', '',
 '{"semantics": "TRUE_MISSING", "recommendedAction": "INTERPOLATE_OR_FLAG", "rationale": "同体温；实测缺失率 10.07%，是全表最低"}',
 '临床数据处理Agent 设计文档 v1', '§6.1 缺失率分析'),

(4004, '呼吸', '["RR","rr"]', 'MISSING_SEMANTICS', '',
 '{"semantics": "TRUE_MISSING", "recommendedAction": "INTERPOLATE_OR_FLAG", "rationale": "同体温；实测缺失率 35.12%"}',
 '临床数据处理Agent 设计文档 v1', '§6.1 缺失率分析'),

(4005, '血氧', '["SpO2","spo2"]', 'MISSING_SEMANTICS', '',
 '{"semantics": "TRUE_MISSING", "recommendedAction": "INTERPOLATE_OR_FLAG", "rationale": "同体温；实测缺失率 22.45%"}',
 '临床数据处理Agent 设计文档 v1', '§6.1 缺失率分析');


-- ════════════════════════════════════════════════════════════
-- 录入自检
-- ════════════════════════════════════════════════════════════
SELECT rule_type, COUNT(*) AS 行数 FROM data_agent_knowledge_rule
WHERE effective_flag = 1 GROUP BY rule_type ORDER BY rule_type;

-- 会命中多条、因而必须触发澄清的 (列, 规则类型) 组合：
SELECT column_name, rule_type, COUNT(*) AS 命中数,
       GROUP_CONCAT(IF(scoring_system = '', '院内', scoring_system) ORDER BY id) AS 冲突来源
FROM data_agent_knowledge_rule
WHERE effective_flag = 1
GROUP BY column_name, rule_type
HAVING COUNT(*) > 1
ORDER BY 命中数 DESC, column_name;
