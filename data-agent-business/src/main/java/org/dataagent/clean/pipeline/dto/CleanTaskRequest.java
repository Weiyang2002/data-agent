package org.dataagent.clean.pipeline.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 发起一次数据处理任务。 */
@Data
public class CleanTaskRequest {

    /**
     * 医生的自然语言需求原文，不做预处理或规范化。理解口语化、不精确的需求
     * （如「体温有明显不合理的值，帮我处理一下」）是系统本身的职责。
     */
    @NotBlank(message = "需求不能为空")
    private String requirement;

    /** 待处理的 parquet 路径。M2 用评测数据集，Phase 1 再做上传 */
    @NotBlank(message = "数据集路径不能为空")
    private String datasetPath;
}
