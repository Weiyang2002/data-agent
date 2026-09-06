package org.dataagent.clean.toolsclient.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级校验请求。阈值全部在这里传给 Python（Java 先查 {@code data_agent_knowledge_rule}
 * 拿到 {@code {min, max}} 连同 ruleId 与来源文档），Python 只负责比对。
 */
@Data
public class ValidateRowRequest {

    private String datasetPath;

    /** 至少一条，传空会被 Python 侧拒绝（避免把「没查」当成「没问题」）。 */
    private List<ValidityRule> rules = new ArrayList<>();

    private Integer maxSamplesPerRule = 20;

    @Data
    public static class ValidityRule {
        /** 知识库规则主键，回填到校验发现上做引用标注 */
        private String ruleId;
        private String column;
        private Double min;
        private Double max;
        /** 文本列的合法取值集合；与 min/max 互斥 */
        private List<String> allowedValues;
        private String unit;
        private String sourceDoc;
        private String sourceLocator;
    }
}
