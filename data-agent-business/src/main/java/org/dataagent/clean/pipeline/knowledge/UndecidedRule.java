package org.dataagent.clean.pipeline.knowledge;

import java.util.List;

/**
 * 命中了唯一一条规则，但那条规则本身记录的是一个决策点而非结论。
 *
 * <p>{@link KnowledgeLookup} 的三态按命中条数判定，「命中 1 条」不等于「有唯一
 * 答案」：规则的 {@code recommendedAction} 可能写成 {@code A_OR_B}
 * （如缺失值「插值 或 标记为缺失」），选哪个取决于研究设计。歧义既存在于规则之间，
 * 也存在于规则内部，后者需读 payload 才能发现。
 *
 * @param ruleId    该规则主键，澄清项要引用它作为依据
 * @param column    规范列名
 * @param options   规则自己列出的候选动作
 * @param rationale 规则里写的理由，直接给医生看
 */
public record UndecidedRule(Long ruleId, String column,
                            List<String> options, String rationale) {
}
