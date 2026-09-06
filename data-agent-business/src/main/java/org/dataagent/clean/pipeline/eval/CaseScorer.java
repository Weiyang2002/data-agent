package org.dataagent.clean.pipeline.eval;

import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.clean.toolsclient.model.DatasetGenerateResponse;
import org.dataagent.clean.toolsclient.model.EvalCasesResponse;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单用例评分
 */
@Component
public class CaseScorer {

    /** 沙箱静态检查拦截的固定话术，见 Python 侧 static_check.StaticCheckResult.reason */
    private static final String BLOCKED_MARK = "静态检查未通过";

    private final DefectCodeMatcher matcher;

    public CaseScorer(DefectCodeMatcher matcher) {
        this.matcher = matcher;
    }

    /**
     * @param evalCase        用例定义
     * @param golden          该用例数据集的 golden
     * @param result          链路返回；链路抛异常时为 null
     * @param beforeRowProbe  处理前的行级探测结果（见 {@code EvalRunner} 的说明）
     * @param afterResidual   处理后仍被检出的「码@列」键
     * @param error           链路异常，正常时为 null
     */
    public CaseScore score(EvalCasesResponse.EvalCase evalCase,
                           DatasetGenerateResponse golden,
                           CleanTaskResultVO result,
                           List<DefectCodeMatcher.Signal> beforeRowProbe,
                           Set<String> afterResidual,
                           String error,
                           long costMillis) {

        CaseScore score = new CaseScore();
        score.setCaseId(evalCase.getCaseId());
        score.setCaseLevel(evalCase.getLevel());
        score.setClarifyExpected(Boolean.TRUE.equals(evalCase.getExpectClarify()));
        score.setCostMillis(costMillis);
        score.setProactiveExpected(evalCase.getExpectProactiveReport() == null
            ? List.of() : evalCase.getExpectProactiveReport());

        if (result == null) {
            // 链路异常：其余判据全部不成立，全记 0 并归因 PIPELINE_ERROR
            score.setFailureCategory(FailureCategory.PIPELINE_ERROR);
            score.setFailureDetail(error);
            golden.getInjected().forEach(item -> {
                score.getMissed().add(item.getCode());
                score.getSamples().add(new CaseScore.DefectSample(
                    item.getCode(), item.getExpectLevel(),
                    Boolean.TRUE.equals(item.getUserAsked()), false));
            });
            return score;
        }

        score.setTaskCode(result.getTaskCode());
        score.setRepairAttempts(result.getTotalRepairAttempts() == null
            ? 0 : result.getTotalRepairAttempts());
        score.setRepairShortCircuited(result.getRepairShortCircuitCount() == null
            ? 0 : result.getRepairShortCircuitCount());

        // ── 检出 ──
        DefectCodeMatcher.DetectionView detection = matcher.detect(result, beforeRowProbe);
        detection.logSummary(evalCase.getCaseId());
        score.setDetected(detection.codes());
        score.setUnmatchedSuspicions(detection.unmatchedSuspicions());
        score.setBaselineInherent(detection.baselineInherentCodes());

        Set<String> expected = new LinkedHashSet<>();
        for (DatasetGenerateResponse.InjectedDefect item : golden.getInjected()) {
            expected.add(item.getCode());
            boolean hit = detection.hit(item.getCode(), item.getColumns());
            score.getSamples().add(new CaseScore.DefectSample(
                item.getCode(), item.getExpectLevel(),
                Boolean.TRUE.equals(item.getUserAsked()), hit));
            if (!hit) {
                score.getMissed().add(item.getCode());
            }
        }
        detection.codes().stream()
            .filter(code -> !expected.contains(code))
            // 基线自带的信号不算误报，见 DefectCodeMatcher.BASELINE_INHERENT
            .filter(code -> !detection.baselineInherentCodes().contains(code))
            .forEach(score.getFalseAlarm()::add);

        score.getProactiveExpected().stream()
            .filter(detection.codes()::contains)
            .forEach(score.getProactiveHit()::add);

        // ── 执行 ──
        List<CleanTaskResultVO.StepView> executed = result.getSteps().stream()
            .filter(step -> Boolean.TRUE.equals(step.getExecuted()))
            .toList();
        score.setExecAttempted(!executed.isEmpty());
        score.setExecSuccess(!executed.isEmpty()
            && executed.stream().allMatch(step -> Boolean.TRUE.equals(step.getSuccess())));

        // ── 澄清 ──
        score.setClarifyActual(!result.getClarifications().isEmpty());
        result.getClarifications().forEach(item ->
            score.getClarifyTopics().add(item.getTopic()));

        // ── 结果正确性 ──
        Set<String> target = targetKeys(evalCase, golden);
        score.setTargetCodes(target);
        target.stream().filter(afterResidual::contains).forEach(score.getResidualTargets()::add);

        score.setResultScorable(!score.isClarifyExpected());
        score.setResultCorrect(score.isResultScorable()
            && score.isExecSuccess()
            && result.getOutputPath() != null
            && score.getResidualTargets().isEmpty());

        attribute(score, result);
        return score;
    }

