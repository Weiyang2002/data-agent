package org.dataagent.clean.pipeline.agent;

/**
 * 四个 Agent 的统一接口，自定义而非使用框架的 Agent 抽象。
 *
 * @param <I> 输入
 * @param <O> 输出
 */
public interface DataAgent<I, O> {

    AgentCode code();

    O run(AgentContext context, I input);
}
