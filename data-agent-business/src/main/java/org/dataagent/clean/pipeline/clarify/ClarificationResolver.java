package org.dataagent.clean.pipeline.clarify;

import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;
import org.dataagent.clean.pipeline.knowledge.KnowledgeRuleService;
import org.dataagent.clean.pipeline.knowledge.RuleType;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 澄清答复 → 执行参数的归一，全确定性，零模型调用。
 *
 * <p>答复只在 {@code optionCodes} 这份机器码表里取值：措辞由模型改写，选项含义由
 * 代码决定，两者按下标对应。归一不出来的答复照样落库，但不产生执行动作。
 *
 * <p>三种决策点的归一路径：
 * <pre>
 *   多套标准并存  → 机器码 RULE:&lt;id&gt;  → 装配该条规则的参数，依据是知识库
 *   规则内部未决  → 机器码 ClarifyAction → 装配缺失处理参数，依据仍是知识库
 *   知识库无依据  → 机器码 ClarifyAction → 装配缺失处理参数，依据是医生自决
 * </pre>
 */
@Component
public class ClarificationResolver {

    private static final Logger log = LoggerFactory.getLogger(ClarificationResolver.class);

    /** 选中某条并存规则的机器码前缀 */
    static final String RULE_CODE_PREFIX = "RULE:";

    /** 数值列的阴性填充值 */
    private static final int NUMERIC_NEGATIVE_FILL = 0;

    /** 文本列的阴性填充值。给文本列填 0 会把整列变成 str/int 混排，parquet 写不出去 */
    private static final String TEXT_NEGATIVE_FILL = "未使用";

    private final KnowledgeRuleService knowledgeRuleService;

    public ClarificationResolver(KnowledgeRuleService knowledgeRuleService) {
        this.knowledgeRuleService = knowledgeRuleService;
    }

    /** 把答复归一成机器码再装配参数。 */
    public AnswerResolution resolve(ClarificationItem item, String answer,
                                    ProfileResponse profile) {
        RuleType ruleType = ruleTypeOf(item.getTopic());
        Optional<String> code = matchOptionCode(item, answer);
        if (code.isEmpty()) {
            return AnswerResolution.rejected(item.getClarifyCode(), item.getTopic(),
                item.getColumnName(), ruleType, answer,
                item.getOptionCodes().isEmpty()
                    ? "该决策点没有可选项，自由文本无法确定性地映射为执行参数"
                    : "答复对不上任何候选项，候选项为：" + String.join(" / ", item.getOptions()));
        }
        String optionCode = code.get();
        if (optionCode.startsWith(RULE_CODE_PREFIX)) {
            return resolveRuleChoice(item, answer, ruleType, optionCode);
        }
        return resolveActionChoice(item, answer, ruleType, optionCode, profile);
    }

    /**
     * 把归一结果并进方案。被答复覆盖的目标列从 {@code unresolvedColumns} 里移除，
     * 该步骤的全部列都落地后才重新变成可执行。
     *
     * @return 本次由不可执行转为可执行的步骤数
     */
    public int applyToPlan(CleaningPlan plan, List<AnswerResolution> resolutions) {
        int unblocked = 0;
        for (PlanStep step : plan.getSteps()) {
            if (step.isExecutable()) {
                continue;
            }
            boolean touched = false;
            for (AnswerResolution resolution : resolutions) {
                if (!resolution.isResolved() || !matches(step, resolution)) {
                    continue;
                }
                step.getRuleIds().addAll(resolution.ruleIds());
                step.getParams().putAll(resolution.params());
                step.getUnresolvedColumns().remove(resolution.columnName());
                if (!resolution.knowledgeBacked()) {
                    step.setUserDecided(true);
                }
                if (resolution.note() != null) {
                    step.setDescription((step.getDescription() == null ? ""
                        : step.getDescription()) + resolution.note());
                }
                touched = true;
            }
            if (touched && step.isExecutable()) {
                unblocked++;
                log.info("步骤 {}（{}）拿到澄清答复，转为可执行", step.getStepNo(), step.getAction());
            }
        }
        return unblocked;
    }

    // ── 归一：选中某条并存规则 ──

