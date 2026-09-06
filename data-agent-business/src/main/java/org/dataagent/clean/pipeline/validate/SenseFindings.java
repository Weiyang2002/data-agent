package org.dataagent.clean.pipeline.validate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 常识级校验的模型输出。字段刻意做得窄：模型只能说「哪列、什么现象、为什么临床上
 * 不合理」，不能填 affectedRows（算不准）和 sourceRuleId（无依据可引）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SenseFindings {

    private List<SenseFinding> findings = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SenseFinding {
        /** 涉及的列或指标名；跨列问题可填派生指标名，如 admissionsPerPatientAfter */
        private String subject;
        /** 现象描述：哪个数字不对 */
        private String observation;
        /** 为什么临床上不该是这样 */
        private String reasoning;
        /** LOW / MEDIUM / HIGH */
        private String suspicionLevel;
    }
}
