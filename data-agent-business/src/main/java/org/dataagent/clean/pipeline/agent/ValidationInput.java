package org.dataagent.clean.pipeline.agent;

import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.toolsclient.model.ProfileResponse;

/**
 * ValidatorAgent 的输入。
 *
 * @param beforeProfile 处理前画像 —— 常识级校验的对照基准
 * @param outputPath    处理后数据路径；为 null 表示没有任何步骤成功执行
 * @param plan          方案，用于确定该校验哪些列
 */
public record ValidationInput(ProfileResponse beforeProfile, String outputPath, CleaningPlan plan) {
}
