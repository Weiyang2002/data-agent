package org.dataagent.clean.pipeline.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;
import org.dataagent.clean.pipeline.mapper.KnowledgeRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 列名对齐：把数据集列名或医生的自然语言说法映射到知识库的规范列名。
 *
 */
@Component
public class ColumnResolver {

    private static final Logger log = LoggerFactory.getLogger(ColumnResolver.class);

    private final KnowledgeRuleMapper knowledgeRuleMapper;
    private final ObjectMapper objectMapper;

    /**
     * 别名 → 规范列名，首次使用时构建。用 AtomicReference 惰性加载而非
     * {@code @PostConstruct}，使 MySQL 暂时不可用时服务仍能启动做 Prompt 调试。
     */
    private final AtomicReference<Map<String, String>> aliasIndex = new AtomicReference<>();

    public ColumnResolver(KnowledgeRuleMapper knowledgeRuleMapper, ObjectMapper objectMapper) {
        this.knowledgeRuleMapper = knowledgeRuleMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 归一到规范列名。
     *
     * @return 规范列名；匹配不上返回 {@link Optional#empty()}，不猜测
     */
    public Optional<String> resolve(String rawColumn) {
        if (rawColumn == null || rawColumn.isBlank()) {
            return Optional.empty();
        }
        String probe = normalize(rawColumn);
        String canonical = index().get(probe);
        if (canonical == null) {
            log.debug("列名未能对齐到知识库规范列名: {}", rawColumn);
        }
        return Optional.ofNullable(canonical);
    }

    /** 知识库里是否存在这个规范列名。 */
    public boolean isKnownColumn(String canonicalColumn) {
        return canonicalColumn != null && index().containsValue(canonicalColumn);
    }

    /** 规则变更后清空缓存。 */
    public void refresh() {
        aliasIndex.set(null);
    }

    private Map<String, String> index() {
        Map<String, String> current = aliasIndex.get();
        if (current != null) {
            return current;
        }
        Map<String, String> built = build();
        aliasIndex.compareAndSet(null, built);
        return built;
    }

    private Map<String, String> build() {
        Map<String, String> index = new HashMap<>();
        List<KnowledgeRuleEntity> rules = knowledgeRuleMapper.findAllEffective();
        for (KnowledgeRuleEntity rule : rules) {
            String canonical = rule.getColumnName();
            index.put(normalize(canonical), canonical);
            for (String alias : parseAliases(rule)) {
                // 别名冲突时保留先到的，并记 WARN（同一别名指向两个规范列名属知识库录入错误）
                String existing = index.putIfAbsent(normalize(alias), canonical);
                if (existing != null && !existing.equals(canonical)) {
                    log.warn("知识库别名冲突：别名 [{}] 同时指向 [{}] 与 [{}]，"
                        + "已保留 [{}]。请检查 knowledge_rule_seed.sql", alias, existing, canonical, existing);
                }
            }
        }
        log.info("列名别名索引构建完成，规则 {} 条，别名 {} 个", rules.size(), index.size());
        return Map.copyOf(index);
    }

    private List<String> parseAliases(KnowledgeRuleEntity rule) {
        String json = rule.getColumnAlias();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        }
        catch (Exception exception) {
            log.warn("规则 {} 的 column_alias 解析失败，已忽略其别名: {}",
                rule.getId(), exception.getMessage());
            return List.of();
        }
    }

    /**
     * 归一化：去空白、全角括号转半角、转小写。只做这三种确定性变换，不做同义词
     * 扩展和拼写纠错。
     */
    private String normalize(String value) {
        return value.trim()
            .replace("（", "(")
            .replace("）", ")")
            .replace(" ", "")
            .toLowerCase();
    }
}
