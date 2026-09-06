package org.dataagent.clean.pipeline.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个任务的完整链路视图。{@link #stages} 是流水（依次发生了什么），
 * {@link #tokenByStage} 是账（钱花在哪一层），分开列。{@link #caveats} 写本次链路里
 * 测不准的东西。
 */
@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class TaskTraceVO {

    private String taskCode;

    private String traceId;

    private String status;

    private String requirement;

    private String failReason;

    private LocalDateTime createTime;

    /** 端到端耗时：只求和 depth = 0 的阶段，子阶段耗时已含在父阶段里 */
    private Long totalCostMillis;

    private Long totalTokens;

    private Long promptTokens;

    private Long completionTokens;

    /** 模型调用次数，不是沙箱执行次数 */
    private Integer modelCalls;

    /** 模型没返回 usage 的调用数，大于 0 时上面的 Token 是下界，并在 {@link #caveats} 出现一条。 */
    private Integer usageMissingCalls;

    private List<StageRow> stages = new ArrayList<>();

    private List<StageCost> tokenByStage = new ArrayList<>();

    /** 本次链路测不准的东西 */
    private List<String> caveats = new ArrayList<>();

    /** 链路流水的一行 */
    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class StageRow {
        private String stageCode;
        private String label;
        private Integer stageOrder;
        /** 0 = 顶层；1 = 嵌在上一个顶层阶段里 */
        private Integer depth;
        /** SUCCESS / FAILED / SKIPPED */
        private String state;
        private String summary;
        private String errorMsg;
        private Long costMillis;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    /** 按阶段收口的账。total 之和 = 任务总量，不会重复计算 */
    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class StageCost {
        private String stageCode;
        private String label;
        private Integer depth;
        private Integer enterCount;
        private Integer modelCalls;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private Integer usageMissingCalls;
        /** 本阶段自身耗时，已扣除子阶段 */
        private Long selfCostMillis;
        /** 含子阶段的墙钟耗时 */
        private Long totalCostMillis;
        /** 本阶段 Token 占任务总量的比例；总量为 0 时为 null */
        private Double tokenShare;
    }
}
