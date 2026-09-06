package org.dataagent.clean.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt 模板启动自检。模板语法错误是纯静态问题，启动时把每个模板都渲染一遍即可
 * 发现，不必等到第一次请求。
 *
 * <p>{@code ValidationMode.THROW} 要求模板里每个变量都有值，这里喂所有变量名的并集
 * （多余的变量 StringTemplate 不介意），代价是新增模板变量时要在这里补一行。
 */
@Component
@Order(10)
public class PromptTemplateSelfCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateSelfCheck.class);

    /** 全部模板变量名的并集，新增模板变量时在这里补一行 */
    private static final List<String> ALL_VARIABLES = List.of(
        "columnName", "ruleType",
        "profileBlock", "requirement", "outputFormat",
        "clarificationsBlock",
        "stepDescription", "targetColumns", "paramKeys", "schemaBlock",
        "previousCode", "failureText",
        "diffBlock", "knownFindings"
    );

    private static final List<String> TEMPLATES = List.of(
        PromptTemplateNames.SMOKE_TEST,
        PromptTemplateNames.PROFILER_ANOMALY,
        PromptTemplateNames.PLANNER_SYSTEM,
        PromptTemplateNames.PLANNER_USER,
        PromptTemplateNames.CLARIFICATION_GENERATE,
        PromptTemplateNames.CODEGEN_SYSTEM,
        PromptTemplateNames.CODEGEN_USER,
        PromptTemplateNames.CODEGEN_REPAIR,
        PromptTemplateNames.VALIDATOR_SENSE
    );

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateSelfCheck(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> probe = new LinkedHashMap<>();
        ALL_VARIABLES.forEach(name -> probe.put(name, "__probe__"));

        for (String template : TEMPLATES) {
            try {
                promptTemplateService.render(template, probe);
            }
            catch (Exception exception) {
                // 直接抛出让服务起不来，避免把必然发生的失败推迟到第一个真实任务
                log.error("Prompt 模板 [{}] 渲染失败：{}", template, exception.getMessage());
                log.error("  常见原因：模板正文出现裸的尖括号字面量（含 HTML/XML 风格的闭合标签），"
                    + "会被 StringTemplate 当成表达式解析。"
                    + "本项目约定：模板里的尖括号只用于业务变量。");
                throw new IllegalStateException("Prompt 模板自检未通过: " + template, exception);
            }
        }
        log.info("Prompt 模板自检通过，共 {} 个模板", TEMPLATES.size());
    }
}
