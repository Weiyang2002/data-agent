package org.dataagent.clean.pipeline.validate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.model.profile.ProfileDiff;
import org.dataagent.clean.pipeline.model.validate.FindingLevel;
import org.dataagent.clean.pipeline.model.validate.ValidationFinding;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.pipeline.support.DataBoundaryRenderer;
import org.dataagent.clean.prompt.PromptTemplateNames;
import org.dataagent.clean.prompt.PromptTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 第 3 层：常识级校验，链路上 LLM 唯一不可替代的位置。前两层通过、代码不报错、
 * 统计正常的问题（如列错位导致人均入院次数塌成 1.00）只有临床常识能发现。
 *
 * <p>输入是 {@link ProfileDiff}：统计量和代码算好的派生指标，无原始数据。温度
 * 0.5，允许发散地怀疑；发现是 WARN 而非 ERROR，不阻断流程。
 */
@Component
public class CommonSenseValidator {

    private static final Logger log = LoggerFactory.getLogger(CommonSenseValidator.class);

    /** 常识级校验需要发散地怀疑，温度取 0.5 */
    private static final double SENSE_TEMPERATURE = 0.5;

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final DataBoundaryRenderer boundaryRenderer;
    private final ObjectMapper objectMapper;

    public CommonSenseValidator(ChatClient.Builder chatClientBuilder,
                                PromptTemplateService promptTemplateService,
                                DataBoundaryRenderer boundaryRenderer,
                                ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
        this.boundaryRenderer = boundaryRenderer;
        this.objectMapper = objectMapper;
    }

    @TraceStage(TraceStageCode.VALIDATE_SENSE)
    public void validate(ProfileDiff diff, ValidationReport report) {
        BeanOutputConverter<SenseFindings> converter = new BeanOutputConverter<>(SenseFindings.class);
        try {
            String prompt = promptTemplateService.render(
                PromptTemplateNames.VALIDATOR_SENSE,
                Map.of(
                    "diffBlock", boundaryRenderer.wrapEscaped(toJson(diff)),
                    "knownFindings", renderKnown(report),
                    "outputFormat", converter.getFormat()
                ));

            SenseFindings result = converter.convert(chatClient.prompt()
                .options(ChatOptions.builder().temperature(SENSE_TEMPERATURE).build())
                .user(prompt)
                .call()
                .content());

            report.markExecuted(FindingLevel.COMMON_SENSE);
            for (SenseFindings.SenseFinding item : result.getFindings()) {
                ValidationFinding finding = ValidationFinding.of(
                    FindingLevel.COMMON_SENSE,
                    "COMMON_SENSE_SUSPICION",
                    item.getSubject(),
                    item.getObservation() + " —— " + item.getReasoning(),
                    0L);
                // 常识级发现没有规范依据可引，sourceRuleId 保持 null（正确，非遗漏）
                finding.setSeverity("WARN");
                report.add(finding);
            }
            log.info("常识级校验完成：{} 项疑点", result.getFindings().size());
        }
        catch (Exception exception) {
            // 显式标记未执行，不留空的 findings 让人误以为「常识级通过了」
            String reason = "常识级校验调用失败：" + exception.getMessage();
            log.warn("{}。本层未产出任何结论，报告中已标记为未执行", reason);
            report.markSkipped(FindingLevel.COMMON_SENSE, reason);
        }
    }

    /** 把已有发现压缩成一行一条，避免模型重复报告同一个问题 */
    private String renderKnown(ValidationReport report) {
        List<String> lines = report.getFindings().stream()
            .map(item -> "- [%s] %s %s".formatted(
                item.getLevel().getLabel(), item.getColumnName(), item.getEvidence()))
            .collect(Collectors.toList());
        return lines.isEmpty() ? "（确定性校验未发现问题）" : String.join("\n", lines);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("画像 diff 序列化失败", exception);
        }
    }
}
