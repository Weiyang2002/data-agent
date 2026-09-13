package org.dataagent.clean.pipeline.clarify;

import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;
import org.dataagent.clean.pipeline.knowledge.KnowledgeRuleService;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.ClarificationLevel;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.PlanAction;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 澄清答复归一。这一层没有模型调用，行为应当逐位可预期。 */
class ClarificationResolverTest {

    private KnowledgeRuleService knowledgeRuleService;
    private ClarificationResolver resolver;

    @BeforeEach
    void setUp() {
        knowledgeRuleService = mock(KnowledgeRuleService.class);
        resolver = new ClarificationResolver(knowledgeRuleService);
    }

    // ── 答复归一 ──

    @Test
    @DisplayName("机器码、选项原文、1 起的序号三种作答都能归一到同一个动作")
    void acceptsCodeLabelAndOrdinal() {
        ClarificationItem item = missingSemanticsItem("升压药");

        for (String answer : List.of("FILL_NEGATIVE", ClarifyAction.FILL_NEGATIVE.getLabel(), "1")) {
            AnswerResolution resolution = resolver.resolve(item, answer, profile());
            assertThat(resolution.isResolved()).as(answer).isTrue();
            assertThat(resolution.optionCode()).isEqualTo("FILL_NEGATIVE");
            assertThat(resolution.params()).containsEntry("升压药_fill_value", "未使用");
        }
    }

    @Test
    @DisplayName("阴性填充值按列的类型给：数值列 0，文本列取值标记")
    void negativeFillValueFollowsColumnType() {
        assertThat(resolver.resolve(missingSemanticsItem("体温"), "FILL_NEGATIVE", profile())
            .params()).containsEntry("体温_fill_value", 0);
        assertThat(resolver.resolve(missingSemanticsItem("升压药"), "FILL_NEGATIVE", profile())
            .params()).containsEntry("升压药_fill_value", "未使用");
    }

