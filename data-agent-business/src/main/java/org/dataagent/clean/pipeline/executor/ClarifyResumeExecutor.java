package org.dataagent.clean.pipeline.executor;

import org.dataagent.clean.pipeline.agent.AgentContext;
import org.dataagent.clean.pipeline.agent.ExecutorAgent;
import org.dataagent.clean.pipeline.agent.ValidationInput;
import org.dataagent.clean.pipeline.agent.ValidatorAgent;
import org.dataagent.clean.pipeline.clarify.AnswerResolution;
import org.dataagent.clean.pipeline.clarify.ClarificationResolver;
import org.dataagent.clean.pipeline.clarify.ClarificationStore;
import org.dataagent.clean.pipeline.clarify.TaskCheckpoint;
import org.dataagent.clean.pipeline.clarify.TaskCheckpointStore;
import org.dataagent.clean.pipeline.model.plan.ClarificationItem;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.ExecutionInput;
import org.dataagent.clean.pipeline.model.plan.StepExecution;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.model.validate.ValidationReport;
import org.dataagent.clean.pipeline.service.TaskInfo;
import org.dataagent.clean.pipeline.service.TaskPersistence;
import org.dataagent.clean.pipeline.service.TraceRecorder;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.dataagent.common.exception.BusinessException;
import org.dataagent.common.result.BaseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 澄清中断后续跑：医生答复 → 归一成执行参数 → 从 checkpoint 接着跑。
 *
 * <p>「接着跑」不是「重跑」：画像与方案从 checkpoint 读回，首轮已经成功的步骤不重
 * 做，本轮的输入是首轮的产出。因此恢复一轮的模型调用只有代码生成那几次，画像与规
 * 划两次调用（M5 实测占 Token 的 63.5%）一次都不发生。
 *
 * <p>答复归一是确定性的（见 {@code ClarificationResolver}），归一不出来的答复照样落
 * 库，但对应步骤保持不执行并在结果里写明原因 —— 「医生答了」和「系统用得上」是两
 * 件事。
 */
