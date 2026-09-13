package org.dataagent.clean.pipeline.model.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 还没拿到依据的目标列，分流时写入、澄清答复落地时移除。
     *
     * <p>只看 {@link #ruleIds} 非空是不够的：一步涉及多列时，一列查到依据就会让
     * 整步「看起来有依据」，另一列的参数其实是缺的。
     */
    private Set<String> unresolvedColumns = new LinkedHashSet<>();

    /** 该步骤的依据来自医生答复而非知识库，报告里要与规范依据分开陈述 */
    private boolean userDecided;

    public boolean hasEvidence() {
        return !ruleIds.isEmpty() || userDecided;
    }

    /** 需要临床依据却一条都没有、或还有列悬着的步骤不允许执行 */
    public boolean isExecutable() {
        if (action == PlanAction.UNKNOWN) {
            return false;
        }
        if (!action.needsClinicalEvidence()) {
            return true;
        }
        return hasEvidence() && unresolvedColumns.isEmpty();
    }
}
