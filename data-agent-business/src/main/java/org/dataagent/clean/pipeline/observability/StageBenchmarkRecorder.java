package org.dataagent.clean.pipeline.observability;

import org.dataagent.clean.pipeline.data.StageBenchmarkEntity;
import org.dataagent.clean.pipeline.mapper.StageBenchmarkMapper;
import org.dataagent.clean.pipeline.model.trace.TraceStageCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 任务结束时把 {@link TraceContext.TaskScope} 里的账一次性落到
 * {@code data_agent_stage_benchmark}（一个任务一个阶段一行，内存累加后批量插入）。
 * 中途崩溃会丢这份账，故障恢复所需信息在逐阶段实时写的 {@code task_stage} 流水里。
 */
@Component
public class StageBenchmarkRecorder {

    private static final Logger log = LoggerFactory.getLogger(StageBenchmarkRecorder.class);

    private final StageBenchmarkMapper stageBenchmarkMapper;

    public StageBenchmarkRecorder(StageBenchmarkMapper stageBenchmarkMapper) {
        this.stageBenchmarkMapper = stageBenchmarkMapper;
    }

    /** 结算并落库。埋点失败只打 WARN，绝不影响业务返回。 */
    public void flush(TraceContext.TaskScope scope) {
        if (scope == null) {
            return;
        }
        try {
            List<TraceContext.StageTally> tallies = scope.settle();
            long totalTokens = 0;
            int totalCalls = 0;
            int missing = 0;
            for (TraceContext.StageTally tally : tallies) {
                stageBenchmarkMapper.insert(toEntity(scope, tally));
                totalTokens += tally.totalTokens();
                totalCalls += tally.modelCalls();
                missing += tally.usageMissingCalls();
                if (tally.stage() == TraceStageCode.UNATTRIBUTED) {
                    // 有模型调用没被 @TraceStage 覆盖到：某个调用点漏标，或 AOP 代理没生效
                    log.warn("任务 {} 有 {} 次模型调用（{} Token）落在任何被埋点的阶段之外，"
                            + "已记入 UNATTRIBUTED。请检查是否有 chatClient 调用点漏标 @TraceStage",
                        scope.taskCode(), tally.modelCalls(), tally.totalTokens());
                }
            }
            log.info("Token 账本 taskCode={} 阶段 {} 个，模型调用 {} 次，共 {} Token{}",
                scope.taskCode(), tallies.size(), totalCalls, totalTokens,
                missing > 0 ? "（其中 " + missing + " 次调用模型未回 usage，总量是下界）" : "");
        }
        catch (Exception exception) {
            log.warn("阶段账本写入失败（不影响业务），taskCode={}, 原因={}",
                scope.taskCode(), exception.getMessage());
        }
    }

    private StageBenchmarkEntity toEntity(TraceContext.TaskScope scope,
                                          TraceContext.StageTally tally) {
        StageBenchmarkEntity entity = new StageBenchmarkEntity();
        entity.setTaskCode(scope.taskCode());
        entity.setTraceId(scope.traceId());
        entity.setStageCode(tally.stage().name());
        entity.setStageOrder(tally.stage().getOrder());
        entity.setDepth(tally.depth());
        entity.setEnterCount(tally.enterCount());
        entity.setModelCalls(tally.modelCalls());
        entity.setPromptTokens(tally.promptTokens());
        entity.setCompletionTokens(tally.completionTokens());
        entity.setTotalTokens(tally.totalTokens());
        entity.setUsageMissingCalls(tally.usageMissingCalls());
        entity.setSelfCostMillis(tally.selfMillis());
        entity.setTotalCostMillis(tally.totalMillis());
        return entity;
    }
}
