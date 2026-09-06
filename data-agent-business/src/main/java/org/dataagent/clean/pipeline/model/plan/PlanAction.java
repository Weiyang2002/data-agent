package org.dataagent.clean.pipeline.model.plan;

import org.dataagent.clean.pipeline.knowledge.RuleType;

/**
 * 清洗动作，「LLM 规划」与「确定性分流」之间的接口。模型只把需求映射成动作序列
 * （枚举值 + 目标列），不决定是否澄清、不产出阈值。每个动作自带
 * {@link #requiredRuleType()}，声明执行需要哪一类临床依据，之后的分流全是代码。
 */
public enum PlanAction {

    /** 整行完全重复去重。无歧义、无临床阈值，永远自动执行 */
    DEDUPLICATE("整行去重", null),

    /** 越界值置空。需要 VALIDITY 阈值 */
    NULLIFY_OUT_OF_RANGE("越界值置空", RuleType.VALIDITY),

    /** 日期格式统一为 ISO。纯格式问题，无临床判断 */
    NORMALIZE_DATETIME("日期格式归一", null),

    /** 全半角统一。纯格式问题 */
    NORMALIZE_FULLWIDTH("全半角归一", null),

    /** 重复拼接的取值还原。纯字符串结构问题 */
    SPLIT_DUP_CONCAT("重复拼接还原", null),

    /** 文本取值映射为编码。需要 TEXT_MAPPING，常有多套并存 */
    MAP_TEXT_CODE("文本取值编码", RuleType.TEXT_MAPPING),

    /** 缺失值填充。需要 MISSING_SEMANTICS */
    FILL_MISSING("缺失值处理", RuleType.MISSING_SEMANTICS),

    /** 计算预警评分。需要 SEVERITY_SCORING */
    COMPUTE_SCORE("预警评分计算", RuleType.SEVERITY_SCORING),

    /** 模型给了识别不了的动作时的兜底，不执行只记录 */
    UNKNOWN("未识别的动作", null),
    ;

    private final String label;
    private final RuleType requiredRuleType;

    PlanAction(String label, RuleType requiredRuleType) {
        this.label = label;
        this.requiredRuleType = requiredRuleType;
    }

    public String getLabel() {
        return label;
    }

    /** 执行本动作需要的临床依据类型；null 表示纯工程操作，不需要临床判断 */
    public RuleType requiredRuleType() {
        return requiredRuleType;
    }

    public boolean needsClinicalEvidence() {
        return requiredRuleType != null;
    }

    /** 认不出来返回 UNKNOWN，不抛异常，但失败要可见 */
    public static PlanAction parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim().toUpperCase();
        for (PlanAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        return UNKNOWN;
    }
}
