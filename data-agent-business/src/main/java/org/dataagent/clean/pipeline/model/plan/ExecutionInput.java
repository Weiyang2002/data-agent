package org.dataagent.clean.pipeline.model.plan;

import org.dataagent.clean.toolsclient.model.ProfileResponse;

/**
 * ExecutorAgent 的输入。
 *
 * <p>画像和方案打包成不可变入参传入，而非在 Agent 上加可变字段：Agent 是 Spring
 * 单例，可变状态会在并发下串任务。
 *
 * @param plan    规划产出，含可执行步骤与已装配的 params
 * @param profile 处理前画像，给代码生成提供列结构参考
 */
public record ExecutionInput(CleaningPlan plan, ProfileResponse profile) {
}
