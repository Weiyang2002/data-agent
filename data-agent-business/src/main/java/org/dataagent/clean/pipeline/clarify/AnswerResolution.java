package org.dataagent.clean.pipeline.clarify;

import org.dataagent.clean.pipeline.knowledge.RuleType;

import java.util.List;
import java.util.Map;

/**
 * 一条澄清答复的归一结果。
 *
 * <p>{@code rejectReason} 非空表示这条答复没能映射成确定性参数，对应的步骤仍然不
 * 执行。答复照样落库，但不产生任何执行动作 —— 「记下了」和「能执行了」是两件事。
 *
 * @param optionCode      归一后的机器码：{@code RULE:<id>} 选中某条规则，或
 *                        {@link ClarifyAction} 的枚举名
 * @param ruleIds         本次采纳的知识库依据；医生自决时为空
 * @param params          要并进 {@code PlanStep.params} 的执行参数
 * @param note            要追加到步骤说明里的一句话，由代码拼，不经模型
 * @param knowledgeBacked 依据来自知识库还是医生自决，报告里两者不能混为一谈
 */
public record AnswerResolution(String clarifyCode,
                               String topic,
                               String columnName,
                               RuleType ruleType,
                               String answer,
                               String optionCode,
                               List<Long> ruleIds,
                               Map<String, Object> params,
                               String note,
                               boolean knowledgeBacked,
                               String rejectReason) {

    public boolean isResolved() {
        return rejectReason == null;
    }

    public static AnswerResolution rejected(String clarifyCode, String topic,
                                            String columnName, RuleType ruleType,
                                            String answer, String reason) {
        return new AnswerResolution(clarifyCode, topic, columnName, ruleType, answer,
            null, List.of(), Map.of(), null, false, reason);
    }
}
