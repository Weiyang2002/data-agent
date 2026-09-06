package org.dataagent.clean.pipeline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 行为参数，全部走配置而非硬编码，便于调参观察指标变化。 */
@Data
@ConfigurationProperties(prefix = "app.clean-agent")
public class CleanAgentProperties {

    /** 单次执行中，代码生成失败后最多自修复几次。超过则降级为向用户报告 */
    private int maxRepairAttempts = 3;

    /** 单个任务允许的最大模型调用次数，防止 Agent 陷入死循环烧 Token */
    private int maxModelCallsPerRun = 20;

    /** 单个会话线程累计模型调用上限 */
    private int maxModelCallsPerThread = 60;

    /** 单个任务允许的最大工具调用次数 */
    private int maxToolCallsPerRun = 15;

    /** 沙箱单次执行超时（秒），会作为参数传给 Python /execute */
    private int sandboxTimeoutSeconds = 120;

    /** 沙箱内存上限（MB） */
    private int sandboxMemoryLimitMb = 2048;

    /** 数据画像中文本列取值枚举上限，控制注入 Prompt 的上下文体积 */
    private int textValueTopN = 50;

    /** 是否启用 checkpoint 持久化。关掉后澄清中断无法恢复，仅用于本地无 MySQL 时调试 */
    private boolean checkpointEnabled = true;
}
