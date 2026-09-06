package org.dataagent.clean.pipeline.model.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 方案中的一步。 */
@Data
public class PlanStep {

    private int stepNo;

    private PlanAction action;

    /** 已经过 ColumnResolver 对齐的规范列名 */
    private List<String> targetColumns = new ArrayList<>();

    /** 给医生看的一句话说明 */
    private String description;

    /**
     * 该步骤依据的知识库规则 ID。空 = 无临床依据，对纯工程动作正常，对需要依据的
     * 动作则意味着这一步不该执行（应已在分流阶段变成澄清项）。
     */
    private List<Long> ruleIds = new ArrayList<>();

    /**
     * 执行参数，阈值走这里不走 Prompt。原样进 {@code /execute} 的 params，在沙箱
     * 运行时注入 {@code clean(df, params)}。
     */
    private Map<String, Object> params = new LinkedHashMap<>();

    /**
     * 本步骤采用的评分系统（NEWS / MEWS / …），只有 COMPUTE_SCORE 会有值。来源是需求
     * 原文的确定性匹配（见 {@code ScoringSystemResolver}），进 Prompt 只用于给输出列
     * 起名，不携带阈值。
     */
    private String scoringSystem;

    /** 为 DAG 化预留，当前顺序执行，恒为空 */
    private List<Integer> dependsOn = new ArrayList<>();

    public boolean hasEvidence() {
        return !ruleIds.isEmpty();
    }

    /** 需要临床依据却一条都没有的步骤不允许执行 */
    public boolean isExecutable() {
        if (action == PlanAction.UNKNOWN) {
            return false;
        }
        return !action.needsClinicalEvidence() || hasEvidence();
    }
}
