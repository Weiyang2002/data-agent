package org.dataagent.clean.prompt;

import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板渲染组件。三个设计点：模板外置成 classpath:prompt/*.st 文件；定界符
 * 改成 {@code < >}（默认的 {@code { }} 会与 Prompt 里的 JSON 示例冲突）；
 * {@code ValidationMode.THROW}，变量缺失直接抛异常而非渲染成空串。
 */
@Component
public class PromptTemplateService {

    private static final String PROMPT_DIR = "prompt/";
    private static final String TEMPLATE_SUFFIX = ".st";

    private final ResourceLoader resourceLoader;
    private final StTemplateRenderer templateRenderer;

    /** 模板内容缓存：文件只读一次 */
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.templateRenderer = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .validationMode(ValidationMode.THROW)
            .build();
    }

    public String render(String templateName, Map<String, ?> variables) {
        String templatePath = normalizeTemplatePath(templateName);
        String template = templateCache.computeIfAbsent(templatePath, this::loadTemplate);
        String rendered = templateRenderer.apply(template, normalizeVariables(variables));
        return normalizeLineSeparator(rendered).trim();
    }

    /**
     * 换行归一化为 \n，必须放在渲染之后：StringTemplate 在 Windows 上会按平台换行符
     * 把每个换行输出成 \r\n，多送的 \r 是无谓的 Token 开销。
     */
    private String normalizeLineSeparator(String text) {
        if (text == null || text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** null 值转空串：StringTemplate 遇到 null 会渲染成字面量 "null" */
    private Map<String, Object> normalizeVariables(Map<String, ?> variables) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty()) {
            return normalized;
        }
        variables.forEach((key, value) -> normalized.put(key, value == null ? "" : value));
        return normalized;
    }

    /** 允许调用方写 "planner-system"、"prompt/planner-system.st"、"classpath:prompt/planner-system.st" */
    private String normalizeTemplatePath(String templateName) {
        String normalized = templateName == null ? "" : templateName.trim();
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith(PROMPT_DIR)) {
            normalized = PROMPT_DIR + normalized;
        }
        if (!normalized.endsWith(TEMPLATE_SUFFIX)) {
            normalized = normalized + TEMPLATE_SUFFIX;
        }
        return normalized;
    }

    private String loadTemplate(String templatePath) {
        Resource resource = resourceLoader.getResource("classpath:" + templatePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Prompt 模板不存在: classpath:" + templatePath);
        }
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
        catch (Exception exception) {
            throw new IllegalStateException("读取 Prompt 模板失败: classpath:" + templatePath, exception);
        }
    }
}
