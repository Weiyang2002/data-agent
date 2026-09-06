package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 阶段链路 trace。写入由 {@code @TraceStage} 注解 + {@code TraceStageAspect} 承担，
 * 业务方法体内无埋点代码。
 */
@Data
@TableName("data_agent_task_stage")
public class TaskStageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskCode;

    private String traceId;

    /** PROFILE/KNOWLEDGE/PLAN/CLARIFY/CODEGEN/SANDBOX/VALIDATE_* */
    private String stageCode;

    /** 阶段序号，按它排出完整链路 */
    private Integer stageOrder;

    /**
     * 嵌套深度，0 = 顶层。端到端延迟只求和 {@code depth = 0} 的阶段，否则子阶段耗时
     * 已含在父阶段内会让延迟翻倍。
     */
    private Integer depth;

    /** RUNNING/SUCCESS/FAILED/SKIPPED */
    private String state;

    private String summary;

    private String detailJson;

    private String errorMsg;

    private Long costMillis;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
