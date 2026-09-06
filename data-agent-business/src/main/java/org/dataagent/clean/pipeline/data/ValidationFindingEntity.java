package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 三层校验发现。{@code level} 是分层检出率的直接来源，{@code sourceRuleId} 是规范
 * 遵从率的直接来源，各是一条 SQL。
 */
@Data
@TableName("data_agent_validation_finding")
public class ValidationFindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskCode;

    /** BEFORE 画像阶段发现 / AFTER 处理后校验发现 */
    private String phase;

    /** ROW / DISTRIBUTION / STRUCTURE / COMMON_SENSE / CLARIFY */
    private String level;

    /** 与 defects.py 缺陷码同命名空间，可直接比对 golden */
    private String code;

    private String columnName;

    private String severity;

    private String evidence;

    private Long affectedRows;

    private Double metric;

    /** 依据的知识库规则；空表示无规范依据 */
    private String sourceRuleId;

    private String sourceDoc;

    private String sourceLocator;

    /** 违规样例。仅入库与报告，不进 Prompt */
    private String sampleJson;

    private LocalDateTime createTime;
}