@Component
public class ClarifyResumeExecutor implements PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(ClarifyResumeExecutor.class);

    private final ExecutorAgent executorAgent;
    private final ValidatorAgent validatorAgent;
    private final ClarificationStore clarificationStore;
    private final ClarificationResolver resolver;
    private final TaskCheckpointStore checkpointStore;
    private final TraceRecorder traceRecorder;
    private final TaskPersistence persistence;
    private final CleanTaskResultAssembler assembler;

    public ClarifyResumeExecutor(ExecutorAgent executorAgent,
                                 ValidatorAgent validatorAgent,
                                 ClarificationStore clarificationStore,
                                 ClarificationResolver resolver,
                                 TaskCheckpointStore checkpointStore,
                                 TraceRecorder traceRecorder,
                                 TaskPersistence persistence,
                                 CleanTaskResultAssembler assembler) {
        this.executorAgent = executorAgent;
        this.validatorAgent = validatorAgent;
        this.clarificationStore = clarificationStore;
        this.resolver = resolver;
        this.checkpointStore = checkpointStore;
        this.traceRecorder = traceRecorder;
        this.persistence = persistence;
        this.assembler = assembler;
    }

    @Override
    public PipelineMode mode() {
        return PipelineMode.CLARIFY_RESUME;
    }

    @Override
    public CleanTaskResultVO execute(TaskInfo taskInfo) {
        TaskCheckpoint checkpoint = checkpointStore.load(taskInfo.getTaskCode())
            .orElseThrow(() -> new BusinessException(BaseCode.PARAM_INVALID,
                "任务 " + taskInfo.getTaskCode() + " 没有可恢复的检查点"));

        CleaningPlan plan = checkpoint.plan();
        ProfileResponse profile = checkpoint.profile();

        CleanTaskResultVO result = new CleanTaskResultVO();
        result.setTaskCode(taskInfo.getTaskCode());
        result.setTraceId(taskInfo.getTraceId());
        assembler.fillProfileShape(result, profile);

        // ── 1. 答复归一（确定性，零模型调用）──
        List<AnswerResolution> resolutions = resolveAnswers(taskInfo, plan, profile);
        int unblocked = resolver.applyToPlan(plan, resolutions);
        recordClarifyStage(taskInfo, resolutions, unblocked);
        fillOutcomes(result, resolutions);

        AgentContext context = taskInfo.toAgentContext();
        // 本轮从首轮的产出接着算，不重读原始数据集
        context.setDatasetPath(checkpoint.currentInputPath());
        context.setDatasetCode(checkpoint.datasetCode());

        assembler.fillPlan(result, plan);
        markAlreadyExecuted(result, checkpoint.executedStepNos());

        CleaningPlan resumePlan = pendingPlan(plan, checkpoint.executedStepNos());
        if (resumePlan.getSteps().isEmpty()) {
            return finishWithoutExecution(taskInfo, result, plan, profile, checkpoint);
        }

        // ── 2. 代码生成 + 沙箱执行 ──
        persistence.updateStatus(taskInfo, "EXECUTING", null);
        List<StepExecution> executions = executorAgent.run(context,
            new ExecutionInput(resumePlan, profile));
        persistence.saveExecutions(taskInfo, plan.getPlanCode(),
            executions, checkpoint.currentInputPath());
        assembler.fillExecution(result, executions);

        String outputPath = assembler.lastSuccessfulOutput(executions);
        if (outputPath == null) {
            outputPath = checkpoint.currentInputPath();
        }

        // ── 3. 三层校验 ──
        persistence.updateStatus(taskInfo, "VALIDATING", null);
        ValidationReport report =
            validatorAgent.run(context, new ValidationInput(profile, outputPath, plan));
        persistence.saveFindings(taskInfo, report, "AFTER");
        assembler.fillValidation(result, report);

        boolean allSucceeded = executions.stream().allMatch(StepExecution::isSuccess);
        if (!allSucceeded) {
            result.setStatus("FAILED");
            result.setFailReason(assembler.failReasonOf(executions));
        }
        else {
            result.setStatus(plan.needsClarification() ? "CLARIFYING" : "DONE");
        }

        List<Integer> executed = new ArrayList<>(checkpoint.executedStepNos());
        executions.stream().map(StepExecution::getStepNo).forEach(executed::add);
        refreshCheckpoint(taskInfo, result, checkpoint, plan, profile, outputPath, executed);

        persistence.updateStatus(taskInfo, result.getStatus(), result.getFailReason());
        traceRecorder.mark(taskInfo, TraceStageCode.FINALIZE,
            "澄清恢复轮跑到终点，状态 " + result.getStatus());
        return result;
    }

    // ── 答复 ──

    private List<AnswerResolution> resolveAnswers(TaskInfo taskInfo, CleaningPlan plan,
                                                  ProfileResponse profile) {
        Map<String, ClarificationItem> byCode = new LinkedHashMap<>();
        clarificationStore.loadByTask(taskInfo.getTaskCode())
            .forEach(item -> byCode.put(item.getClarifyCode(), item));

        List<AnswerResolution> resolutions = new ArrayList<>();
        taskInfo.getAnswers().forEach((clarifyCode, answer) -> {
            ClarificationItem item = byCode.get(clarifyCode);
            if (item == null) {
                throw new BusinessException(BaseCode.PARAM_INVALID,
                    "澄清项 " + clarifyCode + " 不属于任务 " + taskInfo.getTaskCode());
            }
            AnswerResolution resolution = resolver.resolve(item, answer, profile);
            clarificationStore.saveAnswer(clarifyCode, answer, resolution.optionCode());
            syncPlanItem(plan, clarifyCode, answer, resolution.optionCode());
            if (!resolution.isResolved()) {
                log.warn("澄清项 {} 的答复未被采纳：{}", clarifyCode, resolution.rejectReason());
            }
            resolutions.add(resolution);
        });
        return resolutions;
    }

    /** checkpoint 里的方案也要带上答复，否则下一轮恢复读回来的是没答过的样子。 */
    private void syncPlanItem(CleaningPlan plan, String clarifyCode,
                              String answer, String optionCode) {
        plan.getClarifications().stream()
            .filter(item -> clarifyCode.equals(item.getClarifyCode()))
            .findFirst()
            .ifPresent(item -> {
                item.setAnswer(answer);
                item.setAnswerAction(optionCode);
            });
    }

    private void recordClarifyStage(TaskInfo taskInfo, List<AnswerResolution> resolutions,
                                    int unblocked) {
        long accepted = resolutions.stream().filter(AnswerResolution::isResolved).count();
        String summary = "收到 %d 条答复，采纳 %d 条，解锁 %d 个步骤".formatted(
            resolutions.size(), accepted, unblocked);
        log.info("澄清答复归一完成 taskCode={} {}", taskInfo.getTaskCode(), summary);
        traceRecorder.mark(taskInfo, TraceStageCode.CLARIFY, summary);
    }

    private void fillOutcomes(CleanTaskResultVO result, List<AnswerResolution> resolutions) {
        for (AnswerResolution resolution : resolutions) {
            CleanTaskResultVO.ClarifyOutcomeView view = new CleanTaskResultVO.ClarifyOutcomeView();
            view.setClarifyCode(resolution.clarifyCode());
            view.setTopic(resolution.topic());
            view.setAnswer(resolution.answer());
            view.setAnswerAction(resolution.optionCode());
            view.setAccepted(resolution.isResolved());
            view.setRejectReason(resolution.rejectReason());
            view.setKnowledgeBacked(resolution.knowledgeBacked());
            result.getClarifyOutcomes().add(view);
        }
    }

    // ── 本轮要跑哪些步骤 ──

    /** 可执行、且首轮没跑过的步骤。原方案对象不动，避免把「跑过一次」写回 checkpoint。 */
    private CleaningPlan pendingPlan(CleaningPlan plan, List<Integer> executedStepNos) {
        Set<Integer> executed = new LinkedHashSet<>(executedStepNos);
        CleaningPlan pending = new CleaningPlan();
        pending.setPlanCode(plan.getPlanCode());
        pending.setSummary(plan.getSummary());
        plan.executableSteps().stream()
            .filter(step -> !executed.contains(step.getStepNo()))
            .forEach(step -> pending.getSteps().add(step));
        return pending;
    }

    private void markAlreadyExecuted(CleanTaskResultVO result, List<Integer> executedStepNos) {
        Set<Integer> executed = new LinkedHashSet<>(executedStepNos);
        result.getSteps().stream()
            .filter(view -> executed.contains(view.getStepNo()))
            .forEach(view -> {
                view.setExecuted(true);
                view.setSuccess(true);
                view.setSkipReason("首轮已执行，本轮不重跑");
            });
    }

    /**
     * 答复一条都没解锁步骤时的收尾。这不是失败：医生可能只答了一部分，也可能答的
     * 恰好是系统用不上的那条。状态照实反映还有没有待答项。
     */
    private CleanTaskResultVO finishWithoutExecution(TaskInfo taskInfo, CleanTaskResultVO result,
                                                     CleaningPlan plan, ProfileResponse profile,
                                                     TaskCheckpoint checkpoint) {
        String reason = "本轮答复没有解锁任何步骤，无代码生成与沙箱执行";
        log.info("任务 {} {}", taskInfo.getTaskCode(), reason);
        traceRecorder.skip(taskInfo, TraceStageCode.SANDBOX, reason);

        AgentContext context = taskInfo.toAgentContext();
        context.setDatasetPath(checkpoint.currentInputPath());
        ValidationReport report = validatorAgent.run(context,
            new ValidationInput(profile,
                checkpoint.executedStepNos().isEmpty() ? null : checkpoint.currentInputPath(),
                plan));
        assembler.fillValidation(result, report);

        result.setStatus(plan.needsClarification() ? "CLARIFYING" : "DONE");
        refreshCheckpoint(taskInfo, result, checkpoint, plan, profile,
            checkpoint.currentInputPath(), checkpoint.executedStepNos());
        persistence.updateStatus(taskInfo, result.getStatus(), result.getFailReason());
        traceRecorder.mark(taskInfo, TraceStageCode.FINALIZE,
            "澄清恢复轮跑到终点，状态 " + result.getStatus());
        return result;
    }

    /** 还有待答项就把新状态写回 checkpoint，支持医生分多次答。 */
    private void refreshCheckpoint(TaskInfo taskInfo, CleanTaskResultVO result,
                                   TaskCheckpoint checkpoint, CleaningPlan plan,
                                   ProfileResponse profile, String currentInputPath,
                                   List<Integer> executedStepNos) {
        if (!"CLARIFYING".equals(result.getStatus())) {
            return;
        }
        TaskCheckpoint updated = new TaskCheckpoint(
            checkpoint.taskCode(), checkpoint.requirement(), checkpoint.datasetCode(),
            checkpoint.datasetPath(), currentInputPath, executedStepNos, profile, plan);
        if (!checkpointStore.save(taskInfo.getTraceId(), "CLARIFYING", updated)) {
            result.setFailReason("澄清 checkpoint 更新失败，剩余澄清项答复后将无法恢复");
        }
    }

}
