package org.dataagent.clean.pipeline.agent;

import org.dataagent.clean.pipeline.model.plan.PlanStep;
import org.dataagent.clean.toolsclient.model.ExecuteResponse;
import org.dataagent.clean.toolsclient.model.ProfileResponse;

/**
 * 代码生成端口，框架隔离的接缝。
 *
 * <p>本接口在 {@code agent/} 包（零框架依赖），实现在 {@code executor/} 包
 * （允许框架依赖）。ExecutorAgent 只依赖此接口，不感知具体实现。
 */
public interface CodeGenPort {

    /**
     * 首次生成。入参不含阈值，{@code step.getParams()} 只把键名交给实现写进
     * Prompt，值在沙箱执行时才注入。
     */
    String generate(AgentContext context, PlanStep step, ProfileResponse profile);

    /** 报错回喂重生成。 */
    String repair(AgentContext context, PlanStep step,
                  String previousCode, ExecuteResponse failure);

    /** 自修复次数上限，超过则降级为向医生报告。 */
    int maxRepairAttempts();
}