    /**
     * 本用例需求真正指向的缺陷，展开成「码@列」：用例声明要注入的缺陷 ∩ golden
     * 里 userAsked=true 的缺陷。没有列语义的缺陷（整行重复）用通配列。
     */
    private Set<String> targetKeys(EvalCasesResponse.EvalCase evalCase,
                                   DatasetGenerateResponse golden) {
        Set<String> declared = new LinkedHashSet<>();
        if (evalCase.getDefects() != null) {
            evalCase.getDefects().forEach(spec -> declared.add(spec.getCode()));
        }

        Set<String> keys = new LinkedHashSet<>();
        for (DatasetGenerateResponse.InjectedDefect item : golden.getInjected()) {
            if (!Boolean.TRUE.equals(item.getUserAsked()) || !declared.contains(item.getCode())) {
                continue;
            }
            if (item.getColumns() == null || item.getColumns().isEmpty()) {
                keys.add(item.getCode() + "@" + DefectCodeMatcher.ANY_COLUMN);
            }
            else {
                item.getColumns().forEach(column -> keys.add(item.getCode() + "@" + column));
            }
        }
        return keys;
    }

    /** 归因。顺序即优先级，见 {@link FailureCategory}。 */
    private void attribute(CaseScore score, CleanTaskResultVO result) {
        if (score.isClarifyExpected() && !score.isClarifyActual()) {
            score.setFailureCategory(FailureCategory.CLARIFY_MISSING);
            score.setFailureDetail("golden 要求澄清，系统直接做了决定；方案摘要："
                + result.getPlanSummary());
            return;
        }
        if (!score.isClarifyExpected() && score.isClarifyActual()) {
            score.setFailureCategory(FailureCategory.CLARIFY_EXCESS);
            score.setFailureDetail("不该问却问了：" + score.getClarifyTopics());
            return;
        }
        if (score.isExecAttempted() && !score.isExecSuccess()) {
            boolean blocked = result.getSteps().stream()
                .map(CleanTaskResultVO.StepView::getSkipReason)
                .anyMatch(reason -> reason != null && reason.contains(BLOCKED_MARK));
            score.setFailureCategory(blocked
                ? FailureCategory.SANDBOX_BLOCKED : FailureCategory.CODEGEN_FAILED);
            score.setFailureDetail(result.getFailReason());
            return;
        }
        if (score.isClarifyExpected()) {
            // 必须排在「没有可执行步骤」前：澄清类用例的正确行为就是停下提问
            score.setFailureCategory(FailureCategory.CLARIFY_ONLY);
            score.setFailureDetail("澄清正确触发：" + score.getClarifyTopics());
            return;
        }
        if (result.getSteps().isEmpty()) {
            score.setFailureCategory(FailureCategory.UNSUPPORTED_ACTION);
            score.setFailureDetail("规划未产出任何步骤，需求落不进 PlanAction 词表；摘要："
                + result.getPlanSummary());
            return;
        }
        if (!score.isExecAttempted()) {
            score.setFailureCategory(FailureCategory.UNSUPPORTED_ACTION);
            score.setFailureDetail("有步骤但一步都没执行；未执行原因："
                + result.getSteps().stream()
                    .map(CleanTaskResultVO.StepView::getSkipReason).toList());
            return;
        }
        if (!score.isResultCorrect()) {
            score.setFailureCategory(FailureCategory.RESULT_MISMATCH);
            score.setFailureDetail("处理后目标缺陷仍被检出：" + score.getResidualTargets()
                + "（目标 " + score.getTargetCodes() + "）");
            return;
        }
        if (!score.getProactiveExpected().isEmpty()
            && score.getProactiveHit().size() < score.getProactiveExpected().size()) {
            score.setFailureCategory(FailureCategory.DISCOVERY_MISS);
            score.setFailureDetail("应主动报告 " + score.getProactiveExpected()
                + "，实际报告 " + score.getProactiveHit());
            return;
        }
        score.setFailureCategory(FailureCategory.OK);
    }
}
