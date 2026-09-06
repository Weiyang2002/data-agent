package org.dataagent.clean.pipeline.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通过 {@code ChatClientCustomizer} 把 {@link StageTokenAdvisor} 挂到所有
 * {@code ChatClient} 上，新加的 Agent 自动被覆盖。依赖的是
 * {@code org.springframework.ai.chat.client}（模型调用门面），不在框架隔离范围内。
 */
@Configuration
public class ObservabilityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfiguration.class);

    @Bean
    public StageTokenAdvisor stageTokenAdvisor() {
        return new StageTokenAdvisor();
    }

    @Bean
    public ChatClientCustomizer stageTokenChatClientCustomizer(StageTokenAdvisor advisor) {
        log.info("Token 采集已挂载：所有 ChatClient 的调用都会按阶段归集用量");
        return builder -> builder.defaultAdvisors(advisor);
    }
}
