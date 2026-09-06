package org.dataagent.clean.pipeline.model.validate;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 一条校验发现。 */
@Data
public class ValidationFinding {

    private FindingLevel level;

    /** 缺陷码，与 {@code defects.py} 共用命名空间，评测直接与 golden 比对。 */
    private String code;

    private String columnName;

    private String severity = "WARN";

    /** 人类可读的证据，给医生看，要能独立成句 */
    private String evidence;

    private long affectedRows;

    private Double metric;

    /**
     * 依据的知识库规则 ID，「规范遵从率」的直接来源。空表示无规范依据：对分布级
     * 发现正常，对行级越界发现则意味着阈值来路不明。
     */
    private Long sourceRuleId;

    private String sourceDoc;

    private String sourceLocator;

    /** 违规样例，只入库和进报告，不进 Prompt */
    private List<String> samples = new ArrayList<>();

    public static ValidationFinding of(FindingLevel level, String code,
                                       String column, String evidence, long affectedRows) {
        ValidationFinding finding = new ValidationFinding();
        finding.setLevel(level);
        finding.setCode(code);
        finding.setColumnName(column);
        finding.setEvidence(evidence);
        finding.setAffectedRows(affectedRows);
        return finding;
    }
}
