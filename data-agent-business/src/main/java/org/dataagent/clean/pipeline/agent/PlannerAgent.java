package org.dataagent.clean.pipeline.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.knowledge.KnowledgeLookup;
import org.dataagent.clean.pipeline.knowledge.KnowledgeRuleService;
import org.dataagent.clean.pipeline.knowledge.RuleType;
import org.dataagent.clean.pipeline.knowledge.ScoringSystemResolver;
import org.dataagent.clean.pipeline.knowledge.UndecidedRule;
import org.dataagent.clean.pipeline.knowledge.ValidityRange;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.ClarificationLevel;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.PlanAction;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.pipeline.support.DataBoundaryRenderer;
import org.dataagent.clean.prompt.PromptTemplateNames;
import org.dataagent.clean.prompt.PromptTemplateService;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 2：方案规划 + 澄清三级分流。
 *
 */
@Component
public class PlannerAgent implements DataAgent<ProfileResponse, CleaningPlan> {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    /** 规划温度取 0，保证同输入同输出 */
    private static final double PLANNING_TEMPERATURE = 0.0;

    /** 澄清措辞温度 0.5，允许自然表达 */
    private static final double WORDING_TEMPERATURE = 0.5;

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final DataBoundaryRenderer boundaryRenderer;
    private final KnowledgeRuleService knowledgeRuleService;
    private final ScoringSystemResolver scoringSystemResolver;
    private final ObjectMapper objectMapper;

