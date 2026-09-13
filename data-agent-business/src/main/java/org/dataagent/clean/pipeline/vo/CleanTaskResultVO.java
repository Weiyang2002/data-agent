package org.dataagent.clean.pipeline.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次处理任务的完整结果。结构上把「做了什么」「问什么」「发现什么」「哪里没做」
 * 分开。
 */
@Data
public class CleanTaskResultVO {

    private String taskCode;

    private String traceId;

    /** DONE / CLARIFYING / FAILED */
    private String status;

    private String failReason;

    // ── 画像 ──
    private Long inputRowCount;
    private Integer columnCount;
    /** LLM 对确定性检出结果的自然语言归纳，归纳失败时为空串 */
    private String profileNarrative;
    private List<AnomalyView> profileAnomalies = new ArrayList<>();

    // ── 方案 ──
    private String planCode;
    private String planSummary;
    private List<StepView> steps = new ArrayList<>();

    // ── 澄清 ──
    /** 按 coverageRatio 降序：医生从上往下答，中途停止也已覆盖绝大部分数据 */
    private List<ClarificationView> clarifications = new ArrayList<>();

    // ── 执行 ──
    private String outputPath;
    private Long outputRowCount;
    private Integer totalRepairAttempts;

    /** 因「修复版与已跑过的版本相同」而提前终止的步骤数。 */
    private Integer repairShortCircuitCount;

    /** 本轮澄清答复的归一结果，含被驳回的答复及原因 */
    private List<ClarifyOutcomeView> clarifyOutcomes = new ArrayList<>();

    // ── 校验 ──
    private List<FindingView> findings = new ArrayList<>();
    /**
     * 各层是否真的执行过。必须暴露给调用方：空的 findings 列表可能是「查过没问题」，
     * 也可能是「压根没查」。
     */
    private Map<String, Boolean> validationExecuted = new LinkedHashMap<>();
    private Map<String, String> validationSkipReason = new LinkedHashMap<>();

    @Data
    public static class AnomalyView {
        private String code;
        private String column;
        private String level;
        private String evidence;
        private Long affectedRows;
    }

    @Data
    public static class StepView {
        private Integer stepNo;
        private String action;
        private String description;
        private List<String> targetColumns = new ArrayList<>();
        /** 该步骤的临床依据，空表示无依据（对纯工程动作正常） */
        private List<Long> ruleIds = new ArrayList<>();
        /** 依据来自医生答复而非院内规范。两者不能混为一谈 */
        private Boolean userDecided;
        private Boolean executed;
        private Boolean success;
        private Integer repairCount;
        /** 未执行的原因，如「需要临床依据但知识库无命中，已转为澄清项」 */
        private String skipReason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ClarificationView {
        private String clarifyCode;
        private String level;
        private String topic;
        private String question;
        private List<String> options = new ArrayList<>();
        /** 与 options 逐位对应的机器码，答复回传时带这个最稳 */
        private List<String> optionCodes = new ArrayList<>();
        private String columnName;
        private Double coverageRatio;
        private String evidence;
        private List<String> conflictingSources = new ArrayList<>();
        private String answer;
        /** 答复归一后的机器码；有答复但为空表示这条答复系统用不上 */
        private String answerAction;
    }

    /**
     * 全局 default-property-inclusion 是 non_null，而这里的 null 本身是结论
     * （answerAction 为空 = 这条答复系统用不上），不显式声明会在响应体里凭空消失。
     */
    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ClarifyOutcomeView {
        private String clarifyCode;
        private String topic;
        private String answer;
        /** 归一后的机器码；为空说明这条答复没能变成执行动作 */
        private String answerAction;
        private Boolean accepted;
        /** 未被采纳的原因，如「答复对不上任何候选项」 */
        private String rejectReason;
        /** 依据来自知识库还是医生自决 */
        private Boolean knowledgeBacked;
    }

    @Data
    public static class FindingView {
        private String level;
        private String code;
        private String column;
        private String severity;
        private String evidence;
        private Long affectedRows;
        /** 引用标注：医生据此核对「系统凭什么这么判」 */
        private Long sourceRuleId;
        private String sourceDoc;
        private String sourceLocator;
    }
}
