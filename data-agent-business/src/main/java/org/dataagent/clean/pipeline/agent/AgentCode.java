package org.dataagent.clean.pipeline.agent;

/** 四个 Agent 的标识。 */
public enum AgentCode {

    PROFILER("数据画像"),
    PLANNER("方案规划"),
    EXECUTOR("代码生成与执行"),
    VALIDATOR("三层校验"),
    ;

    private final String label;

    AgentCode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