    private AnswerResolution resolveRuleChoice(ClarificationItem item, String answer,
                                               RuleType ruleType, String optionCode) {
        Long ruleId = Long.valueOf(optionCode.substring(RULE_CODE_PREFIX.length()));
        Optional<KnowledgeRuleEntity> rule = knowledgeRuleService.findById(ruleId);
        if (rule.isEmpty()) {
            return AnswerResolution.rejected(item.getClarifyCode(), item.getTopic(),
                item.getColumnName(), ruleType, answer,
                "选中的规则 " + ruleId + " 已失效或不存在");
        }
        Map<String, Object> params = knowledgeRuleService.paramsForRule(rule.get());
        if (params.isEmpty()) {
            return AnswerResolution.rejected(item.getClarifyCode(), item.getTopic(),
                item.getColumnName(), ruleType, answer,
                "规则 " + ruleId + " 的内容无法装配成执行参数");
        }
        String note = "（本次采用 %s，依据 %s %s）".formatted(
            sourceLabel(rule.get()), rule.get().getSourceDoc(), rule.get().getSourceLocator());
        return new AnswerResolution(item.getClarifyCode(), item.getTopic(),
            item.getColumnName(), ruleType, answer, optionCode, List.of(ruleId), params,
            note, true, null);
    }

    private String sourceLabel(KnowledgeRuleEntity rule) {
        String system = rule.getScoringSystem();
        return (system == null || system.isBlank()) ? "院内规范" : system;
    }

    // ── 归一：选中一个缺失处理动作 ──

    private AnswerResolution resolveActionChoice(ClarificationItem item, String answer,
                                                 RuleType ruleType, String optionCode,
                                                 ProfileResponse profile) {
        Optional<ClarifyAction> parsed = ClarifyAction.parse(optionCode);
        if (parsed.isEmpty()) {
            return AnswerResolution.rejected(item.getClarifyCode(), item.getTopic(),
                item.getColumnName(), ruleType, answer, "无法识别的动作码 " + optionCode);
        }
        ClarifyAction action = parsed.get();
        String column = item.getColumnName();
        Map<String, Object> params = new LinkedHashMap<>();
        String note;

        switch (action) {
            case FILL_NEGATIVE -> {
                params.put(column + "_fill_value", negativeFillValue(profile, column));
                note = "（医生确认「%s」的缺失代表未发生，用 params[\"%s_fill_value\"] 填充，"
                    .formatted(column, column) + "不要改该列的类型，其余列不动）";
            }
            case KEEP_AND_FLAG -> note =
                "（医生确认「%s」的缺失代表未测量：该列保持原样，另新增布尔列「%s_is_missing」"
                    .formatted(column, column) + "标记缺失行）";
            case EXCLUDE_COLUMN -> note =
                "（医生确认「%s」本次不参与分析，删除该列，其余列不动）".formatted(column);
            case INTERPOLATE -> {
                Optional<TimeSeriesKeys> keys = inferTimeSeriesKeys(profile, column);
                if (keys.isEmpty()) {
                    return AnswerResolution.rejected(item.getClarifyCode(), item.getTopic(),
                        column, ruleType, answer,
                        "按时序插值需要「按谁分组、按什么排序」，画像里没有可用的"
                            + "「标识列 + 时间列」主键候选，本步骤不执行");
                }
                params.put(column + "_interpolate_group", keys.get().groupColumns());
                params.put(column + "_interpolate_order", keys.get().orderColumn());
                note = "（医生确认「%s」的缺失代表未测量，按 params[\"%s_interpolate_group\"] 分组、"
                    .formatted(column, column)
                    + "params[\"%s_interpolate_order\"] 排序后线性插值，插值不外推）".formatted(column);
            }
            default -> throw new IllegalStateException("未覆盖的动作 " + action);
        }
        boolean knowledgeBacked = !item.getSourceRuleIds().isEmpty();
        return new AnswerResolution(item.getClarifyCode(), item.getTopic(), column, ruleType,
            answer, optionCode, List.copyOf(item.getSourceRuleIds()), params, note,
            knowledgeBacked, null);
    }

    /**
     * 阴性填充值按列的类型给：数值列填 0，文本列填一个取值标记。给文本列填 0 会让整
     * 列变成 str 与 int 混排，pandas 不报错、pyarrow 写 parquet 时才炸。
     */
    private Object negativeFillValue(ProfileResponse profile, String column) {
        boolean numeric = profile != null && profile.getColumns().stream()
            .filter(item -> item.getName().equals(column))
            .anyMatch(item -> item.getNumeric() != null);
        return numeric ? NUMERIC_NEGATIVE_FILL : TEXT_NEGATIVE_FILL;
    }

