package org.dataagent.clean.pipeline.model.plan;

import lombok.Data;
import org.dataagent.clean.toolsclient.model.ExecuteResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个步骤的完整执行历史，含全部自修复轮次。
 *
 * <p>保留每一轮而非只留最后一轮：失败归因看的是自修复过程（首版错误、每轮是否收敛、
 * 错误是否重复），只留最终结果无法区分。
 */
@Data
public class StepExecution {

    private int stepNo;

    private PlanAction action;

    private List<Attempt> attempts = new ArrayList<>();

    private boolean success;

    /** 成功时的产出路径；失败为 null */
    private String outputPath;

    /**
     * 自修复循环提前终止的原因；跑满上限或成功时为 null。
     *
     * <p>目前只有一种：修复版与本步骤已跑过的某一版逐字节相同，再执行一次信息增益
     * 为零（见 {@code ExecutorAgent}）。作为返回值而非仅日志，使结果里能看见少跑的
     * 轮次（CLAUDE.md 不变量 7）。
     */
    private String earlyStopReason;

    public boolean isShortCircuited() {
        return earlyStopReason != null;
    }

    @Data
    public static class Attempt {
        /** 0 是首次生成，>0 是自修复。M3 自修复成功率按它统计 */
        private int retryNo;
        private String code;
        private ExecuteResponse response;
    }

    public Attempt lastAttempt() {
        return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
    }

    /** 用掉的自修复次数（首次生成不算） */
    public int repairCount() {
        return Math.max(0, attempts.size() - 1);
    }
}
