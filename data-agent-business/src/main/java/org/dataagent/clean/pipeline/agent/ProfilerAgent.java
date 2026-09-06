package org.dataagent.clean.pipeline.agent;

import org.dataagent.clean.pipeline.config.CleanAgentProperties;
import org.dataagent.clean.pipeline.model.profile.ProfileResult;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.pipeline.support.DataBoundaryRenderer;
import org.dataagent.clean.prompt.PromptTemplateNames;
import org.dataagent.clean.prompt.PromptTemplateService;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.ProfileRequest;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent 1：数据画像。
 *
 * <p>分工：Python 做全部统计和异常模式检出（确定性、可复现、零 Token）；
 * LLM 只把检出结果写成一段自然语言描述，一次调用。LLM 不发现问题，只表达问题。
 *
 * <p>归纳失败时降级为空字符串并打 WARN 日志（不静默），画像事实本身已完整，
 * 不影响后续环节。
 */
@Component
public class ProfilerAgent implements DataAgent<String, ProfileResult> {

    private static final Logger log = LoggerFactory.getLogger(ProfilerAgent.class);

    private final DataToolsClient dataToolsClient;
    private final PromptTemplateService promptTemplateService;
    private final DataBoundaryRenderer boundaryRenderer;
    private final ChatClient chatClient;
    private final CleanAgentProperties properties;

    public ProfilerAgent(DataToolsClient dataToolsClient,
                         PromptTemplateService promptTemplateService,
                         DataBoundaryRenderer boundaryRenderer,
                         ChatClient.Builder chatClientBuilder,
                         CleanAgentProperties properties) {
        this.dataToolsClient = dataToolsClient;
        this.promptTemplateService = promptTemplateService;
        this.boundaryRenderer = boundaryRenderer;
        this.chatClient = chatClientBuilder.build();
        this.properties = properties;
    }

    @Override
    public AgentCode code() {
        return AgentCode.PROFILER;
    }

    @Override
    @TraceStage(TraceStageCode.PROFILE)
    public ProfileResult run(AgentContext context, String datasetPath) {
        ProfileResponse profile = dataToolsClient.profile(
            context.getTraceId(),
            ProfileRequest.of(datasetPath, properties.getTextValueTopN()));

        log.info("画像完成 taskCode={} rows={} 异常模式={} 主键候选={}",
            context.getTaskCode(), profile.getRowCount(),
            profile.getAnomalyPatterns().size(), profile.getKeyCandidates().size());

        return new ProfileResult(profile, summarize(context, profile));
    }

    private String summarize(AgentContext context, ProfileResponse profile) {
        if (profile.getAnomalyPatterns().isEmpty()) {
            // 无异常则无需调用模型
            return "确定性检查未发现异常模式。";
        }
        try {
            String prompt = promptTemplateService.render(
                PromptTemplateNames.PROFILER_ANOMALY,
                Map.of(
                    "profileBlock", boundaryRenderer.renderProfileBlock(profile),
                    // 医生输入属于不可控外部文本，一并转义
                    "requirement", boundaryRenderer.escape(context.getRequirement())
                ));
            return chatClient.prompt().user(prompt).call().content();
        }
        catch (Exception exception) {
            log.warn("画像自然语言归纳失败，降级为空描述，后续环节不受影响。原因: {}",
                exception.getMessage());
            return "";
        }
    }
}
