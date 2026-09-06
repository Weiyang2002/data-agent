package org.dataagent.clean.pipeline.model.validate;

/**
 * 校验发现的层级，「分层检出率」指标的分组依据。量化的是确定性代码能覆盖到哪里、
 * 从哪里开始需要 LLM。检出率逐层下降是预期结果。
 */
public enum FindingLevel {

    /** 行级：单行即可判定，如越界、重复。确定性代码 + 知识库阈值 */
    ROW("行级"),

    /** 分布级：单行正常，整列统计才异常，如 00:00:00 占比 80%。确定性代码 */
    DISTRIBUTION("分布级"),

    /** 结构级：连续窗口内整列空白。确定性统计推断 */
    STRUCTURE("结构级"),

    /** 常识级：统计上完全正常，只有临床常识能发现（如人均入院次数塌成 1.00）。 */
    COMMON_SENSE("常识级"),

    /** 需要医生判断，系统不下结论，如缺失语义 */
    CLARIFY("待澄清"),
    ;

    private final String label;

    FindingLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static FindingLevel parse(String value) {
        if (value == null) {
            return DISTRIBUTION;
        }
        String normalized = value.trim().toUpperCase();
        for (FindingLevel level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        return DISTRIBUTION;
    }
}
