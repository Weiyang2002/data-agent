package org.dataagent.clean.pipeline.knowledge;

import java.util.List;
import java.util.Map;

/**
 * SEVERITY_SCORING 规则的 payload 解析结果。装配进 params 后 Python 的
 * {@code require_params} 检查才能生效，阻止模型把分档凭记忆写死在代码里。
 *
 * <p>两种形状共用一个类型：
 * <pre>
 *   数值列（体温/心率/呼吸/血氧/收缩压）
 *     bands:    [{min, max, score}, ...]   命中第一个满足 min&lt;=v&lt;=max 的档
 *   文本列（意识/氧疗）
 *     mappings: [{category, score}, ...]   按取值查表
 * </pre>
 *
 * @param ruleId        知识库规则主键，一路带到医生面前
 * @param column        规范列名
 * @param scoringSystem NEWS / MEWS / SEWS / CART
 * @param bands         数值分档；文本规则为 null
 * @param mappings      取值映射；数值规则为 null
 * @param unit          单位，只用于人类可读的说明
 * @param sourceDoc     来源文档
 * @param sourceLocator 文档内定位
 */
public record ScoringRule(
    Long ruleId,
    String column,
    String scoringSystem,
    List<Map<String, Object>> bands,
    List<Map<String, Object>> mappings,
    String unit,
    String sourceDoc,
    String sourceLocator
) {

    /** 参数通道用的键。分档里的数值都走这个键进沙箱，不进 Prompt。 */
    public String paramKey() {
        return column + (bands != null ? "_bands" : "_mappings");
    }

    public Object paramValue() {
        return bands != null ? bands : mappings;
    }

    public boolean isUsable() {
        return (bands != null && !bands.isEmpty()) || (mappings != null && !mappings.isEmpty());
    }
}
