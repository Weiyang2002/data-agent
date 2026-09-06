package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 行级校验响应。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ValidateRowResponse {

    private String datasetPath;

    private Long totalRows;

    /** 实际参与比对的规则数。与 findings 为空一起看才能区分「查过没问题」和「压根没查」 */
    private Integer checkedRules;

    private List<RowFinding> findings = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RowFinding {
        private String ruleId;
        private String column;
        private String level;
        private Long violationCount;
        private Double violationRatio;
        /** 参与比对的非空行数。缺失行不计入违规（「该测未测」与「测出不可能的值」是两类问题） */
        private Long checkedCount;
        private List<Long> sampleRowIds = new ArrayList<>();
        /**
         * 违规样例值。
         *
         * <p>只入库和进报告，不进 Prompt。常识级校验的输入是处理前后的统计量 diff，
         * 不接触具体取值。
         */
        private List<String> sampleValues = new ArrayList<>();
        private Double expectedMin;
        private Double expectedMax;
        private String sourceDoc;
        private String sourceLocator;
        private String evidence;
    }
}
