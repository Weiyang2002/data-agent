package org.dataagent.clean.toolsclient.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Python 工具服务 /health 响应。
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)}：Python 侧新增 health 字段
 * 时，Java 侧不因未知字段反序列化失败。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolsHealthResponse {

    private String status;

    private String service;

    private String version;

    /** 沙箱是否可用（Python 侧自检结果） */
    private Boolean sandboxReady;
}
