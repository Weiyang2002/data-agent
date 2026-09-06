package org.dataagent.clean.toolsclient.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Python 工具服务连接配置。端点超时按耗时分级：health 存活探测（秒级）、profile
 * 全量画像（分钟级）、execute 沙箱执行（读超时须大于请求体里的 timeoutSeconds）、
 * validate 确定性统计（秒级）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.data-tools")
public class DataToolsProperties {

    private String baseUrl = "http://127.0.0.1:18081";

    private int connectTimeoutMs = 3000;

    /** 通用读超时，用于 health 等轻量端点 */
    private int readTimeoutMs = 10000;

    /** 数据画像：全量数据统计可能数分钟 */
    private int profileReadTimeoutMs = 600000;

    /** 沙箱执行：必须大于请求体里的 timeoutSeconds，留足回传余量 */
    private int executeReadTimeoutMs = 300000;

    /** 确定性校验 */
    private int validateReadTimeoutMs = 120000;

    /** 评测数据生成 */
    private int datasetReadTimeoutMs = 300000;
}
