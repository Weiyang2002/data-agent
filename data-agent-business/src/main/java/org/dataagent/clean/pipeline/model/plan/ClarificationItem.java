package org.dataagent.clean.pipeline.model.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个需要医生判断的决策点。澄清项按 {@link #coverageRatio} 降序输出，医生即使
 * 中途停止回答，也已覆盖绝大部分数据。
 */
@Data
public class ClarificationItem {

    private String clarifyCode;

    private ClarificationLevel level;

    /**
     * 澄清主题。与 golden 的 {@code expectClarifications} 比对时用，必须稳定可枚举。
     * 措辞的自由度留给 {@link #question}。
     */
    private String topic;

    /** 给医生看的问题措辞，允许模型发挥（temperature 0.5） */
    private String question;

    /** 候选答案，尽量让医生做选择题而不是问答题 */
    private List<String> options = new ArrayList<>();

    private String columnName;

    /** 该决策点覆盖的数据占比，提问排序依据 */
    private double coverageRatio;

    /** 为什么要问，来自画像的确定性证据 */
    private String evidence;

    /** 歧义澄清时冲突的规则 ID */
    private List<Long> sourceRuleIds = new ArrayList<>();

    /** 歧义澄清时，冲突来源的可读描述（如「NEWS（NEWS2 2017 表x）」） */
    private List<String> conflictingSources = new ArrayList<>();

    private String answer;
}
