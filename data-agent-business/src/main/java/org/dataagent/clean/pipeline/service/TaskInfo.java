package org.dataagent.clean.pipeline.service;

import lombok.Data;
import org.dataagent.clean.pipeline.agent.AgentContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次任务的上下文载体，编排层视角（含数据集编号等编排细节）。区别于
 * {@link AgentContext}（Agent 层视角，只含 Agent 需要的东西），不合并是为了让
 * Agent 拿不到编排细节。
 */
@Data
public class TaskInfo {

    private String taskCode;

    private String traceId;

    private String threadId;

    private String requirement;

    private String datasetCode;

    private String datasetPath;

    /** 中断恢复时带进来的既有澄清答复：topic → answer */
    private Map<String, String> confirmedAnswers = new LinkedHashMap<>();

    public AgentContext toAgentContext() {
        AgentContext context = new AgentContext();
        context.setTaskCode(taskCode);
        context.setTraceId(traceId);
        context.setThreadId(threadId);
        context.setRequirement(requirement);
        context.setDatasetCode(datasetCode);
        context.setDatasetPath(datasetPath);
        context.setConfirmedAnswers(confirmedAnswers);
        return context;
    }
}
