package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 列画像快照。
 *
 * <p>落库而非只放内存：常识级校验比对处理前后画像，而处理可能跨越一次澄清中断，
 * 中断恢复后内存中的对照基准已丢失。
 */
@Data
@TableName("data_agent_column_profile")
public class ColumnProfileEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskCode;

    private String datasetCode;

    /** BEFORE / AFTER */
    private String phase;

    private String columnName;

    private String dtype;

    private Double missingRate;

    private Long distinctCount;

    private String numericJson;

    /** 低基数列的取值枚举；高基数列为 null —— 标识列永远不会落到这里 */
    private String textValuesJson;

    private LocalDateTime createTime;
}
