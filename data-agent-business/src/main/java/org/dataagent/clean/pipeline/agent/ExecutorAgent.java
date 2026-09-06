package org.dataagent.clean.pipeline.agent;

import org.dataagent.clean.pipeline.config.CleanAgentProperties;
import org.dataagent.clean.pipeline.model.plan.ExecutionInput;
import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.pipeline.model.plan.StepExecution;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.observability.TraceStage;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.ExecuteRequest;
import org.dataagent.clean.toolsclient.model.ExecuteResponse;
import org.dataagent.clean.toolsclient.model.ProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 3：代码生成与沙箱执行，含有界自修复。
 *
 */
@Component
public class ExecutorAgent implements DataAgent<ExecutionInput, List<StepExecution>> {

    private static final Logger log = LoggerFactory.getLogger(ExecutorAgent.class);

    private final CodeGenPort codeGenPort;
    private final DataToolsClient dataToolsClient;
    private final CleanAgentProperties properties;

    public ExecutorAgent(CodeGenPort codeGenPort,
                         DataToolsClient dataToolsClient,
                         CleanAgentProperties properties) {
        this.codeGenPort = codeGenPort;
        this.dataToolsClient = dataToolsClient;
        this.properties = properties;
    }

    @Override
    public AgentCode code() {
        return AgentCode.EXECUTOR;
    }

    @Override
    @TraceStage(TraceStageCode.SANDBOX)
    public List<StepExecution> run(AgentContext context, ExecutionInput input) {
        List<StepExecution> executions = new ArrayList<>();
        String currentInput = context.getDatasetPath();

        for (PlanStep step : input.plan().executableSteps()) {
            StepExecution execution = runStep(context, step, currentInput, input.profile());
            executions.add(execution);
            if (!execution.isSuccess()) {
                log.warn("步骤 {}（{}）在 {} 次自修复后仍失败，链路停在此处。"
                        + "已完成的步骤结果保留在 {}",
                    step.getStepNo(), step.getAction(), execution.repairCount(), currentInput);
                break;
            }
            currentInput = execution.getOutputPath();
        }
        return executions;
    }

    private StepExecution runStep(AgentContext context, PlanStep step,
                                  String inputPath, ProfileResponse profile) {
        StepExecution execution = new StepExecution();
        execution.setStepNo(step.getStepNo());
        execution.setAction(step.getAction());

        String code = codeGenPort.generate(context, step, profile);
        // 本步骤已执行过的代码版本，命中说明本轮自修复没有产生新代码
        Set<String> triedCode = new LinkedHashSet<>();

        for (int retryNo = 0; retryNo <= codeGenPort.maxRepairAttempts(); retryNo++) {
            triedCode.add(fingerprint(code));

            ExecuteResponse response = dataToolsClient.execute(
                context.getTraceId(), buildRequest(context, step, code, inputPath, retryNo));

            StepExecution.Attempt attempt = new StepExecution.Attempt();
            attempt.setRetryNo(retryNo);
            attempt.setCode(code);
            attempt.setResponse(response);
            execution.getAttempts().add(attempt);

            if (response.isSuccessful()) {
                execution.setSuccess(true);
                execution.setOutputPath(response.getOutputPath());
                log.info("步骤 {}（{}）执行成功，第 {} 次尝试，输出 {} 行",
                    step.getStepNo(), step.getAction(), retryNo + 1, response.getOutputRowCount());
                return execution;
            }

            if (retryNo == codeGenPort.maxRepairAttempts()) {
                break;
            }
            log.info("步骤 {} 第 {} 次尝试失败（{}），发起自修复",
                step.getStepNo(), retryNo + 1,
                response.isBlocked() ? "被静态检查拦截" : "运行期失败");
            code = codeGenPort.repair(context, step, code, response);

            // 不收敛判据，见类注释
            if (triedCode.contains(fingerprint(code))) {
                execution.setEarlyStopReason(
                    "第 %d 次自修复输出的代码与本步骤已执行过的版本完全相同，判定不收敛，"
                        .formatted(retryNo + 1)
                        + "提前终止（剩余 %d 次机会未使用）"
                            .formatted(codeGenPort.maxRepairAttempts() - retryNo - 1));
                log.warn("步骤 {} 自修复不收敛：{}", step.getStepNo(), execution.getEarlyStopReason());
                break;
            }
        }
        return execution;
    }

    /**
     * 代码指纹，用于判定两版代码是否相同。只归一化不影响语义的部分（行尾空白、
     * 空行、CRLF），保留缩进（Python 里缩进是语法）。
     */
    private String fingerprint(String code) {
        if (code == null) {
            return "";
        }
        return code.replace("\r\n", "\n").lines()
            .map(line -> line.replaceAll("\\s+$", ""))
            .filter(line -> !line.isEmpty())
            .collect(Collectors.joining("\n"));
    }

    private ExecuteRequest buildRequest(AgentContext context, PlanStep step,
                                        String code, String inputPath, int retryNo) {
        ExecuteRequest request = new ExecuteRequest();
        // taskId 带上步骤与重试号，使沙箱工作目录一一对应，便于排查
        request.setTaskId(context.getTaskCode() + "-s" + step.getStepNo() + "-r" + retryNo);
        request.setCode(code);
        // 阈值在这里进入链路，未经过任何 Prompt
        request.setParams(step.getParams());
        request.setInputPath(inputPath);
        request.setTimeoutSeconds(properties.getSandboxTimeoutSeconds());
        request.setMemoryLimitMb(properties.getSandboxMemoryLimitMb());
        return request;
    }
}
