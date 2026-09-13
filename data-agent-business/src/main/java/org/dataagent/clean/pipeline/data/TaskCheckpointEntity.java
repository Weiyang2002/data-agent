package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 澄清中断的业务检查点，一个任务一行。 */
@Data
@TableName("data_agent_task_checkpoint")
public class TaskCheckpointEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskCode;

    private String traceId;

    /** 在哪个阶段中断，当前只有 CLARIFYING */
    private String stage;

    /** 恢复所需的全部状态：画像 + 方案 + 已执行到哪一步 */
    private String payloadJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
