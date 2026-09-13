package org.dataagent.clean.pipeline.executor;

import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.plan.StepExecution;
import org.dataagent.clean.pipeline.model.profile.ProfileResult;
import org.dataagent.clean.pipeline.model.validate.FindingLevel;
import org.dataagent.clean.pipeline.model.validate.ValidationFinding;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结果视图组装。首轮与澄清恢复轮返回同一种结构，组装逻辑集中在这里，两条链路的
 * 口径不会各写各的。
 */
@Component
public class CleanTaskResultAssembler {

    public void fillProfile(CleanTaskResultVO result, ProfileResult profileResult) {
        ProfileResponse profile = profileResult.profile();
        result.setInputRowCount(profile.getRowCount());
        result.setColumnCount(profile.getColumnCount());
        result.setProfileNarrative(profileResult.narrative());
        for (ProfileResponse.AnomalyPattern pattern : profile.getAnomalyPatterns()) {
            CleanTaskResultVO.AnomalyView view = new CleanTaskResultVO.AnomalyView();
            view.setCode(pattern.getCode());
            view.setColumn(pattern.getColumn());
            view.setLevel(pattern.getLevel());
            view.setEvidence(pattern.getEvidence());
            view.setAffectedRows(pattern.getAffectedRows());
            result.getProfileAnomalies().add(view);
        }
    }

    /** 只填画像的行列数，恢复轮不重跑画像也不重放归纳文本。 */
    public void fillProfileShape(CleanTaskResultVO result, ProfileResponse profile) {
        result.setInputRowCount(profile.getRowCount());
        result.setColumnCount(profile.getColumnCount());
    }

    public void fillPlan(CleanTaskResultVO result, CleaningPlan plan) {
        result.setPlanCode(plan.getPlanCode());
        result.setPlanSummary(plan.getSummary());
        for (PlanStep step : plan.getSteps()) {
            CleanTaskResultVO.StepView view = new CleanTaskResultVO.StepView();
            view.setStepNo(step.getStepNo());
            view.setAction(step.getAction().name());
            view.setDescription(step.getDescription());
            view.setTargetColumns(step.getTargetColumns());
            view.setRuleIds(step.getRuleIds());
            view.setUserDecided(step.isUserDecided());
            view.setExecuted(false);
            view.setSuccess(false);
            view.setRepairCount(0);
            if (!step.isExecutable()) {
                view.setSkipReason(blockedReason(step));
            }
            result.getSteps().add(view);
        }
        // 已按 coverageRatio 降序排好，照搬顺序
        for (ClarificationItem item : plan.pendingQuestions()) {
            result.getClarifications().add(toView(item));
        }
    }

    public CleanTaskResultVO.ClarificationView toView(ClarificationItem item) {
        CleanTaskResultVO.ClarificationView view = new CleanTaskResultVO.ClarificationView();
        view.setClarifyCode(item.getClarifyCode());
        view.setLevel(item.getLevel().name());
        view.setTopic(item.getTopic());
        view.setQuestion(item.getQuestion());
        view.setOptions(item.getOptions());
        view.setOptionCodes(item.getOptionCodes());
        view.setColumnName(item.getColumnName());
        view.setCoverageRatio(item.getCoverageRatio());
        view.setEvidence(item.getEvidence());
        view.setConflictingSources(item.getConflictingSources());
        view.setAnswer(item.getAnswer());
        view.setAnswerAction(item.getAnswerAction());
        return view;
    }

    private String blockedReason(PlanStep step) {
        String ruleLabel = step.getAction().requiredRuleType() == null
            ? "未知" : step.getAction().requiredRuleType().getLabel();
        if (!step.getUnresolvedColumns().isEmpty()) {
            return "「%s」还缺「%s」类临床依据，已转为澄清项，答复后才执行"
                .formatted(String.join("、", step.getUnresolvedColumns()), ruleLabel);
        }
        return "该动作需要「%s」类临床依据，知识库未命中，已转为澄清项".formatted(ruleLabel);
    }

    public void fillExecution(CleanTaskResultVO result, List<StepExecution> executions) {
        int totalRepairs = result.getTotalRepairAttempts() == null
            ? 0 : result.getTotalRepairAttempts();
        int shortCircuited = result.getRepairShortCircuitCount() == null
            ? 0 : result.getRepairShortCircuitCount();
        for (StepExecution execution : executions) {
            totalRepairs += execution.repairCount();
            if (execution.isShortCircuited()) {
                shortCircuited++;
            }
            result.getSteps().stream()
                .filter(view -> view.getStepNo() != null
                    && view.getStepNo() == execution.getStepNo())
                .findFirst()
                .ifPresent(view -> {
                    view.setExecuted(true);
                    view.setSuccess(execution.isSuccess());
                    view.setRepairCount(execution.repairCount());
                    view.setSkipReason(execution.isSuccess() ? null : "执行失败：" + shorten(
                        execution.lastAttempt() == null ? ""
                            : execution.lastAttempt().getResponse().failureText())
                        // 少跑的轮次在结果里说明
                        + (execution.isShortCircuited()
                            ? "｜" + execution.getEarlyStopReason() : ""));
                });
            if (execution.isSuccess()) {
                result.setOutputPath(execution.getOutputPath());
                result.setOutputRowCount(
                    execution.lastAttempt().getResponse().getOutputRowCount());
            }
        }
        result.setTotalRepairAttempts(totalRepairs);
        result.setRepairShortCircuitCount(shortCircuited);
    }

    public void fillValidation(CleanTaskResultVO result, ValidationReport report) {
        for (ValidationFinding finding : report.getFindings()) {
            CleanTaskResultVO.FindingView view = new CleanTaskResultVO.FindingView();
            view.setLevel(finding.getLevel().name());
            view.setCode(finding.getCode());
            view.setColumn(finding.getColumnName());
            view.setSeverity(finding.getSeverity());
            view.setEvidence(finding.getEvidence());
            view.setAffectedRows(finding.getAffectedRows());
            view.setSourceRuleId(finding.getSourceRuleId());
            view.setSourceDoc(finding.getSourceDoc());
            view.setSourceLocator(finding.getSourceLocator());
            result.getFindings().add(view);
        }
        // 把「哪一层跑了、哪一层没跑」原样暴露给调用方
        report.getLevelExecuted().forEach((level, executed) ->
            result.getValidationExecuted().put(level.name(), executed));
        report.getLevelSkipReason().forEach((level, reason) ->
            result.getValidationSkipReason().put(level.name(), reason));
        for (FindingLevel level : FindingLevel.values()) {
            result.getValidationExecuted().putIfAbsent(level.name(), false);
        }
    }

    public String lastSuccessfulOutput(List<StepExecution> executions) {
        String path = null;
        for (StepExecution execution : executions) {
            if (execution.isSuccess()) {
                path = execution.getOutputPath();
            }
        }
        return path;
    }

    public String failReasonOf(List<StepExecution> executions) {
        return executions.stream()
            .filter(execution -> !execution.isSuccess())
            .findFirst()
            .map(execution -> "步骤 %d(%s) 在 %d 次自修复后仍失败%s：%s".formatted(
                execution.getStepNo(), execution.getAction(), execution.repairCount(),
                execution.isShortCircuited() ? "（自修复不收敛，已提前终止）" : "",
                execution.lastAttempt() == null ? "无尝试记录"
                    : shorten(execution.lastAttempt().getResponse().failureText())))
            .orElse(null);
    }

    private String shorten(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= 300 ? single : single.substring(0, 300) + "...";
    }
}
