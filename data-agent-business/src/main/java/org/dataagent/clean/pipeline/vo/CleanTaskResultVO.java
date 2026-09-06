package org.dataagent.clean.pipeline.vo;

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
        private Boolean executed;
        private Boolean success;
        private Integer repairCount;
        /** 未执行的原因，如「需要临床依据但知识库无命中，已转为澄清项」 */
        private String skipReason;
    }

    @Data
    public static class ClarificationView {
        private String clarifyCode;
        private String level;
        private String topic;
        private String question;
        private List<String> options = new ArrayList<>();
        private String columnName;
        private Double coverageRatio;
        private String evidence;
        private List<String> conflictingSources = new ArrayList<>();
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
