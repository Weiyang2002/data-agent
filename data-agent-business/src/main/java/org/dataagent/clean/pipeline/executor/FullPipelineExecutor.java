package org.dataagent.clean.pipeline.executor;

import org.dataagent.clean.pipeline.agent.AgentContext;
import org.dataagent.clean.pipeline.agent.ExecutorAgent;
import org.dataagent.clean.pipeline.agent.PlannerAgent;
import org.dataagent.clean.pipeline.agent.ProfilerAgent;
import org.dataagent.clean.pipeline.agent.ValidationInput;
import org.dataagent.clean.pipeline.agent.ValidatorAgent;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.ExecutionInput;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.plan.StepExecution;
import org.dataagent.clean.pipeline.model.profile.ProfileResult;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.model.validate.FindingLevel;
import org.dataagent.clean.pipeline.model.validate.ValidationFinding;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.service.TaskInfo;
import org.dataagent.clean.pipeline.service.TaskPersistence;
import org.dataagent.clean.pipeline.service.TraceRecorder;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 完整链路：Profiler → Planner → Executor → Validator。
 *
 * <p>顺序调用而非图编排：四个阶段之间有业务分支（需澄清则停、某步失败不继续），
 * 写成 Java 的 if 更直观。阶段埋点由各 Agent 的 {@code run()} 上的 {@code @TraceStage}
 * 注解 + AOP 承担，本类只剩业务调用；{@code traceRecorder.skip(...)} 因为是业务判断
 * 留在本类。
 *
 * <p>链路的四个约束：需澄清时无歧义步骤照跑、某步失败停下如实报告、没有产出时
 * 标记三层「未执行」、任何降级不静默。
 */
