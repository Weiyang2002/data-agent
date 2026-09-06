package org.dataagent.clean.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动自检。配置项给了空默认值能让服务启动，但降级不能静默：每一项降级都在启动
 * 日志里说清楚「什么不可用、后果是什么、怎么修」。
 */
@Component
public class StartupCheckRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCheckRunner.class);

    /** 与 application.yaml 中的占位符默认值保持一致 */
    private static final String API_KEY_PLACEHOLDER = "PLACEHOLDER-SET-DATA_AGENT_MODEL_API_KEY";

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:}")
    private String modelBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String chatModel;

    @Value("${app.clean-agent.checkpoint-enabled:true}")
    private boolean checkpointEnabled;

    @Value("${app.data-tools.base-url:}")
    private String toolsBaseUrl;

    @Override
    public void run(ApplicationArguments args) {
        log.info("──── data-agent 启动自检 ────");

        if (!StringUtils.hasText(apiKey) || API_KEY_PLACEHOLDER.equals(apiKey)) {
            log.warn("未配置模型密钥（当前是占位符），所有大模型调用会失败");
            log.warn("  修复：在 application-local.yaml 填 spring.ai.openai.api-key，");
            log.warn("        或设置环境变量 DATA_AGENT_MODEL_API_KEY。");
        }
        else {
            // 只打前 6 位，日志里不留完整密钥
            log.info("模型已配置: {} @ {} (key={}***)",
                chatModel, modelBaseUrl,
                apiKey.length() > 6 ? apiKey.substring(0, 6) : "***");
        }

        if (!checkpointEnabled) {
            log.warn("checkpoint 已关闭，澄清中断后无法恢复，仅适用于本地调试");
        }
        else {
            log.info("checkpoint 已启用，澄清中断可恢复");
        }

        log.info("Python 工具服务地址: {}", toolsBaseUrl);
        log.info("  验证链路: curl http://localhost:9090/api/smoke/all");
        log.info("────────────────────────────");
    }
}
