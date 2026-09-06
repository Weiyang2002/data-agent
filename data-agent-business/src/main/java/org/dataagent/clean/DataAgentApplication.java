package org.dataagent.clean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 临床研究数据处理 Agent 主服务入口。scanBasePackages 显式列出 org.dataagent.common，
 * 否则 common 模块里的 GlobalExceptionHandler 不会被扫描到。
 */
@SpringBootApplication(scanBasePackages = {"org.dataagent.clean", "org.dataagent.common"})
@MapperScan("org.dataagent.clean.pipeline.mapper")
public class DataAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataAgentApplication.class, args);
    }
}
