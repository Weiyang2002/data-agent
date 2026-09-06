package org.dataagent.clean.pipeline.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Agent 装配配置。
 *
 */
@Configuration
@EnableConfigurationProperties(CleanAgentProperties.class)
public class CleanAgentConfiguration {

    /**
     * Graph 检查点存储，澄清中断恢复的物理基础。{@code CREATE_IF_NOT_EXISTS} 会自动
     * 建 GRAPH_THREAD / GRAPH_CHECKPOINT 两张表。{@code @ConditionalOnProperty} 让本地
     * 没起 MySQL 时也能启动做纯 Prompt 调试（此时中断恢复不可用，属显式降级）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.clean-agent", name = "checkpoint-enabled",
        havingValue = "true", matchIfMissing = true)
    public MysqlSaver mysqlCheckpointSaver(DataSource dataSource) {
        return MysqlSaver.builder()
            .dataSource(dataSource)
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .build();
    }
}