@Component
public class FullPipelineExecutor implements PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(FullPipelineExecutor.class);

    private final ProfilerAgent profilerAgent;
    private final PlannerAgent plannerAgent;
    private final ExecutorAgent executorAgent;
    private final ValidatorAgent validatorAgent;
    private final TraceRecorder traceRecorder;
    private final TaskPersistence persistence;

    public FullPipelineExecutor(ProfilerAgent profilerAgent,
                                PlannerAgent plannerAgent,
                                ExecutorAgent executorAgent,
                                ValidatorAgent validatorAgent,
                                TraceRecorder traceRecorder,
                                TaskPersistence persistence) {
        this.profilerAgent = profilerAgent;
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.validatorAgent = validatorAgent;
        this.traceRecorder = traceRecorder;
        this.persistence = persistence;
    }

    @Override
    public PipelineMode mode() {
        return PipelineMode.FULL;
    }

    @Override
    public CleanTaskResultVO execute(TaskInfo taskInfo) {
        AgentContext context = taskInfo.toAgentContext();
        CleanTaskResultVO result = new CleanTaskResultVO();
        result.setTaskCode(taskInfo.getTaskCode());
        result.setTraceId(taskInfo.getTraceId());

        // ── 1. 画像 ──
        ProfileResult profileResult = profilerAgent.run(context, taskInfo.getDatasetPath());
        ProfileResponse profile = profileResult.profile();
        persistence.saveDataset(taskInfo, profile);
        persistence.saveColumnProfiles(taskInfo, profile, "BEFORE");
        persistence.saveProfileAnomalies(taskInfo, profile);
        fillProfileView(result, profileResult);
        persistence.updateStatus(taskInfo, "PROFILING", null);

        // ── 2. 规划 + 澄清分流 ──
        // 知识库检索是分流的一部分，不单独埋点
        CleaningPlan plan = plannerAgent.run(context, profile);
        persistence.savePlan(taskInfo, plan);
        fillPlanView(result, plan);

        if (plan.needsClarification()) {
            // 有问题要问，但无歧义的步骤照跑
            traceRecorder.skip(taskInfo, TraceStageCode.CLARIFY,
                "产生 " + plan.pendingQuestions().size() + " 个澄清项，等待医生答复");
            persistence.updateStatus(taskInfo, "CLARIFYING", null);
        }

        if (plan.executableSteps().isEmpty()) {
            // 全部步骤都在等澄清，是正常结局
            log.info("任务 {} 没有可直接执行的步骤，全部待澄清", taskInfo.getTaskCode());
            traceRecorder.skip(taskInfo, TraceStageCode.SANDBOX, "无可执行步骤，全部待澄清");
            ValidationReport empty = validatorAgent.run(context,
                new ValidationInput(profile, null, plan));
            fillValidationView(result, empty);
            result.setStatus(plan.needsClarification() ? "CLARIFYING" : "DONE");
            persistence.updateStatus(taskInfo, result.getStatus(), null);
            return result;
        }

        // ── 3. 代码生成 + 沙箱执行（含有界自修复）──
        persistence.updateStatus(taskInfo, "EXECUTING", null);
        List<StepExecution> executions =
            executorAgent.run(context, new ExecutionInput(plan, profile));
        persistence.saveExecutions(taskInfo, plan.getPlanCode(),
            executions, taskInfo.getDatasetPath());
        fillExecutionView(result, plan, executions);

        String outputPath = lastSuccessfulOutput(executions);

        // ── 4. 三层校验 ──
        persistence.updateStatus(taskInfo, "VALIDATING", null);
        ValidationReport report =
            validatorAgent.run(context, new ValidationInput(profile, outputPath, plan));
        persistence.saveFindings(taskInfo, report, "AFTER");
        fillValidationView(result, report);

        boolean allSucceeded = executions.stream().allMatch(StepExecution::isSuccess);
        if (!allSucceeded) {
            result.setStatus("FAILED");
            result.setFailReason(failReasonOf(executions));
        }
        else {
            result.setStatus(plan.needsClarification() ? "CLARIFYING" : "DONE");
        }
        persistence.updateStatus(taskInfo, result.getStatus(), result.getFailReason());
        traceRecorder.mark(taskInfo, TraceStageCode.FINALIZE, "链路跑到终点，状态 " + result.getStatus());
        return result;
    }

    // ── 视图组装 ──

    private void fillProfileView(CleanTaskResultVO result, ProfileResult profileResult) {
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

    private void fillPlanView(CleanTaskResultVO result, CleaningPlan plan) {
        result.setPlanCode(plan.getPlanCode());
        result.setPlanSummary(plan.getSummary());
        for (PlanStep step : plan.getSteps()) {
            CleanTaskResultVO.StepView view = new CleanTaskResultVO.StepView();
            view.setStepNo(step.getStepNo());
            view.setAction(step.getAction().name());
            view.setDescription(step.getDescription());
            view.setTargetColumns(step.getTargetColumns());
            view.setRuleIds(step.getRuleIds());
            view.setExecuted(false);
            view.setSuccess(false);
            view.setRepairCount(0);
            if (!step.isExecutable()) {
                // 说清楚为什么没执行
                view.setSkipReason("该动作需要「%s」类临床依据，知识库未命中，已转为澄清项"
                    .formatted(step.getAction().requiredRuleType() == null
                        ? "未知" : step.getAction().requiredRuleType().getLabel()));
            }
            result.getSteps().add(view);
        }
        // 已按 coverageRatio 降序排好，照搬顺序
        for (ClarificationItem item : plan.pendingQuestions()) {
            CleanTaskResultVO.ClarificationView view = new CleanTaskResultVO.ClarificationView();
            view.setClarifyCode(item.getClarifyCode());
            view.setLevel(item.getLevel().name());
            view.setTopic(item.getTopic());
            view.setQuestion(item.getQuestion());
            view.setOptions(item.getOptions());
            view.setColumnName(item.getColumnName());
            view.setCoverageRatio(item.getCoverageRatio());
            view.setEvidence(item.getEvidence());
            view.setConflictingSources(item.getConflictingSources());
            result.getClarifications().add(view);
        }
    }

    private void fillExecutionView(CleanTaskResultVO result, CleaningPlan plan,
                                   List<StepExecution> executions) {
        int totalRepairs = 0;
        int shortCircuited = 0;
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
                    if (!execution.isSuccess() && execution.lastAttempt() != null) {
                        view.setSkipReason("执行失败：" + shorten(
                            execution.lastAttempt().getResponse().failureText())
                            // 少跑的轮次在结果里说明
                            + (execution.isShortCircuited()
                                ? "｜" + execution.getEarlyStopReason() : ""));
                    }
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

    private void fillValidationView(CleanTaskResultVO result, ValidationReport report) {
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

    private String lastSuccessfulOutput(List<StepExecution> executions) {
        String path = null;
        for (StepExecution execution : executions) {
            if (execution.isSuccess()) {
                path = execution.getOutputPath();
            }
        }
        return path;
    }

    private String failReasonOf(List<StepExecution> executions) {
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
