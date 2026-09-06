package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一轮评测的指标快照。{@link #changeNote} 是本表唯一拒绝为空的业务字段：没有
 * 「这一轮改了什么」，指标序列就只是一串无法归因的数字。
 */
@Data
@TableName("data_agent_eval_run")
// 局部覆盖全局的 non_null：指标序列里字段消失（本轮没有样本）和字段为 0（得了 0 分）
// 意味着完全不同的事
@JsonInclude(JsonInclude.Include.ALWAYS)
public class EvalRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runCode;

    /** 同 seed 必须产出完全相同的数据与 golden，这是跨轮次比较的前提 */
    private Integer datasetSeed;

    private String datasetPath;

    private String goldenPath;

    /** 本轮使用的模型，换模型也是一种改动，需记录 */
    private String modelName;

    /** 本轮改了什么，空则拒绝发起评测 */
    private String changeNote;

    private Integer caseTotal;

    private Integer casePassed;

    // ── 指标快照 ──

    private Double execSuccessRate;

    private Double resultCorrectRate;

    private Double detectRateRow;

    private Double detectRateDist;

    /** 结构级检出率，由 migration 补齐，四层必须分开列 */
    private Double detectRateStructure;

    private Double detectRateSense;

    /** 待澄清级检出率（缺失语义类），由 migration 补齐 */
    private Double detectRateClarify;

    /** 规范遵从率，只在行级有意义（其余层天然无依据可引） */
    private Double specComplianceRate;

    /** 澄清恰当率，双向计分：该问的问了 × 不该问的没问 */
    private Double clarifyPrecision;

    private Double discoveryRate;

    private Double selfRepairRate;

    /** Token 总量，未采集时为 null（不填估算值） */
    private Long tokenTotal;

    private Long latencyP50Millis;

    private Long latencyP95Millis;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
