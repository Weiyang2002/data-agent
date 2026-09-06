package org.dataagent.clean.pipeline.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dataagent.clean.pipeline.data.StageBenchmarkEntity;
import org.dataagent.clean.pipeline.data.TaskEntity;
import org.dataagent.clean.pipeline.data.TaskStageEntity;
import org.dataagent.clean.pipeline.mapper.StageBenchmarkMapper;
import org.dataagent.clean.pipeline.mapper.TaskMapper;
import org.dataagent.clean.pipeline.mapper.TaskStageMapper;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.dataagent.clean.pipeline.vo.TaskTraceVO;
import org.dataagent.common.exception.BusinessException;
import org.dataagent.common.result.BaseCode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 按 taskCode 拉出完整链路（阶段流水 + Token 账本）。
 */
@Service
public class TaskTraceService {

    private final TaskMapper taskMapper;
    private final TaskStageMapper taskStageMapper;
    private final StageBenchmarkMapper stageBenchmarkMapper;

    public TaskTraceService(TaskMapper taskMapper,
                            TaskStageMapper taskStageMapper,
                            StageBenchmarkMapper stageBenchmarkMapper) {
        this.taskMapper = taskMapper;
        this.taskStageMapper = taskStageMapper;
        this.stageBenchmarkMapper = stageBenchmarkMapper;
    }

    public TaskTraceVO trace(String taskCode) {
        TaskEntity task = taskMapper.selectOne(new LambdaQueryWrapper<TaskEntity>()
            .eq(TaskEntity::getTaskCode, taskCode));
        if (task == null) {
            throw new BusinessException(BaseCode.PARAM_INVALID, "找不到任务 " + taskCode);
        }

        TaskTraceVO view = new TaskTraceVO();
        view.setTaskCode(task.getTaskCode());
        view.setTraceId(task.getTraceId());
        view.setStatus(task.getStatus());
        view.setRequirement(task.getRequirement());
        view.setFailReason(task.getFailReason());
        view.setCreateTime(task.getCreateTime());

        fillStages(taskCode, view);
        fillBenchmark(taskCode, view);
        return view;
    }

    private void fillStages(String taskCode, TaskTraceVO view) {
        List<TaskStageEntity> stages = taskStageMapper.selectList(
            new LambdaQueryWrapper<TaskStageEntity>()
                .eq(TaskStageEntity::getTaskCode, taskCode)
                .orderByAsc(TaskStageEntity::getId));

        long topLevelCost = 0;
        for (TaskStageEntity stage : stages) {
            TaskTraceVO.StageRow row = new TaskTraceVO.StageRow();
            row.setStageCode(stage.getStageCode());
            row.setLabel(labelOf(stage.getStageCode()));
            row.setStageOrder(stage.getStageOrder());
            row.setDepth(stage.getDepth());
            row.setState(stage.getState());
            row.setSummary(stage.getSummary());
            row.setErrorMsg(stage.getErrorMsg());
            row.setCostMillis(stage.getCostMillis());
            row.setStartTime(stage.getStartTime());
            row.setEndTime(stage.getEndTime());
            view.getStages().add(row);

            // 只累加顶层，子阶段耗时已含在父阶段里
            if (stage.getDepth() != null && stage.getDepth() == 0 && stage.getCostMillis() != null) {
                topLevelCost += stage.getCostMillis();
            }
        }
        view.setTotalCostMillis(topLevelCost);

        // 按 start_time 再按 depth 排序（按 id 排会让子阶段排在父阶段前）
        view.getStages().sort((left, right) -> {
            int byTime = compareNullable(left.getStartTime(), right.getStartTime());
            return byTime != 0 ? byTime : Integer.compare(left.getDepth(), right.getDepth());
        });
    }

    private void fillBenchmark(String taskCode, TaskTraceVO view) {
        List<StageBenchmarkEntity> rows = stageBenchmarkMapper.selectList(
            new LambdaQueryWrapper<StageBenchmarkEntity>()
                .eq(StageBenchmarkEntity::getTaskCode, taskCode)
                .orderByAsc(StageBenchmarkEntity::getStageOrder));

        long total = 0;
        long prompt = 0;
        long completion = 0;
        int calls = 0;
        int missing = 0;
        for (StageBenchmarkEntity row : rows) {
            total += nz(row.getTotalTokens());
            prompt += nz(row.getPromptTokens());
            completion += nz(row.getCompletionTokens());
            calls += (int) nz(row.getModelCalls());
            missing += (int) nz(row.getUsageMissingCalls());
        }
        view.setTotalTokens(total);
        view.setPromptTokens(prompt);
        view.setCompletionTokens(completion);
        view.setModelCalls(calls);
        view.setUsageMissingCalls(missing);

        for (StageBenchmarkEntity row : rows) {
            TaskTraceVO.StageCost cost = new TaskTraceVO.StageCost();
            cost.setStageCode(row.getStageCode());
            cost.setLabel(labelOf(row.getStageCode()));
            cost.setDepth(row.getDepth());
            cost.setEnterCount(row.getEnterCount());
            cost.setModelCalls(row.getModelCalls());
            cost.setPromptTokens(row.getPromptTokens());
            cost.setCompletionTokens(row.getCompletionTokens());
            cost.setTotalTokens(row.getTotalTokens());
            cost.setUsageMissingCalls(row.getUsageMissingCalls());
            cost.setSelfCostMillis(row.getSelfCostMillis());
            cost.setTotalCostMillis(row.getTotalCostMillis());
            cost.setTokenShare(total == 0 ? null
                : Math.round((double) nz(row.getTotalTokens()) / total * 10000) / 10000.0);
            view.getTokenByStage().add(cost);
        }

        if (rows.isEmpty()) {
            view.getCaveats().add("本任务没有 Token 账本：可能跑在埋点之前，"
                + "或 data_agent_stage_benchmark 没建表（跑 alter_m5_observability.sql）");
        }
        if (missing > 0) {
            view.getCaveats().add(("有 %d 次模型调用没有返回 usage 元数据，"
                + "上面的 Token 是下界不是真值")
                .formatted(missing));
        }
        rows.stream()
            .filter(row -> TraceStageCode.UNATTRIBUTED.name().equals(row.getStageCode()))
            .findFirst()
            .ifPresent(row -> view.getCaveats().add(
                ("有 %d 次模型调用（%d Token）落在任何被埋点的阶段之外，"
                    + "说明有 chatClient 调用点漏标 @TraceStage 或 AOP 代理未生效")
                    .formatted(nz(row.getModelCalls()), nz(row.getTotalTokens()))));
    }

    private String labelOf(String stageCode) {
        try {
            return TraceStageCode.valueOf(stageCode).getLabel();
        }
        catch (IllegalArgumentException exception) {
            // 历史 trace 里可能有已改名的阶段码，原样返回，不抛异常
            return stageCode;
        }
    }

    private <T extends Comparable<T>> int compareNullable(T left, T right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return left.compareTo(right);
    }

    private long nz(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
