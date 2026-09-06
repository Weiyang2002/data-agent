package org.dataagent.clean.pipeline.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 数据集登记。同一份 parquet 可被多个任务引用，所以单独成表。 */
@Data
@TableName("data_agent_dataset")
public class DatasetEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String datasetCode;

    private String datasetPath;

    /** 评测数据才有 */
    private String goldenPath;

    private Long rowCount;

    private Integer columnCount;

    /** SYNTHETIC / UPLOAD */
    private String source;

    private Long profileMillis;

    private LocalDateTime createTime;
}
