package org.dataagent.clean.pipeline.service;

import org.dataagent.clean.pipeline.data.TaskStageEntity;
import org.dataagent.clean.pipeline.mapper.TaskStageMapper;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 阶段 trace 的写入端。埋点入口是 {@code @TraceStage} 注解 + {@code TraceStageAspect}，
 * 本类只负责把一条阶段记录写进表，以及记录一次 {@link #skip}（跳过是业务判断，切面
 * 拦不到，但必须留痕）。埋点失败只打 WARN，不影响业务。
 */
@Component
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final TaskStageMapper taskStageMapper;

    public TraceRecorder(TaskStageMapper taskStageMapper) {
        this.taskStageMapper = taskStageMapper;
    }

    /**
     * 写一条阶段记录，由 {@code TraceStageAspect} 在阶段结束时调用。
     *
     * @param depth   嵌套深度，0 = 顶层。延迟统计只求和 depth = 0
     * @param summary 阶段摘要，带上 Token 与模型调用次数
     */
    public void stage(String taskCode, String traceId, TraceStageCode stage, int depth,
                      String state, String summary, String errorMsg,
                      LocalDateTime startTime, LocalDateTime endTime, long costMillis) {
        persist(taskCode, traceId, stage, depth, state, summary, errorMsg,
            startTime, endTime, costMillis);
    }

    /** 显式记录一次跳过。跳过要留痕，否则 trace 里看不出「这一步为什么没有」 */
    public void skip(TaskInfo taskInfo, TraceStageCode stage, String reason) {
        LocalDateTime now = LocalDateTime.now();
        persist(taskInfo.getTaskCode(), taskInfo.getTraceId(), stage, 0,
            "SKIPPED", reason, null, now, now, 0L);
    }

    /**
     * 零时长的里程碑（目前只有 FINALIZE 用），标记链路是否跑到终点。
     */
    public void mark(TaskInfo taskInfo, TraceStageCode stage, String summary) {
        LocalDateTime now = LocalDateTime.now();
        persist(taskInfo.getTaskCode(), taskInfo.getTraceId(), stage, 0,
            "SUCCESS", summary, null, now, now, 0L);
    }

    private void persist(String taskCode, String traceId, TraceStageCode stage, int depth,
                         String state, String summary, String errorMsg,
                         LocalDateTime start, LocalDateTime end, Long cost) {
        try {
            TaskStageEntity entity = new TaskStageEntity();
            entity.setTaskCode(taskCode);
            entity.setTraceId(traceId);
            entity.setStageCode(stage.name());
            entity.setStageOrder(stage.getOrder());
            entity.setDepth(depth);
            entity.setState(state);
            entity.setSummary(truncate(summary, 500));
            entity.setErrorMsg(truncate(errorMsg, 1000));
            entity.setCostMillis(cost);
            entity.setStartTime(start);
            entity.setEndTime(end);
            taskStageMapper.insert(entity);
        }
        catch (Exception exception) {
            log.warn("阶段埋点写入失败（不影响业务），stage={}, 原因={}",
                stage, exception.getMessage());
        }
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