    public PlannerAgent(ChatClient.Builder chatClientBuilder,
                        PromptTemplateService promptTemplateService,
                        DataBoundaryRenderer boundaryRenderer,
                        KnowledgeRuleService knowledgeRuleService,
                        ScoringSystemResolver scoringSystemResolver,
                        ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.promptTemplateService = promptTemplateService;
        this.boundaryRenderer = boundaryRenderer;
        this.knowledgeRuleService = knowledgeRuleService;
        this.scoringSystemResolver = scoringSystemResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentCode code() {
        return AgentCode.PLANNER;
    }

    @Override
    @TraceStage(TraceStageCode.PLAN)
    public CleaningPlan run(AgentContext context, ProfileResponse profile) {
        PlannerDraft draft = requestDraft(context, profile);

        CleaningPlan plan = new CleaningPlan();
        plan.setPlanCode("P" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        plan.setSummary(draft.getSummary());

        // 需求原文里点名的评分系统参与知识库检索，从原文确定性识别，不问模型
        String scoringSystem = scoringSystemResolver.resolve(context.getRequirement()).orElse(null);

        int stepNo = 0;
        for (PlannerDraft.DraftStep draftStep : draft.getSteps()) {
            PlanAction action = PlanAction.parse(draftStep.getAction());
            if (action == PlanAction.UNKNOWN) {
                // 无法识别的动作跳过但留痕，供失败归因使用
                log.warn("规划返回了无法识别的动作，已跳过: {}", draftStep.getAction());
                continue;
            }
            PlanStep step = new PlanStep();
            step.setStepNo(++stepNo);
            step.setAction(action);
            step.setDescription(draftStep.getDescription());
            step.setTargetColumns(alignColumns(draftStep.getTargetColumns(), profile));
            overrideScoringColumns(step, profile, scoringSystem);
            triage(context, plan, step, profile, scoringSystem);
            plan.getSteps().add(step);
        }

        plan.sortClarifications();
        wordQuestions(plan);

        log.info("规划完成 taskCode={} 步骤={} 其中可执行={} 待澄清={}",
            context.getTaskCode(), plan.getSteps().size(),
            plan.executableSteps().size(), plan.pendingQuestions().size());
        return plan;
    }

    // ── 第一步：唯一的 LLM 调用 ──

    private PlannerDraft requestDraft(AgentContext context, ProfileResponse profile) {
        BeanOutputConverter<PlannerDraft> converter = new BeanOutputConverter<>(PlannerDraft.class);

        String system = promptTemplateService.render(PromptTemplateNames.PLANNER_SYSTEM, Map.of());
        String user = promptTemplateService.render(
            PromptTemplateNames.PLANNER_USER,
            Map.of(
                "requirement", boundaryRenderer.escape(context.getRequirement()),
                "profileBlock", boundaryRenderer.renderProfileBlock(profile),
                "outputFormat", converter.getFormat()
            ));

        String content = chatClient.prompt()
            .options(ChatOptions.builder().temperature(PLANNING_TEMPERATURE).build())
            .system(system)
            .user(user)
            .call()
            .content();
        try {
            return converter.convert(content);
        }
        catch (Exception exception) {
            // 结构化输出解析失败，归为独立失败类别 OUTPUT_FORMAT
            log.error("规划输出解析失败，原始内容前 500 字: {}",
                content == null ? "null" : content.substring(0, Math.min(500, content.length())));
            throw new IllegalStateException("规划 Agent 的结构化输出无法解析", exception);
        }
    }

    // ── 第二步：列名对齐（确定性） ──

    /**
     * 把模型给的列名对齐到数据集里真实存在的列。对齐失败的列直接丢弃并记日志，
     * 不做模糊匹配。
     */
    private List<String> alignColumns(List<String> raw, ProfileResponse profile) {
        Set<String> datasetColumns = new LinkedHashSet<>();
        profile.getColumns().forEach(column -> datasetColumns.add(column.getName()));

        List<String> aligned = new ArrayList<>();
        for (String candidate : raw) {
            if (datasetColumns.contains(candidate)) {
                aligned.add(candidate);
            }
            else {
                log.warn("规划给出的列名不在数据集中，已忽略: {}", candidate);
            }
        }
        return aligned;
    }

    /**
     * 评分的计分列由知识库定，不由模型定。
     *
     */
    private void overrideScoringColumns(PlanStep step, ProfileResponse profile,
                                        String scoringSystem) {
        if (step.getAction() != PlanAction.COMPUTE_SCORE || scoringSystem == null) {
            return;
        }
        List<String> defined = knowledgeRuleService.scoringColumns(scoringSystem);
        if (defined.isEmpty()) {
            log.warn("知识库里没有「{}」的任何分档规则，计分列维持模型给的原样", scoringSystem);
            return;
        }
        Set<String> datasetColumns = new LinkedHashSet<>();
        profile.getColumns().forEach(column -> datasetColumns.add(column.getName()));

        List<String> usable = defined.stream().filter(datasetColumns::contains).toList();
        List<String> absent = defined.stream().filter(name -> !datasetColumns.contains(name)).toList();
        List<String> dropped = step.getTargetColumns().stream()
            .filter(name -> !defined.contains(name)).toList();

        if (usable.isEmpty()) {
            log.warn("「{}」定义的 {} 个计分列在本数据集里一个都没有，维持原样",
                scoringSystem, defined.size());
            return;
        }
        step.setTargetColumns(usable);

        StringBuilder note = new StringBuilder();
        if (!dropped.isEmpty()) {
            note.append("（%s 不含 %s，已从计分列移除）".formatted(scoringSystem, String.join("、", dropped)));
        }
        if (!absent.isEmpty()) {
            // 少算项的总分和完整总分数值上无法区分，必须写进说明
            note.append("（本数据集缺少 %s 列，本次总分不含这几项）"
                .formatted(String.join("、", absent)));
            log.warn("「{}」定义的计分列 {} 在本数据集中不存在，总分是不完整的",
                scoringSystem, absent);
        }
        if (!note.isEmpty()) {
            step.setDescription((step.getDescription() == null ? "" : step.getDescription())
                + note);
        }
        log.info("计分列由知识库接管：{} 定义 {} 列，本数据集可用 {} 列",
            scoringSystem, defined.size(), usable.size());
    }

    // ── 第三步：澄清三级分流（确定性） ──

    /**
     * 按知识库命中情况分流：
     * <pre>
     *   动作不需要临床依据          → 直接执行
     *   命中 1 条且规则内部已决      → HIGH   自动决定，记录 ruleId，装配 params
     *   命中 &gt;1 条                   → LOW    语义歧义，澄清
     *   命中 0 条                    → LOW    无依据，澄清
     *   命中 1 条但规则内部未决      → LOW    决策点澄清（见 UndecidedRule）
     *   confirmedAnswers 里已答过    → 复用答案，不重复提问
     *   可归并到已有澄清项           → MEDIUM 合并
     * </pre>
     *
     * <p>{@code scoringSystem} 限定词只对 SEVERITY_SCORING 生效：非评分规则的
     * {@code scoring_system} 为空串，带限定词检索必然 0 命中。
     */
    private void triage(AgentContext context, CleaningPlan plan,
                        PlanStep step, ProfileResponse profile, String scoringSystem) {
        RuleType required = step.getAction().requiredRuleType();
        if (required == null) {
            // 纯工程动作（去重、日期归一、全半角归一），无临床判断空间，不澄清
            return;
        }
        if (step.getTargetColumns().isEmpty()) {
            log.warn("步骤 {} 需要 {} 依据但没有目标列，无法检索知识库",
                step.getAction(), required);
            return;
        }

        String qualifier = required == RuleType.SEVERITY_SCORING ? scoringSystem : null;

        for (String column : step.getTargetColumns()) {
            KnowledgeLookup lookup = qualifier == null
                ? knowledgeRuleService.find(column, required)
                : knowledgeRuleService.find(column, required, qualifier);

            // 命中 1 条也可能是规则内部的决策点，需要单独判定
            Optional<UndecidedRule> undecided = lookup.isResolved()
                ? knowledgeRuleService.undecided(lookup.single())
                : Optional.empty();

            if (lookup.isResolved() && undecided.isEmpty()) {
                step.getRuleIds().addAll(lookup.ruleIds());
                assembleParams(step, column, required, qualifier);
                continue;
            }

            String topic = topicOf(column, required);
            if (context.getConfirmedAnswers().containsKey(topic)) {
                // 中断恢复：医生已答过，不重复提问
                log.info("澄清点 {} 已有既往答复，跳过提问", topic);
                continue;
            }
            Optional<ClarificationItem> mergeable = findMergeable(plan, topic);
            if (mergeable.isPresent()) {
                mergeable.get().setLevel(ClarificationLevel.MEDIUM);
                continue;
            }
            plan.getClarifications().add(
                buildClarification(column, required, lookup, profile, undecided.orElse(null)));
        }
    }

    /**
     * 把知识库查出的阈值装进 {@code params}，不拼进 Prompt。生成的代码通过
     * {@code params["体温_min"]} 读取，模型看不到具体数值。
     */
    private void assembleParams(PlanStep step, String column,
                                RuleType required, String scoringSystem) {
        if (required == RuleType.SEVERITY_SCORING) {
            assembleScoringParams(step, column, scoringSystem);
            return;
        }
        if (required != RuleType.VALIDITY) {
            // TEXT_MAPPING / MISSING_SEMANTICS 的装配未实现，步骤的 ruleIds 已记下依据
            return;
        }
        Optional<ValidityRange> range = knowledgeRuleService.findValidity(column);
        if (range.isEmpty()) {
            return;
        }
        ValidityRange validity = range.get();
        if (validity.isNumeric()) {
            step.getParams().put(validity.minParamKey(), validity.min());
            step.getParams().put(validity.maxParamKey(), validity.max());
        }
        if (validity.allowedValues() != null) {
            step.getParams().put(validity.allowedValuesParamKey(), validity.allowedValues());
        }
    }

    /**
     * 评分分档的参数装配。整张分档表进 {@code params}，模型只看到键名和形状描述。
     * 装配不成功不继续，不做「查不到就让模型自己写分档」的兜底。
     */
    private void assembleScoringParams(PlanStep step, String column, String scoringSystem) {
        if (scoringSystem == null) {
            // 检索为 RESOLVED（该列只有一套分档）但需求未点名系统，跳过装配
            log.debug("列 {} 的评分分档唯一，但需求未指定系统，跳过参数装配", column);
            return;
        }
        knowledgeRuleService.findScoring(column, scoringSystem).ifPresent(rule -> {
            step.getParams().put(rule.paramKey(), rule.paramValue());
            step.setScoringSystem(scoringSystem);
        });
    }

    private Optional<ClarificationItem> findMergeable(CleaningPlan plan, String topic) {
        return plan.getClarifications().stream()
            .filter(item -> item.getTopic().equals(topic))
            .findFirst();
    }

    /** 主题需稳定可枚举，用于和 golden 的 expectClarifications 比对 */
    private String topicOf(String column, RuleType ruleType) {
        return column + "/" + ruleType.name();
    }

    private ClarificationItem buildClarification(String column, RuleType required,
                                                 KnowledgeLookup lookup,
                                                 ProfileResponse profile,
                                                 UndecidedRule undecided) {
        ClarificationItem item = new ClarificationItem();
        item.setClarifyCode("C" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        item.setLevel(ClarificationLevel.LOW);
        item.setTopic(topicOf(column, required));
        item.setColumnName(column);
        item.setCoverageRatio(coverageOf(column, required, profile));

        if (undecided != null) {
            // 规则内部未决：有依据，且依据本身写着需要用户选择，措辞需与 NO_EVIDENCE 区分
            item.setSourceRuleIds(List.of(undecided.ruleId()));
            item.setOptions(new ArrayList<>(undecided.options()));
            item.setEvidence("院内规范对「%s」的%s给出的是一个决策点而非结论：%s。%s"
                .formatted(column, required.getLabel(),
                    String.join(" / ", undecided.options()),
                    undecided.rationale() == null ? "" : undecided.rationale()));
            item.setQuestion("「%s」的缺失该怎么处理？规范列了 %s 两条路，选哪条取决于本次研究设计。"
                .formatted(column, String.join("、", undecided.options())));
            return item;
        }

        if (lookup.isAmbiguous()) {
            item.setSourceRuleIds(lookup.ruleIds());
            item.setConflictingSources(lookup.conflictingSources());
            item.setEvidence("知识库中「%s」的%s存在 %d 套并存标准：%s。"
                .formatted(column, required.getLabel(), lookup.getRules().size(),
                    String.join("、", lookup.conflictingSources())));
            item.setOptions(new ArrayList<>(lookup.conflictingSources()));
            // 占位措辞，措辞环节失败时兜底
            item.setQuestion("「%s」有多套%s标准，本次分析用哪一套？"
                .formatted(column, required.getLabel()));
        }
        else {
            item.setEvidence(noEvidenceText(column, required, profile));
            item.setQuestion("知识库里查不到「%s」的%s，需要你来定。".formatted(column, required.getLabel()));
        }
        return item;
    }

    /**
     * 无依据时的证据文本，带上画像里的确定性事实（缺失率、非空取值数），
     * 而不只说「查不到规则」。
     */
    private String noEvidenceText(String column, RuleType required, ProfileResponse profile) {
        StringBuilder text = new StringBuilder(
            "知识库中没有「%s」的%s规则（NO_EVIDENCE），系统不做推测。".formatted(column, required.getLabel()));
        profile.getColumns().stream()
            .filter(item -> item.getName().equals(column))
            .findFirst()
            .ifPresent(item -> text.append("该列缺失率 %.2f%%，非空取值 %d 种。"
                .formatted(item.getMissingRate() * 100, item.getDistinctCount())));
        return text.toString();
    }

    /**
     * 覆盖率，用于提问排序。{@code MISSING_SEMANTICS} 看缺失率，其余看非空占比，
     * 取不到时给 0（排最后）。
     */
    private double coverageOf(String column, RuleType required, ProfileResponse profile) {
        return profile.getColumns().stream()
            .filter(item -> item.getName().equals(column))
            .findFirst()
            .map(item -> {
                double missing = item.getMissingRate() == null ? 0.0 : item.getMissingRate();
                return required == RuleType.MISSING_SEMANTICS ? missing : 1.0 - missing;
            })
            .orElse(0.0);
    }

    // ── 第四步：措辞（LLM 只管怎么问，不管问什么） ──

    private void wordQuestions(CleaningPlan plan) {
        List<ClarificationItem> pending = plan.pendingQuestions();
        if (pending.isEmpty()) {
            return;
        }
        try {
            BeanOutputConverter<WordedQuestions> converter =
                new BeanOutputConverter<>(WordedQuestions.class);
            String prompt = promptTemplateService.render(
                PromptTemplateNames.CLARIFICATION_GENERATE,
                Map.of(
                    "clarificationsBlock", boundaryRenderer.wrapEscaped(toJson(pending)),
                    "outputFormat", converter.getFormat()
                ));
            WordedQuestions worded = converter.convert(chatClient.prompt()
                .options(ChatOptions.builder().temperature(WORDING_TEMPERATURE).build())
                .user(prompt)
                .call()
                .content());

            Map<String, WordedQuestions.Worded> byCode = new LinkedHashMap<>();
            worded.getItems().forEach(item -> byCode.put(item.getClarifyCode(), item));
            for (ClarificationItem item : pending) {
                WordedQuestions.Worded replacement = byCode.get(item.getClarifyCode());
                if (replacement != null && replacement.getQuestion() != null
                    && !replacement.getQuestion().isBlank()) {
                    item.setQuestion(replacement.getQuestion());
                    if (replacement.getOptions() != null && !replacement.getOptions().isEmpty()) {
                        item.setOptions(replacement.getOptions());
                    }
                }
            }
        }
        catch (Exception exception) {
            // 措辞失败不影响正确性，降级为占位措辞（显式降级）
            log.warn("澄清措辞生成失败，沿用系统生成的占位措辞（问题内容不受影响）: {}",
                exception.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception exception) {
            throw new IllegalStateException("澄清项序列化失败", exception);
        }
    }
}
