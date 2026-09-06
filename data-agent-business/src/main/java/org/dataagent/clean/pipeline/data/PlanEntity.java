package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 处理方案（PlannerAgent 产出）。 */
@Data
@TableName("data_agent_plan")
public class PlanEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planCode;

    private String taskCode;

    private String summary;

    /** DRAFT/CLARIFYING/CONFIRMED/EXECUTED/FAILED */
    private String status;

    private Integer stepCount;

    private Integer clarifyCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
