package org.dataagent.clean.pipeline.model.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 「还要不要问医生」的判定，直接决定任务终态是 DONE 还是 CLARIFYING。 */
class CleaningPlanTest {

    @Test
    @DisplayName("HIGH 级自动决定，不进待答队列")
    void highLevelIsNotAsked() {
        CleaningPlan plan = planWith(item("C1", ClarificationLevel.HIGH, null),
            item("C2", ClarificationLevel.LOW, null));

        assertThat(plan.pendingQuestions()).extracting(ClarificationItem::getClarifyCode)
            .containsExactly("C2");
        assertThat(plan.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("答复归一成功后不再算待答，任务才能走到 DONE")
    void resolvedAnswerClearsPending() {
        CleaningPlan plan = planWith(item("C1", ClarificationLevel.LOW, "FILL_NEGATIVE"));

        assertThat(plan.pendingQuestions()).isEmpty();
        assertThat(plan.needsClarification()).isFalse();
    }

    @Test
    @DisplayName("答了但没能归一的仍然算待答：那条答复系统用不上，等于没答")
    void rejectedAnswerStaysPending() {
        ClarificationItem item = item("C1", ClarificationLevel.LOW, null);
        item.setAnswer("你看着办");
        CleaningPlan plan = planWith(item);

        assertThat(plan.pendingQuestions()).hasSize(1);
        assertThat(plan.needsClarification()).isTrue();
    }

    @Test
    @DisplayName("按覆盖率降序排：医生中途停止回答也已覆盖绝大部分数据")
    void sortsByCoverageDescending() {
        ClarificationItem low = item("C-low", ClarificationLevel.LOW, null);
        low.setTopic("意识/MISSING_SEMANTICS");
        low.setCoverageRatio(0.1791);
        ClarificationItem high = item("C-high", ClarificationLevel.LOW, null);
        high.setTopic("升压药/MISSING_SEMANTICS");
        high.setCoverageRatio(0.9911);

        CleaningPlan plan = planWith(low, high);
        plan.sortClarifications();

        assertThat(plan.pendingQuestions()).extracting(ClarificationItem::getClarifyCode)
            .containsExactly("C-high", "C-low");
    }

    private CleaningPlan planWith(ClarificationItem... items) {
        CleaningPlan plan = new CleaningPlan();
        plan.getClarifications().addAll(List.of(items));
        return plan;
    }

    private ClarificationItem item(String code, ClarificationLevel level, String answerAction) {
        ClarificationItem item = new ClarificationItem();
        item.setClarifyCode(code);
        item.setLevel(level);
        item.setTopic(code + "/MISSING_SEMANTICS");
        item.setAnswerAction(answerAction);
        return item;
    }
}
