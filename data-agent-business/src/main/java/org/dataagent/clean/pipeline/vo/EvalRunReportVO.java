package org.dataagent.clean.pipeline.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一轮评测的完整报告。每个指标都带 numerator / denominator / note，指标是自解释的。
 * {@link #caveats} 列出本轮测不了的东西，最该先读。
 */
@Data
public class EvalRunReportVO {

    private String runCode;

    /** 本轮改了什么，指标序列靠它归因 */
    private String changeNote;

    private String modelName;

    private Integer datasetSeed;

    private Integer datasetRows;

    private Integer datasetPatients;

    private Integer caseTotal;

    private Integer casePassed;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long wallClockMillis;

    /** 指标表主体 */
    private List<MetricEntry> metrics = new ArrayList<>();

    /** 分层检出率，ROW / DISTRIBUTION / STRUCTURE / COMMON_SENSE 分开列 */
    private List<LevelStat> detectionByLevel = new ArrayList<>();

    /** 规范遵从率同样按层列。分布/结构/常识层天然无依据可引，合并统计是误导 */
    private List<LevelStat> complianceByLevel = new ArrayList<>();

    /**
     * Token 按阶段分布，{@code level} 位放的是 stageCode（复用 {@link LevelStat}：
     * hit=本阶段 Token，total=全轮 Token，rate=占比，note=口径）。
     */
    private List<LevelStat> tokenByStage = new ArrayList<>();

    /** 失败归因直方图，从最大的那一簇开始修 */
    private Map<String, Integer> failureHistogram = new LinkedHashMap<>();

    /** 本轮测不了的东西 */
    private List<String> caveats = new ArrayList<>();

    private List<CaseRow> cases = new ArrayList<>();

    /**
     * @param key         指标标识
     * @param label       中文名
     * @param value       比值；无法计算时为 null（不是 0）
     * @param numerator   分子
     * @param denominator 分母。为 0 表示本轮没有该指标的样本
     * @param note        口径说明
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetricEntry(String key, String label, Double value,
                              long numerator, long denominator, String note) {
    }

    /**
     * @param level 层级
     * @param hit   命中数
     * @param total 样本数
     * @param rate  命中率；total=0 时为 null
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LevelStat(String level, long hit, long total, Double rate, String note) {
    }

    /**
     * 显式 ALWAYS 覆盖全局的 non_null：这份报告里 null 本身是信息，「没测」和「测了
     * 得 0 分」必须长得不一样。
     */
    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class CaseRow {
        private String caseId;
        private String caseLevel;
        private String taskCode;
        private Boolean execAttempted;
        private Boolean execSuccess;
        private Boolean resultScorable;
        private Boolean resultCorrect;
        private Boolean clarifyExpected;
        private Boolean clarifyActual;
        private List<String> detected = new ArrayList<>();
        private List<String> missed = new ArrayList<>();
        private List<String> falseAlarm = new ArrayList<>();
        /** 检出但属于合成基线自带、非注入的信号，不计误报，见 DefectCodeMatcher */
        private List<String> baselineInherent = new ArrayList<>();
        private Integer repairAttempts;
        /** 因「修复版与已跑过的版本相同」提前终止的步骤数 */
        private Integer repairShortCircuited;
        private Long costMillis;
        private String failureCategory;
        private String failureDetail;
        /** 未能归一到缺陷码的常识级疑点条数，见 DefectCodeMatcher */
        private Integer unmatchedSuspicions;
    }
}
