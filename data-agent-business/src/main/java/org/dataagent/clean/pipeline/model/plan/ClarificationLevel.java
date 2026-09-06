package org.dataagent.clean.pipeline.model.plan;

/**
 * 澄清分级。分级的判定是确定性代码，不由 LLM 决定问不问：
 * <pre>
 *   知识库命中 1 条    → HIGH    自动决定，记录 ruleId 作为依据
 *   命中 &gt;1 条         → LOW     语义歧义，必须澄清
 *   可归并到已有澄清项 → MEDIUM  合并进同一个问题
 *   命中 0 条          → LOW     无依据，逐个追问
 * </pre>
 * 过度澄清和澄清不足是同等程度的失败。
 */
public enum ClarificationLevel {

    /** 有唯一依据，系统自己定，只在报告里说明用了哪条规则 */
    HIGH("自动决定"),

    /** 与已有问题同源，合并成一个问题问 */
    MEDIUM("合并提问"),

    /** 必须单独问医生 */
    LOW("必须追问"),
    ;

    private final String label;

    ClarificationLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean needsAsking() {
        return this != HIGH;
    }
}
