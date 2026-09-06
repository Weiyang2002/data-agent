package org.dataagent.clean.toolsclient.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱执行请求。代码契约：必须定义 {@code def clean(df, params)}，输入 DataFrame
 * 从参数进、输出从返回值出，代码不碰文件系统（读写 parquet 由沙箱宿主完成）。
 */
@Data
public class ExecuteRequest {

    private String taskId;

    private String code;

    /**
     * 临床参数注入通道。知识库查出的阈值放这里，在沙箱运行时才注入到
     * {@code clean(df, params)} 的第二个参数，不进代码生成 Prompt。Python 侧静态检查
     * 会验证「带了参数就必须引用 params」。
     */
    private Map<String, Object> params = new LinkedHashMap<>();

    private String inputPath;

    /** 为空时 Python 侧写到沙箱工作目录下 */
    private String outputPath;

    private Integer timeoutSeconds = 120;

    private Integer memoryLimitMb = 2048;

    /** 只做静态检查、不执行 */
    private Boolean dryRun = false;
}
