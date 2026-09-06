package org.dataagent.clean.pipeline.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;
import org.dataagent.clean.pipeline.mapper.KnowledgeRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库检索服务，本项目「RAG」的全部实现。
 *
 * <p>知识库是 44 条强结构化规则，主键为 {@code (列名, 规则类型, 评分系统)}。检索层
 * 是 SQL 精确查询，LLM 只负责把自然语言映射到 {@code (列名, 规则类型)}。不使用
 * 向量库：相似度检索无法保证「查不到就短路」，也不能保证 top-k 覆盖唯一正确规则。
 *
 */
@Service
public class KnowledgeRuleService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRuleService.class);

    private final KnowledgeRuleMapper knowledgeRuleMapper;
    private final ColumnResolver columnResolver;
    private final ObjectMapper objectMapper;

    public KnowledgeRuleService(KnowledgeRuleMapper knowledgeRuleMapper,
                                ColumnResolver columnResolver,
                                ObjectMapper objectMapper) {
        this.knowledgeRuleMapper = knowledgeRuleMapper;
        this.columnResolver = columnResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 检索入口。
     *
     * @param rawColumn LLM 抽出来的列名，可能是别名
     * @param ruleType  规则类型；为 null 表示模型没抽出来，按无依据处理
     */
    public KnowledgeLookup find(String rawColumn, RuleType ruleType) {
        if (ruleType == null) {
            // 规则类型未识别时不猜，按无依据处理
            log.info("知识库检索跳过：规则类型未识别, column={}", rawColumn);
            return KnowledgeLookup.of(rawColumn, null, List.of());
        }

        Optional<String> canonical = columnResolver.resolve(rawColumn);
        if (canonical.isEmpty()) {
            log.info("知识库检索无依据：列名无法对齐, column={}, ruleType={}", rawColumn, ruleType);
            return KnowledgeLookup.of(rawColumn, ruleType, List.of());
        }

        List<KnowledgeRuleEntity> rules =
            knowledgeRuleMapper.findByColumnAndType(canonical.get(), ruleType.name());
        KnowledgeLookup lookup = KnowledgeLookup.of(canonical.get(), ruleType, rules);
        log.info("知识库检索 {}", lookup.describe());
        return lookup;
    }

    /**
     * 指定评分系统的检索。医生已说明「按 NEWS 评分」时走这条，不存在歧义。
     */
    public KnowledgeLookup find(String rawColumn, RuleType ruleType, String scoringSystem) {
        if (ruleType == null || scoringSystem == null || scoringSystem.isBlank()) {
            return find(rawColumn, ruleType);
        }
        Optional<String> canonical = columnResolver.resolve(rawColumn);
        if (canonical.isEmpty()) {
            return KnowledgeLookup.of(rawColumn, ruleType, List.of());
        }
        List<KnowledgeRuleEntity> rules = knowledgeRuleMapper.findByColumnTypeAndSystem(
            canonical.get(), ruleType.name(), scoringSystem);
        KnowledgeLookup lookup = KnowledgeLookup.of(canonical.get(), ruleType, rules);
        log.info("知识库检索（指定 {}） {}", scoringSystem, lookup.describe());
        return lookup;
    }

    /**
     * 取某列的有效性区间，行级校验的阈值来源。返回 empty 表示无依据，调用方不得
     * 退回到硬编码默认区间。
     */
    public Optional<ValidityRange> findValidity(String rawColumn) {
        KnowledgeLookup lookup = find(rawColumn, RuleType.VALIDITY);
        if (!lookup.isResolved()) {
            return Optional.empty();
        }
        return parseValidity(lookup.single());
    }

    /**
     * 判断一条唯一命中的规则是否其实没给出结论。判据是 payload 的
     * {@code recommendedAction} 写成 {@code A_OR_B} 形式，表示规则作者留了一个
     * 需要临床判断的决策点。返回 empty 表示规则给出了唯一动作，可自动执行。
     */
    public Optional<UndecidedRule> undecided(KnowledgeRuleEntity rule) {
        if (rule == null) {
            return Optional.empty();
        }
        try {
            JsonNode payload = objectMapper.readTree(rule.getPayload());
            if (!payload.hasNonNull("recommendedAction")) {
                return Optional.empty();
            }
            String action = payload.get("recommendedAction").asText();
            if (!action.contains("_OR_")) {
                return Optional.empty();
            }
            List<String> options = List.of(action.split("_OR_"));
            log.info("知识库规则 {}（{}）记录的是一个决策点而非结论：{}，转澄清",
                rule.getId(), rule.getColumnName(), action);
            return Optional.of(new UndecidedRule(rule.getId(), rule.getColumnName(), options,
                payload.hasNonNull("rationale") ? payload.get("rationale").asText() : null));
        }
        catch (Exception exception) {
            // 解析失败按「未决」处理：读不懂的依据不能当成已经决定
            log.warn("知识库规则 {} 的 payload 解析失败，按未决处理: {}",
                rule.getId(), exception.getMessage());
            return Optional.of(new UndecidedRule(rule.getId(), rule.getColumnName(),
                List.of(), "规则内容无法解析"));
        }
    }

    /**
     * 取某列在指定评分系统下的分档规则。必须带 {@code scoringSystem}（同列的
     * SEVERITY_SCORING 天然有多套并存）。返回 empty（列名对不齐 / 无规则 /
     * payload 解析失败）时不得退回硬编码分档。
     */
    public Optional<ScoringRule> findScoring(String rawColumn, String scoringSystem) {
        KnowledgeLookup lookup = find(rawColumn, RuleType.SEVERITY_SCORING, scoringSystem);
        if (!lookup.isResolved()) {
            return Optional.empty();
        }
        return parseScoring(lookup.single(), scoringSystem);
    }

    /**
     * 解析 SEVERITY_SCORING 的 payload。bands / mappings 原样按 {@code List<Map>}
     * 带出，不建强类型：这些值的唯一去处是 {@code /execute} 的 params，需以 JSON
     * 形状原样进沙箱，真正的校验发生在 Python 侧算分时。
     */
    private Optional<ScoringRule> parseScoring(KnowledgeRuleEntity rule, String scoringSystem) {
        try {
            JsonNode payload = objectMapper.readTree(rule.getPayload());
            List<Map<String, Object>> bands = readList(payload.get("bands"));
            List<Map<String, Object>> mappings = readList(payload.get("mappings"));
            ScoringRule parsed = new ScoringRule(
                rule.getId(), rule.getColumnName(), scoringSystem, bands, mappings,
                payload.hasNonNull("unit") ? payload.get("unit").asText() : null,
                rule.getSourceDoc(), rule.getSourceLocator());
            if (!parsed.isUsable()) {
                log.warn("知识库规则 {} 的 payload 既无 bands 也无 mappings，按无依据处理",
                    rule.getId());
                return Optional.empty();
            }
            return Optional.of(parsed);
        }
        catch (Exception exception) {
            log.warn("知识库规则 {} 的评分 payload 解析失败，按无依据处理: {}",
                rule.getId(), exception.getMessage());
            return Optional.empty();
        }
    }

    private List<Map<String, Object>> readList(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        return objectMapper.convertValue(node, new TypeReference<>() { });
    }

    /** 批量取有效性区间，查不到的列不出现在结果里。 */
    public List<ValidityRange> findValidities(List<String> rawColumns) {
        List<ValidityRange> ranges = new ArrayList<>();
        for (String column : rawColumns) {
            findValidity(column).ifPresent(ranges::add);
        }
        return ranges;
    }

    /**
     * 解析 VALIDITY 的 payload。解析失败返回 empty 走无依据分支，不抛异常也不给
     * 默认值。
     */
    private Optional<ValidityRange> parseValidity(KnowledgeRuleEntity rule) {
        try {
            JsonNode payload = objectMapper.readTree(rule.getPayload());
            Double min = payload.hasNonNull("min") ? payload.get("min").asDouble() : null;
            Double max = payload.hasNonNull("max") ? payload.get("max").asDouble() : null;

            List<String> allowed = null;
            JsonNode allowedNode = payload.get("allowedValues");
            if (allowedNode != null && allowedNode.isArray()) {
                allowed = new ArrayList<>();
                for (JsonNode item : allowedNode) {
                    allowed.add(item.asText());
                }
            }
            if (min == null && max == null && allowed == null) {
                log.warn("知识库规则 {} 的 payload 既无区间也无允许取值，按无依据处理", rule.getId());
                return Optional.empty();
            }
            return Optional.of(new ValidityRange(
                rule.getId(), rule.getColumnName(), min, max, allowed,
                payload.hasNonNull("unit") ? payload.get("unit").asText() : null,
                rule.getSourceDoc(), rule.getSourceLocator()));
        }
        catch (Exception exception) {
            log.warn("知识库规则 {} 的 payload 解析失败，按无依据处理: {}",
                rule.getId(), exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 某套评分系统定义了哪些参与计分的列。「NEWS 由哪几项构成」由院内规范（知识库）
     * 决定，不由规划模型决定。
     */
    public List<String> scoringColumns(String scoringSystem) {
        if (scoringSystem == null || scoringSystem.isBlank()) {
            return List.of();
        }
        return knowledgeRuleMapper.findAllEffective().stream()
            .filter(rule -> RuleType.SEVERITY_SCORING.name().equals(rule.getRuleType()))
            .filter(rule -> scoringSystem.equals(rule.getScoringSystem()))
            .map(KnowledgeRuleEntity::getColumnName)
            .distinct()
            .toList();
    }

    /** 生效规则总数，启动自检用，0 条说明种子数据没导入。 */
    public long countEffective() {
        return knowledgeRuleMapper.findAllEffective().size();
    }

    /**
     * 知识库里配了 VALIDITY 规则的全部规范列名，评测的行级检出探针用。返回的是
     * 知识库覆盖的列而非数据集的列。
     */
    public List<String> validityColumns() {
        return knowledgeRuleMapper.findAllEffective().stream()
            .filter(rule -> RuleType.VALIDITY.name().equals(rule.getRuleType()))
            .map(KnowledgeRuleEntity::getColumnName)
            .distinct()
            .toList();
    }
}
