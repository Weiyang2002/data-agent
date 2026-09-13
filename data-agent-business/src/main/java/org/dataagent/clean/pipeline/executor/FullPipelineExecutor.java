package org.dataagent.clean.pipeline.executor;

import org.dataagent.clean.pipeline.agent.AgentContext;
import org.dataagent.clean.pipeline.agent.ExecutorAgent;
import org.dataagent.clean.pipeline.agent.PlannerAgent;
import org.dataagent.clean.pipeline.agent.ProfilerAgent;
import org.dataagent.clean.pipeline.agent.ValidationInput;
import org.dataagent.clean.pipeline.agent.ValidatorAgent;
import org.dataagent.clean.pipeline.clarify.TaskCheckpoint;
import org.dataagent.clean.pipeline.clarify.TaskCheckpointStore;
import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.pipeline.model.plan.ExecutionInput;
import org.dataagent.clean.pipeline.model.plan.StepExecution;
import org.dataagent.clean.pipeline.model.profile.ProfileResult;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
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
 *
 * <p>产生澄清项时写业务 checkpoint，医生答复后由 {@link ClarifyResumeExecutor} 接着
 * 跑；写失败要在结果里说清楚这个任务已经不可恢复。
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
    private final TaskCheckpointStore checkpointStore;
    private final CleanTaskResultAssembler assembler;

    public FullPipelineExecutor(ProfilerAgent profilerAgent,
                                PlannerAgent plannerAgent,
                                ExecutorAgent executorAgent,
                                ValidatorAgent validatorAgent,
                                TraceRecorder traceRecorder,
                                TaskPersistence persistence,
                                TaskCheckpointStore checkpointStore,
                                CleanTaskResultAssembler assembler) {
        this.profilerAgent = profilerAgent;
        this.plannerAgent = plannerAgent;
        this.executorAgent = executorAgent;
        this.validatorAgent = validatorAgent;
        this.traceRecorder = traceRecorder;
        this.persistence = persistence;
        this.checkpointStore = checkpointStore;
        this.assembler = assembler;
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
        assembler.fillProfile(result, profileResult);
        persistence.updateStatus(taskInfo, "PROFILING", null);

        // ── 2. 规划 + 澄清分流 ──
        // 知识库检索是分流的一部分，不单独埋点
        CleaningPlan plan = plannerAgent.run(context, profile);
        persistence.savePlan(taskInfo, plan);
        assembler.fillPlan(result, plan);

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
            assembler.fillValidation(result, empty);
            result.setStatus(plan.needsClarification() ? "CLARIFYING" : "DONE");
            saveCheckpointIfClarifying(taskInfo, result, plan, profile,
                taskInfo.getDatasetPath(), List.of());
            persistence.updateStatus(taskInfo, result.getStatus(), result.getFailReason());
            return result;
        }

        // ── 3. 代码生成 + 沙箱执行（含有界自修复）──
        persistence.updateStatus(taskInfo, "EXECUTING", null);
        List<StepExecution> executions =
            executorAgent.run(context, new ExecutionInput(plan, profile));
        persistence.saveExecutions(taskInfo, plan.getPlanCode(),
            executions, taskInfo.getDatasetPath());
        assembler.fillExecution(result, executions);

        String outputPath = assembler.lastSuccessfulOutput(executions);

        // ── 4. 三层校验 ──
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
        saveCheckpointIfClarifying(taskInfo, result, plan, profile,
            outputPath == null ? taskInfo.getDatasetPath() : outputPath,
            executions.stream().map(StepExecution::getStepNo).toList());
        persistence.updateStatus(taskInfo, result.getStatus(), result.getFailReason());
        traceRecorder.mark(taskInfo, TraceStageCode.FINALIZE, "链路跑到终点，状态 " + result.getStatus());
        return result;
    }

    /**
     * 只有停在 CLARIFYING 才写 checkpoint：跑完的任务没有恢复语义，失败的任务要重跑
     * 而不是接着跑。写失败按「可降级但不可静默降级」处理，把话说进 failReason。
     */
    private void saveCheckpointIfClarifying(TaskInfo taskInfo, CleanTaskResultVO result,
                                            CleaningPlan plan, ProfileResponse profile,
                                            String currentInputPath,
                                            List<Integer> executedStepNos) {
        if (!"CLARIFYING".equals(result.getStatus())) {
            return;
        }
        TaskCheckpoint checkpoint = new TaskCheckpoint(
            taskInfo.getTaskCode(), taskInfo.getRequirement(), taskInfo.getDatasetCode(),
            taskInfo.getDatasetPath(), currentInputPath, executedStepNos, profile, plan);
        if (!checkpointStore.save(taskInfo.getTraceId(), "CLARIFYING", checkpoint)) {
            result.setFailReason("澄清 checkpoint 写入失败，本任务无法通过答复接口恢复，"
                + "答完只能重跑整条链路");
        }
    }
}
