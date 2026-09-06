package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处理任务主表。 */
@Data
@TableName("data_agent_task")
public class TaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对外暴露的业务任务号，不暴露自增 ID */
    private String taskCode;

    /** 贯穿 Java 与 Python 的链路 ID */
    private String traceId;

    /** 医生输入的自然语言需求原文 */
    private String requirement;

    private String datasetPath;

    /** CREATED/PROFILING/CLARIFYING/EXECUTING/VALIDATING/DONE/FAILED */
    private String status;

    private String threadId;

    private String failReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
