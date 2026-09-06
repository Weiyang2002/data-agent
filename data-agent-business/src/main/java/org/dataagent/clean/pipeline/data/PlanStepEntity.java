package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 方案步骤。 */
@Data
@TableName("data_agent_plan_step")
public class PlanStepEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String planCode;

    private Integer stepNo;

    private String action;

    private String targetColumns;

    private String description;

    /**
     * 该步骤引用的知识库规则 ID（JSON 数组）。
     *
     */
    private String ruleIds;

    /** 为 DAG 化预留，M2 顺序执行，恒为空 */
    private String dependsOn;

    private LocalDateTime createTime;
}
