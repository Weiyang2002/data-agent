package org.dataagent.clean.pipeline.knowledge;

import lombok.Getter;
import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;

import java.util.List;

/**
 * 一次知识库检索的结果，按命中条数给出三种显式结局，调用方必须分别处理：
 * <ul>
 *   <li>{@link Outcome#RESOLVED} —— 命中唯一规则，自动决定，记录 ruleId 作为依据</li>
 *   <li>{@link Outcome#AMBIGUOUS} —— 命中多条，语义冲突，触发澄清而非挑一条</li>
 *   <li>{@link Outcome#NO_EVIDENCE} —— 无命中，短路返回，禁止编造</li>
 * </ul>
 *
 * <p>NO_EVIDENCE 依赖检索层是精确匹配的（相似度检索总会返回 top-k），这是本项目
 * 不用向量库的核心理由。
 */
@Getter
public class KnowledgeLookup {

    public enum Outcome {
        RESOLVED,
        AMBIGUOUS,
        NO_EVIDENCE,
    }

    private final String columnName;
    private final RuleType ruleType;
    private final Outcome outcome;
    private final List<KnowledgeRuleEntity> rules;

    private KnowledgeLookup(String columnName, RuleType ruleType,
                            Outcome outcome, List<KnowledgeRuleEntity> rules) {
        this.columnName = columnName;
        this.ruleType = ruleType;
        this.outcome = outcome;
        this.rules = rules;
    }

    public static KnowledgeLookup of(String columnName, RuleType ruleType,
                                     List<KnowledgeRuleEntity> rules) {
        Outcome outcome;
        if (rules == null || rules.isEmpty()) {
            outcome = Outcome.NO_EVIDENCE;
        }
        else if (rules.size() == 1) {
            outcome = Outcome.RESOLVED;
        }
        else {
            outcome = Outcome.AMBIGUOUS;
        }
        return new KnowledgeLookup(columnName, ruleType, outcome,
            rules == null ? List.of() : List.copyOf(rules));
    }

    public boolean isResolved() {
        return outcome == Outcome.RESOLVED;
    }

    public boolean isAmbiguous() {
        return outcome == Outcome.AMBIGUOUS;
    }

    public boolean isNoEvidence() {
        return outcome == Outcome.NO_EVIDENCE;
    }

    /** 唯一命中的规则，非 RESOLVED 时返回 null。 */
    public KnowledgeRuleEntity single() {
        return isResolved() ? rules.get(0) : null;
    }

    /** 歧义时给医生看的候选来源。 */
    public List<String> conflictingSources() {
        return rules.stream()
            .map(rule -> {
                String system = rule.getScoringSystem();
                String label = (system == null || system.isBlank()) ? "院内规范" : system;
                return label + "（" + rule.getSourceDoc() + " " + rule.getSourceLocator() + "）";
            })
            .toList();
    }

    public List<Long> ruleIds() {
        return rules.stream().map(KnowledgeRuleEntity::getId).toList();
    }

    /** 写进日志与 trace 的简短描述。 */
    public String describe() {
        return "%s/%s → %s（命中 %d 条）".formatted(
            columnName, ruleType == null ? "-" : ruleType.name(), outcome, rules.size());
    }
}
