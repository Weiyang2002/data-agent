package org.dataagent.clean.pipeline.knowledge;

import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;
import org.dataagent.clean.pipeline.mapper.KnowledgeRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 从医生的需求原文里识别出已指定的评分系统，作为知识库检索的限定词，避免同列
 * SEVERITY_SCORING 命中多套标准而逐列触发澄清。
 *
 * <p>用确定性匹配而非 LLM 抽取：候选集是闭的（知识库 {@code scoring_system}
 * 列的 distinct 值），且澄清数量是要收敛的指标，输入端不能有随机性。
 *
 * <p>三种结局：
 * <pre>
 *   命中 1 个   → 用它做限定词
 *   命中 0 个   → 不加限定词，维持原样
 *   命中 &gt;1 个  → 不加限定词，属真歧义，交下游照常澄清
 * </pre>
 * 本类只消除不存在的歧义，不消除真歧义。
 */
@Component
public class ScoringSystemResolver {

    private static final Logger log = LoggerFactory.getLogger(ScoringSystemResolver.class);

    private final KnowledgeRuleMapper knowledgeRuleMapper;

    public ScoringSystemResolver(KnowledgeRuleMapper knowledgeRuleMapper) {
        this.knowledgeRuleMapper = knowledgeRuleMapper;
    }

    /**
     * @param requirement 医生的需求原文
     * @return 需求里唯一提到的评分系统；没提到或提到多个都返回 empty
     */
    public Optional<String> resolve(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return Optional.empty();
        }
        String upper = requirement.toUpperCase();

        Set<String> mentioned = new LinkedHashSet<>();
        for (String system : knownSystems()) {
            if (upper.contains(system.toUpperCase())) {
                mentioned.add(system);
            }
        }

        if (mentioned.size() == 1) {
            String system = mentioned.iterator().next();
            log.info("需求中已指定评分系统「{}」，检索将带上这个限定词", system);
            return Optional.of(system);
        }
        if (mentioned.size() > 1) {
            // 真歧义，不挑，让下游照常触发澄清
            log.info("需求中同时提到 {} 套评分系统 {}，属真歧义，不加限定词", mentioned.size(), mentioned);
        }
        return Optional.empty();
    }

    /**
     * 知识库里出现过的全部评分系统。词表从数据来，不写死在代码里。非评分规则的
     * {@code scoring_system} 是空串，在这里过滤掉。
     */
    private List<String> knownSystems() {
        return knowledgeRuleMapper.findAllEffective().stream()
            .map(KnowledgeRuleEntity::getScoringSystem)
            .filter(system -> system != null && !system.isBlank())
            .distinct()
            .toList();
    }
}
