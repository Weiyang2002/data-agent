/**
 * 视图共用的口径常量。每一条都在后端有权威定义（FindingLevel / PlanAction /
 * MetricsAggregator），集中一份避免各视图抄出不一致。
 */

/** 校验/检出的五个层级。权威：pipeline/model/validate/FindingLevel.java */
export const FINDING_LEVELS = [
  { code: 'ROW',          label: '行级',   how: '确定性代码 + 知识库阈值' },
  { code: 'DISTRIBUTION', label: '分布级', how: '确定性代码（整列统计）' },
  { code: 'STRUCTURE',    label: '结构级', how: '确定性统计推断（连续窗口全空）' },
  { code: 'COMMON_SENSE', label: '常识级', how: 'LLM，能力边界在这一层' },
  { code: 'CLARIFY',      label: '待澄清', how: '不下结论，交给医生' }
]

export const LEVEL_LABEL = Object.fromEntries(FINDING_LEVELS.map(level => [level.code, level.label]))

/**
 * 清洗动作词表。权威：pipeline/model/plan/PlanAction.java。`needsEvidence` 用于
 * 标出「需要临床依据的动作却带着空 ruleIds」。
 */
export const PLAN_ACTIONS = {
  DEDUPLICATE:          { label: '整行去重',     needsEvidence: false },
  NULLIFY_OUT_OF_RANGE: { label: '越界值置空',   needsEvidence: true,  ruleType: 'VALIDITY' },
  NORMALIZE_DATETIME:   { label: '日期格式归一', needsEvidence: false },
  NORMALIZE_FULLWIDTH:  { label: '全半角归一',   needsEvidence: false },
  SPLIT_DUP_CONCAT:     { label: '重复拼接还原', needsEvidence: false },
  MAP_TEXT_CODE:        { label: '文本取值编码', needsEvidence: true,  ruleType: 'TEXT_MAPPING' },
  FILL_MISSING:         { label: '缺失值处理',   needsEvidence: true,  ruleType: 'MISSING_SEMANTICS' },
  COMPUTE_SCORE:        { label: '预警评分计算', needsEvidence: true,  ruleType: 'SEVERITY_SCORING' },
  UNKNOWN:              { label: '未识别的动作', needsEvidence: false }
}

/** 评测用例的四个层级 */
export const CASE_LEVELS = [
  { code: 'L1_RULE',      label: 'L1 规则类' },
  { code: 'L2_STRUCTURE', label: 'L2 结构类' },
  { code: 'L3_JUDGMENT',  label: 'L3 判断类' },
  { code: 'L4_DISCOVERY', label: 'L4 发现类' }
]

/**
 * 指标口径文案，前端内置的一份。`metrics[].note` 只在 POST /api/eval/run 的报告里
 * 返回、不落库，历史轮次拿不到，故内置并在页面标注来源
 * （doc/学习文档/M3-基线指标.md）。
 */
export const METRIC_NOTES = {
  casePassed: '归因为 OK 或 CLARIFY_ONLY 的用例数。CLARIFY_ONLY 计通过：该问的问对了，'
    + '而「答完之后做没做对」本轮测不了（澄清答复回传接口未实现）',
  execSuccessRate: '分母是真的发起过沙箱执行的用例；全部待澄清、或需求落不进动作词表的用例不计入',
  resultCorrectRate: '判据 = 有输出 且 已执行步骤全成功 且 需求指向的缺陷在处理后不再被检出；'
    + 'expectClarify=true 的用例不计入',
  clarifyPrecision: '双向计分：该问的问了 × 不该问的没问。过度澄清与澄清不足同等计罚 —— '
    + '只罚前者的话，最优策略会退化成「什么都问」，而医生不会回答 15 个问题',
  discoveryRate: 'golden 中 userAsked=false 的缺陷实例被报告的比例 —— 用户没问，系统该不该说',
  selfRepairRate: '分母是发生过自修复的步骤数（按 task_code + step_no 去重），'
    + '不按执行行数 —— 一个步骤重试 3 次只算一个样本',
  specComplianceRate: '只在行级有意义。分布/结构/常识层本来就没有院内规范可引，'
    + '全层合并算出来的数字是误导',
  latency: '各阶段 cost_millis 求和（只算 depth=0 的顶层阶段）；'
    + '不用 end_time - start_time 是因为 DATETIME 只到秒',
  tokenTotal: 'M5 起采集。M1–M4 各轮为 null —— 空着比填一个估算值诚实'
}

/** 分层检出率旁边的话。常识级的低值是发现，不是失败。 */
export const LEVEL_DETECT_NOTE = {
  ROW: '单行即可判定（越界、重复），确定性代码 + 知识库阈值',
  DISTRIBUTION: '单行正常、整列统计才异常（时间戳 00:00:00 占 80%），确定性代码',
  STRUCTURE: '连续窗口内整列空白，确定性统计推断',
  COMMON_SENSE: '统计上完全正常，只有临床常识能发现（人均入院次数塌成 1.00）。'
    + '这一层的低值是能力边界的位置，不是一个失败的数字 —— '
    + '一条逐层下降的曲线比一个 95% 的总检出率有信息量得多',
  CLARIFY: '需要医生判断，系统不下结论'
}
