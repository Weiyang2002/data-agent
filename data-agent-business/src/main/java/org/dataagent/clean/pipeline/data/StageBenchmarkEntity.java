package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阶段耗时 / Token 账本。与 {@link TaskStageEntity} 的分工：{@code task_stage} 是流水
 * （一个阶段进入几次就有几行），本表是账（一个任务一个阶段只有一行）。
 * {@link #totalTokens} 记的是本阶段自身的消耗（不含子阶段），故 {@code SUM(total_tokens)}
 * 就是任务总量。
 */
@Data
@TableName("data_agent_stage_benchmark")
public class StageBenchmarkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskCode;

    private String traceId;

    private String stageCode;

    private Integer stageOrder;

    /** 嵌套深度，0 = 顶层 */
    private Integer depth;

    /** 本任务内该阶段被进入的次数 */
    private Integer enterCount;

    /** 模型调用次数（不是沙箱执行次数）。被短路掉的代码模型调用已经发生过。 */
    private Integer modelCalls;

    private Long promptTokens;

    private Long completionTokens;

    /** 本阶段自身消耗，不含子阶段 */
    private Long totalTokens;

    /** 模型没返回 usage 元数据的调用数，大于 0 说明该阶段的 Token 是下界。 */
    private Integer usageMissingCalls;

    /** 本阶段自身耗时，已扣除子阶段 */
    private Long selfCostMillis;

    /** 含子阶段的墙钟耗时 */
    private Long totalCostMillis;

    private LocalDateTime createTime;
}