    @Test
    @DisplayName("对不上候选项的答复被驳回，不做模糊匹配")
    void rejectsUnmatchedAnswer() {
        AnswerResolution resolution =
            resolver.resolve(missingSemanticsItem("升压药"), "填零吧", profile());

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.rejectReason()).contains("对不上任何候选项");
        assertThat(resolution.params()).isEmpty();
    }

    @Test
    @DisplayName("没有候选项的决策点，自由文本一律驳回")
    void rejectsFreeTextWhenNoOptions() {
        ClarificationItem item = missingSemanticsItem("尿量");
        item.setOptions(List.of());
        item.setOptionCodes(List.of());

        AnswerResolution resolution = resolver.resolve(item, "按 0 处理", profile());

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.rejectReason()).contains("自由文本");
    }

    @Test
    @DisplayName("措辞被改写后按机器码仍能归一：选项含义由代码定，不由措辞定")
    void survivesRewordedOptions() {
        ClarificationItem item = missingSemanticsItem("升压药");
        item.setOptions(List.of("没用过药，记 0", "没测，留空并标记", "按时间补齐", "这列不要了"));

        AnswerResolution resolution = resolver.resolve(item, "没测，留空并标记", profile());

        assertThat(resolution.optionCode()).isEqualTo("KEEP_AND_FLAG");
        assertThat(resolution.params()).isEmpty();
        assertThat(resolution.note()).contains("升压药_is_missing");
    }

    // ── 多套标准并存：选中的是规则，不是名字 ──

    @Test
    @DisplayName("选中一套并存标准后，装配的是那一条规则的参数")
    void ambiguousChoiceAssemblesChosenRuleParams() {
        ClarificationItem item = new ClarificationItem();
        item.setClarifyCode("C-AMB");
        item.setLevel(ClarificationLevel.LOW);
        item.setTopic("意识/TEXT_MAPPING");
        item.setColumnName("意识");
        item.setSourceRuleIds(List.of(3001L, 3003L, 3004L));
        item.setOptions(List.of("AVPU（…）", "NEWS（…）", "院内规范（…）"));
        item.setOptionCodes(List.of("RULE:3001", "RULE:3003", "RULE:3004"));

        KnowledgeRuleEntity rule = new KnowledgeRuleEntity();
        rule.setId(3003L);
        rule.setColumnName("意识");
        rule.setScoringSystem("NEWS");
        rule.setSourceDoc("NEWS2");
        rule.setSourceLocator("意识水平行");
        when(knowledgeRuleService.findById(3003L)).thenReturn(Optional.of(rule));
        when(knowledgeRuleService.paramsForRule(rule))
            .thenReturn(Map.of("意识_score_map", List.of(Map.of("value", "清醒", "score", 0))));

        AnswerResolution resolution = resolver.resolve(item, "RULE:3003", profile());

        assertThat(resolution.isResolved()).isTrue();
        assertThat(resolution.ruleIds()).containsExactly(3003L);
        assertThat(resolution.knowledgeBacked()).isTrue();
        assertThat(resolution.params()).containsKey("意识_score_map");
        assertThat(resolution.note()).contains("NEWS");
    }

    @Test
    @DisplayName("选中的规则装配不出参数时驳回，不退回硬编码")
    void rejectsWhenChosenRuleHasNoUsableParams() {
        ClarificationItem item = new ClarificationItem();
        item.setClarifyCode("C-AMB");
        item.setTopic("意识/TEXT_MAPPING");
        item.setColumnName("意识");
        item.setOptionCodes(List.of("RULE:3001"));
        item.setOptions(List.of("AVPU（…）"));

        KnowledgeRuleEntity rule = new KnowledgeRuleEntity();
        rule.setId(3001L);
        when(knowledgeRuleService.findById(3001L)).thenReturn(Optional.of(rule));
        when(knowledgeRuleService.paramsForRule(any())).thenReturn(Map.of());

        AnswerResolution resolution = resolver.resolve(item, "1", profile());

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.rejectReason()).contains("无法装配成执行参数");
    }

    // ── 插值：分组键推不出来就显式降级 ──

    @Test
    @DisplayName("插值的分组键与排序键取自画像的主键候选")
    void interpolateTakesKeysFromProfile() {
        AnswerResolution resolution =
            resolver.resolve(missingSemanticsItem("体温"), "INTERPOLATE", profile());

        assertThat(resolution.isResolved()).isTrue();
        assertThat(resolution.params()).containsEntry("体温_interpolate_group", List.of("住院ID"));
        assertThat(resolution.params()).containsEntry("体温_interpolate_order", "记录时间");
    }

    @Test
    @DisplayName("画像里没有「标识列 + 时间列」候选时，插值驳回而不是退化成整列插值")
    void interpolateDegradesLoudlyWithoutKeys() {
        ProfileResponse bare = new ProfileResponse();
        ProfileResponse.ColumnProfile column = new ProfileResponse.ColumnProfile();
        column.setName("体温");
        column.setDtype("float64");
        bare.setColumns(List.of(column));

        AnswerResolution resolution =
            resolver.resolve(missingSemanticsItem("体温"), "INTERPOLATE", bare);

        assertThat(resolution.isResolved()).isFalse();
        assertThat(resolution.rejectReason()).contains("主键候选");
    }

    // ── 并回方案 ──

    @Test
    @DisplayName("一步多列时，只答了一列不解锁执行")
    void partiallyAnsweredStepStaysBlocked() {
        PlanStep step = fillMissingStep("升压药", "意识");
        CleaningPlan plan = new CleaningPlan();
        plan.getSteps().add(step);
        assertThat(step.isExecutable()).isFalse();

        int unblocked = resolver.applyToPlan(plan, List.of(
            resolver.resolve(missingSemanticsItem("升压药"), "FILL_NEGATIVE", profile())));

        assertThat(unblocked).isZero();
        assertThat(step.isExecutable()).isFalse();
        assertThat(step.getUnresolvedColumns()).containsExactly("意识");
        assertThat(step.getParams()).containsKey("升压药_fill_value");
    }

    @Test
    @DisplayName("最后一列也答完后步骤转为可执行，依据记为医生自决")
    void fullyAnsweredStepBecomesExecutable() {
        PlanStep step = fillMissingStep("升压药", "意识");
        CleaningPlan plan = new CleaningPlan();
        plan.getSteps().add(step);

        int unblocked = resolver.applyToPlan(plan, List.of(
            resolver.resolve(missingSemanticsItem("升压药"), "FILL_NEGATIVE", profile()),
            resolver.resolve(missingSemanticsItem("意识"), "EXCLUDE_COLUMN", profile())));

        assertThat(unblocked).isOne();
        assertThat(step.isExecutable()).isTrue();
        assertThat(step.isUserDecided()).isTrue();
        assertThat(step.getRuleIds()).isEmpty();
    }

    @Test
    @DisplayName("被驳回的答复不改动方案")
    void rejectedAnswerLeavesPlanUntouched() {
        PlanStep step = fillMissingStep("升压药");
        CleaningPlan plan = new CleaningPlan();
        plan.getSteps().add(step);

        int unblocked = resolver.applyToPlan(plan, List.of(
            resolver.resolve(missingSemanticsItem("升压药"), "随便填", profile())));

        assertThat(unblocked).isZero();
        assertThat(step.getParams()).isEmpty();
        assertThat(step.getUnresolvedColumns()).containsExactly("升压药");
    }

    @Test
    @DisplayName("答复只落到规则类型对得上的步骤上")
    void answerOnlyTouchesMatchingRuleType() {
        PlanStep mapping = new PlanStep();
        mapping.setStepNo(1);
        mapping.setAction(PlanAction.MAP_TEXT_CODE);
        mapping.setTargetColumns(List.of("升压药"));
        mapping.getUnresolvedColumns().add("升压药");
        CleaningPlan plan = new CleaningPlan();
        plan.getSteps().add(mapping);

        resolver.applyToPlan(plan, List.of(
            resolver.resolve(missingSemanticsItem("升压药"), "FILL_NEGATIVE", profile())));

        assertThat(mapping.getParams()).isEmpty();
        assertThat(mapping.isExecutable()).isFalse();
    }

    // ── 夹具 ──

    private ClarificationItem missingSemanticsItem(String column) {
        ClarificationItem item = new ClarificationItem();
        item.setClarifyCode("C-" + column);
        item.setLevel(ClarificationLevel.LOW);
        item.setTopic(column + "/MISSING_SEMANTICS");
        item.setColumnName(column);
        List<ClarifyAction> menu = ClarificationResolver.missingSemanticsMenu();
        item.setOptions(menu.stream().map(ClarifyAction::getLabel).toList());
        item.setOptionCodes(menu.stream().map(Enum::name).toList());
        return item;
    }

    private PlanStep fillMissingStep(String... columns) {
        PlanStep step = new PlanStep();
        step.setStepNo(1);
        step.setAction(PlanAction.FILL_MISSING);
        step.setTargetColumns(List.of(columns));
        step.getUnresolvedColumns().addAll(List.of(columns));
        return step;
    }

    private ProfileResponse profile() {
        ProfileResponse profile = new ProfileResponse();
        ProfileResponse.ColumnProfile temperature = column("体温", "float64");
        temperature.setNumeric(new ProfileResponse.NumericProfile());
        profile.setColumns(List.of(
            column("住院ID", "object"), column("记录时间", "datetime64[ns]"),
            temperature, column("升压药", "object"), column("意识", "object")));
        ProfileResponse.KeyCandidate candidate = new ProfileResponse.KeyCandidate();
        candidate.setColumns(List.of("住院ID", "记录时间"));
        candidate.setUniqueRatio(0.988);
        profile.setKeyCandidates(List.of(candidate));
        return profile;
    }

    private ProfileResponse.ColumnProfile column(String name, String dtype) {
        ProfileResponse.ColumnProfile column = new ProfileResponse.ColumnProfile();
        column.setName(name);
        column.setDtype(dtype);
        return column;
    }
}