    /**
     * 插值的分组键与排序键来自画像的主键候选，不是临床判断也不问模型：取唯一性最高
     * 的那组「非时间列 + 唯一一个时间列」组合。找不到就返回 empty 让上层显式降级，
     * 不退回「按整列顺序插值」——那会把不同患者的值串起来。
     */
    private Optional<TimeSeriesKeys> inferTimeSeriesKeys(ProfileResponse profile, String column) {
        if (profile == null) {
            return Optional.empty();
        }
        Map<String, String> dtypes = new LinkedHashMap<>();
        profile.getColumns().forEach(item -> dtypes.put(item.getName(), item.getDtype()));

        return profile.getKeyCandidates().stream()
            .sorted((left, right) -> Double.compare(
                right.getUniqueRatio() == null ? 0.0 : right.getUniqueRatio(),
                left.getUniqueRatio() == null ? 0.0 : left.getUniqueRatio()))
            .map(candidate -> {
                List<String> time = candidate.getColumns().stream()
                    .filter(name -> isDatetime(dtypes.get(name))).toList();
                List<String> group = candidate.getColumns().stream()
                    .filter(name -> !isDatetime(dtypes.get(name)))
                    .filter(name -> !name.equals(column))
                    .toList();
                return time.size() == 1 && !group.isEmpty()
                    ? new TimeSeriesKeys(group, time.get(0)) : null;
            })
            .filter(keys -> keys != null)
            .findFirst();
    }

    private boolean isDatetime(String dtype) {
        return dtype != null && dtype.toLowerCase().contains("datetime");
    }

    private record TimeSeriesKeys(List<String> groupColumns, String orderColumn) {
    }

    // ── 答复归一 ──

    /**
     * 把医生的答复对到机器码。依次试：机器码原文 → 选项措辞 → 1 起的序号。
     * 匹配不上返回 empty，不做模糊匹配。
     */
    private Optional<String> matchOptionCode(ClarificationItem item, String answer) {
        if (answer == null || answer.isBlank() || item.getOptionCodes().isEmpty()) {
            return Optional.empty();
        }
        String trimmed = answer.trim();
        List<String> codes = item.getOptionCodes();

        for (String code : codes) {
            if (code.equalsIgnoreCase(trimmed)) {
                return Optional.of(code);
            }
        }
        List<String> options = item.getOptions();
        for (int index = 0; index < options.size() && index < codes.size(); index++) {
            if (trimmed.equals(options.get(index))) {
                return Optional.of(codes.get(index));
            }
        }
        try {
            int ordinal = Integer.parseInt(trimmed);
            if (ordinal >= 1 && ordinal <= codes.size()) {
                return Optional.of(codes.get(ordinal - 1));
            }
        }
        catch (NumberFormatException ignored) {
            // 不是序号，继续走匹配失败
        }
        return Optional.empty();
    }

    private boolean matches(PlanStep step, AnswerResolution resolution) {
        return step.getAction().requiredRuleType() == resolution.ruleType()
            && step.getTargetColumns().contains(resolution.columnName());
    }

    /** topic 形如 {@code 体温/MISSING_SEMANTICS}，取后半段。 */
    private RuleType ruleTypeOf(String topic) {
        if (topic == null) {
            return null;
        }
        int slash = topic.lastIndexOf('/');
        return slash < 0 ? null : RuleType.parse(topic.substring(slash + 1));
    }

    /** 选中某条并存规则的机器码。 */
    public static String ruleOptionCode(Long ruleId) {
        return RULE_CODE_PREFIX + ruleId;
    }

    /** 缺失语义在知识库无依据时给出的候选动作，顺序固定，供答复按序号作答。 */
    public static List<ClarifyAction> missingSemanticsMenu() {
        return new ArrayList<>(List.of(ClarifyAction.FILL_NEGATIVE,
            ClarifyAction.KEEP_AND_FLAG, ClarifyAction.INTERPOLATE,
            ClarifyAction.EXCLUDE_COLUMN));
    }
}
