package org.dataagent.clean.pipeline.clarify;

import org.dataagent.clean.pipeline.model.plan.CleaningPlan;
import org.dataagent.clean.toolsclient.model.ProfileResponse;

import java.util.List;

/**
 * 澄清中断时保存的业务状态，恢复时据此接着跑而不是重跑。
 *
 * <p>装的是「重算一遍会花钱或不可复现」的东西：画像要跑一次 Python 全表扫描加一次
 * 模型归纳，方案要一次模型调用；数据集路径和已执行到哪一步则是重跑会出错的东西。
 *
 * @param currentInputPath 下一步该读哪个文件：首轮跑成功过步骤时是它的产出，否则是
 *                         原始数据集。恢复时不重跑已成功的步骤
 * @param executedStepNos  首轮已经执行过的步骤号
 */
public record TaskCheckpoint(String taskCode,
                             String requirement,
                             String datasetCode,
                             String datasetPath,
                             String currentInputPath,
                             List<Integer> executedStepNos,
                             ProfileResponse profile,
                             CleaningPlan plan) {
}
