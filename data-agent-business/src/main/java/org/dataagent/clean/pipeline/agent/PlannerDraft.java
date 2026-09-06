package org.dataagent.clean.pipeline.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 规划的原始输出，区别于 {@code CleaningPlan}。
 *
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlannerDraft {

    private String summary;

    private List<DraftStep> steps = new ArrayList<>();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DraftStep {
        /** 动作枚举名，无法识别时解析为 UNKNOWN 并跳过 */
        private String action;
        /** 模型给的列名，可能是别名，未对齐 */
        private List<String> targetColumns = new ArrayList<>();
        private String description;
    }
}
