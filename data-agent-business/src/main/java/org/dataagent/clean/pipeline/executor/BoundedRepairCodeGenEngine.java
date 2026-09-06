package org.dataagent.clean.pipeline.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.agent.AgentContext;
import org.dataagent.clean.pipeline.agent.CodeGenPort;
import org.dataagent.clean.pipeline.config.CleanAgentProperties;
import org.dataagent.clean.pipeline.model.plan.PlanAction;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.pipeline.support.DataBoundaryRenderer;
import org.dataagent.clean.prompt.PromptTemplateNames;
import org.dataagent.clean.prompt.PromptTemplateService;
import org.dataagent.clean.toolsclient.model.ExecuteResponse;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link CodeGenPort} 的实现：ChatClient + 有界自修复。放在 {@code pipeline/executor/}
 * （框架隔离约束允许依赖编排框架的包之一），换编排框架时改动落在本包内。
 *
 * <p>阈值只给键名不给值：{@link #describeParamKeys} 把 {@code {"体温_min": 32.0}}
 * 渲染成键名和形状描述，数值不出现在 Prompt 里，值在沙箱执行时随 HTTP 请求注入。
 */
@Component
public class BoundedRepairCodeGenEngine implements CodeGenPort {

    private static final Logger log = LoggerFactory.getLogger(BoundedRepairCodeGenEngine.class);

    /** 代码生成温度 0.2，不取 0，给自修复留出产生不同解法的余地 */
    private static final double CODEGEN_TEMPERATURE = 0.2;

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final DataBoundaryRenderer boundaryRenderer;
    private final ObjectMapper objectMapper;
    private final CleanAgentProperties properties;

    public BoundedRepairCodeGenEngine(ChatClient.Builder chatClientBuilder,
                                      PromptTemplateService promptTemplateService,
                                      DataBoundaryRenderer boundaryRenderer,
                                      ObjectMapper objectMapper,
                                      CleanAgentProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
        this.boundaryRenderer = boundaryRenderer;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public int maxRepairAttempts() {
        return properties.getMaxRepairAttempts();
    }

    @Override
    @TraceStage(TraceStageCode.CODEGEN)
    public String generate(AgentContext context, PlanStep step, ProfileResponse profile) {
        String system = promptTemplateService.render(PromptTemplateNames.CODEGEN_SYSTEM, Map.of());
        String user = promptTemplateService.render(
            PromptTemplateNames.CODEGEN_USER,
            Map.of(
                "stepDescription", boundaryRenderer.escape(describeStep(step)),
                "targetColumns", String.join("、", step.getTargetColumns()),
                "paramKeys", describeParamKeys(step),
                "schemaBlock", renderSchema(profile)
            ));
        return stripCodeFence(chatClient.prompt()
            .options(ChatOptions.builder().temperature(CODEGEN_TEMPERATURE).build())
            .system(system)
            .user(user)
            .call()
            .content());
    }

    @Override
    @TraceStage(TraceStageCode.REPAIR)
    public String repair(AgentContext context, PlanStep step,
                         String previousCode, ExecuteResponse failure) {
        String system = promptTemplateService.render(PromptTemplateNames.CODEGEN_SYSTEM, Map.of());
        String user = promptTemplateService.render(
            PromptTemplateNames.CODEGEN_REPAIR,
            Map.of(
                "previousCode", previousCode,
                // 报错文本来自沙箱，可能含数据里的字符串，一并转义
                "failureText", boundaryRenderer.escape(failure.failureText()),
                "stepDescription", boundaryRenderer.escape(describeStep(step)),
                "paramKeys", describeParamKeys(step)
            ));
        log.debug("发起自修复，失败原因前 200 字: {}", truncate(failure.failureText()));
        return stripCodeFence(chatClient.prompt()
            .options(ChatOptions.builder().temperature(CODEGEN_TEMPERATURE).build())
            .system(system)
            .user(user)
            .call()
            .content());
    }

    private String describeStep(PlanStep step) {
        String text = "%s（%s）：%s".formatted(
            step.getAction().name(), step.getAction().getLabel(),
            step.getDescription() == null ? "" : step.getDescription());
        return text + scoringConvention(step);
    }

    /**
     * COMPUTE_SCORE 的输出约定：总分写进哪一列，以及某个参数缺失时那一行怎么办
     * （整行总分置空，不按 0 分算）。不含任何数字，分档全在 params 里。
     */
    private String scoringConvention(PlanStep step) {
        if (step.getAction() != PlanAction.COMPUTE_SCORE || step.getScoringSystem() == null) {
            return "";
        }
        return "\n【本步骤的输出约定】\n"
            + "1. 把每行的总分写进新列「%s_score」，原有列一列都不要改。\n".formatted(
                step.getScoringSystem())
            + "2. 参与计分的列就是上面列出的目标列，每列按它自己那份 params 分档取分再求和。\n"
            + "3. ★ 只要有任何一个参与计分的列在该行是缺失的，"
            + "这一行的总分置空（np.nan），不要把缺失项当 0 分。\n"
            + "   缺一项就当它正常，会把没测全的危重患者算成低危。";
    }

    /** 只描述键名、类型和用途，不写具体数值。 */
    private String describeParamKeys(PlanStep step) {
        if (step.getParams().isEmpty()) {
            return "（本步骤无需临床参数，params 为空字典）";
        }
        List<String> lines = new ArrayList<>();
        step.getParams().forEach((key, value) ->
            lines.add("- params[\"%s\"]：%s".formatted(key, describeShape(key, value))));
        return String.join("\n", lines);
    }

    /**
     * 描述参数的形状而非值。结构是契约（如「元素形如 {min, max, score}」），
     * 具体数值是临床规范，留在知识库里运行时注入。
     */
    private String describeShape(String key, Object value) {
        if (key.endsWith("_bands")) {
            return "评分分档列表，元素形如 {\"min\": 数值, \"max\": 数值, \"score\": 整数}；"
                + "min 或 max 缺省表示该侧无界；取第一个满足 min 小于等于值且值小于等于 max 的档的 score";
        }
        if (key.endsWith("_mappings")) {
            return "取值到分数的映射列表，元素形如 {\"category\": 字符串, \"score\": 整数}；"
                + "按该列的取值查表，查不到的取值不计分并使该行总分置空";
        }
        if (key.endsWith("_allowed")) {
            return "该列的允许取值列表（字符串）";
        }
        return value instanceof List<?> ? "列表" : "数值";
    }

    /** 给代码生成看的结构参考：只有列名和类型，不给统计量和取值枚举。 */
    private String renderSchema(ProfileResponse profile) {
        if (profile == null) {
            return boundaryRenderer.wrap("{}");
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ProfileResponse.ColumnProfile column : profile.getColumns()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", column.getName());
            item.put("dtype", column.getDtype());
            columns.add(item);
        }
        try {
            return boundaryRenderer.wrapEscaped(
                objectMapper.writeValueAsString(Map.of("columns", columns)));
        }
        catch (Exception exception) {
            throw new IllegalStateException("列结构序列化失败", exception);
        }
    }

    /** 剥掉模型可能加上的 Markdown 代码块围栏，避免 Python 侧 ast.parse 报语法错误。 */
    private String stripCodeFence(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        text = text.substring(firstNewline + 1);
        int lastFence = text.lastIndexOf("```");
        return (lastFence >= 0 ? text.substring(0, lastFence) : text).trim();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
