package org.dataagent.clean.pipeline.agent;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次任务在四个 Agent 之间传递的上下文。
 *
 * <p>不含框架类型（如 {@code RunnableConfig}）：checkpoint 线程配置由
 * {@code pipeline/executor/} 持有，Agent 只拿到 {@link #threadId} 字符串标识。
 */
@Data
public class AgentContext {

    private String taskCode;

    /** 跨语言贯穿：随 X-Trace-Id 头传给 Python，两侧日志用同一个 ID */
    private String traceId;

    /** checkpoint 线程标识。对 Agent 而言只是字符串，不携带框架语义 */
    private String threadId;

    private String datasetCode;

    private String datasetPath;

    /** 医生的原始需求 */
    private String requirement;

    /**
     * 已确认的澄清结论：topic → answer。中断恢复后 PlannerAgent 先查这里，
     * 已答过的决策点不再重复提问。
     */
    private Map<String, String> confirmedAnswers = new LinkedHashMap<>();

    public static AgentContext of(String taskCode, String traceId, String requirement) {
        AgentContext context = new AgentContext();
        context.setTaskCode(taskCode);
        context.setTraceId(traceId);
        context.setRequirement(requirement);
        return context;
    }
}
