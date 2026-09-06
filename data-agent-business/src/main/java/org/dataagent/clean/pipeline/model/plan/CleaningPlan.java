package org.dataagent.clean.pipeline.model.plan;

import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** PlannerAgent 的产出：可执行步骤 + 待澄清项。 */
@Data
public class CleaningPlan {

    private String planCode;

    /** 给医生看的一句话方案说明 */
    private String summary;

    private List<PlanStep> steps = new ArrayList<>();

    private List<ClarificationItem> clarifications = new ArrayList<>();

    /**
     * 按覆盖率降序排澄清项。排序规则是业务决定，放在领域对象上而非 SQL，换存储不丢。
     */
    public void sortClarifications() {
        clarifications.sort(Comparator
            .comparingDouble(ClarificationItem::getCoverageRatio).reversed()
            .thenComparing(ClarificationItem::getTopic));
    }

    /** 真正要问医生的项。HIGH 级已经自动决定了，不打扰医生 */
    public List<ClarificationItem> pendingQuestions() {
        return clarifications.stream()
            .filter(item -> item.getLevel().needsAsking())
            .toList();
    }

    /**
     * 本轮可以直接执行的步骤。
     *
     * <p>需要临床依据却没查到的步骤不在其中：它们已转为澄清项，等医生回答后才能补
     * 参数执行。无歧义的步骤（如去重）不等澄清答复，照常执行。
     */
    public List<PlanStep> executableSteps() {
        return steps.stream().filter(PlanStep::isExecutable).toList();
    }

    public boolean needsClarification() {
        return !pendingQuestions().isEmpty();
    }
}
