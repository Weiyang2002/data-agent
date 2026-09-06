package org.dataagent.clean.pipeline.knowledge;

/**
 * 知识库规则类型。它代表提问的种类（「什么值算合法」与「这个值该记几分」是不同
 * 问题，答案来源和冲突处理都不同），进唯一键而非塞在 payload 里。
 */
public enum RuleType {

    /** 生理指标有效性区间 / 文本列合法取值 */
    VALIDITY("有效性区间或合法取值"),

    /** 预警评分分档：NEWS / MEWS / SEWS / CART */
    SEVERITY_SCORING("预警评分分档"),

    /** 文本取值到标准编码的映射 */
    TEXT_MAPPING("文本取值映射"),

    /**
     * 缺失语义。用于澄清三级分流中「某列的空值是隐含阴性还是真实缺失」这类决策点
     * 的知识库检索。知识库只录语义明确的生理指标（该测未测），升压药 / 意识 /
     * 氧疗等需临床判断的列刻意不录，命中 0 条 → NO_EVIDENCE → 触发澄清。
     */
    MISSING_SEMANTICS("缺失语义"),
    ;

    private final String label;

    RuleType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 宽松解析：无法识别返回 null，不抛异常也不猜。调用方是 LLM 抽取出的字符串，
     * 不认识时按「没有可查的规则类型」走 NO_EVIDENCE 分支。
     */
    public static RuleType parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        for (RuleType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
