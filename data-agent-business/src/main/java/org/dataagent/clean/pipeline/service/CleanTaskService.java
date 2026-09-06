package org.dataagent.clean.pipeline.service;

import org.dataagent.clean.pipeline.dto.CleanTaskRequest;
import org.dataagent.clean.pipeline.executor.PipelineExecutorRegistry;
import org.dataagent.clean.pipeline.executor.PipelineMode;
import org.dataagent.clean.pipeline.observability.StageBenchmarkRecorder;
import org.dataagent.clean.pipeline.observability.TraceContext;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编排主服务。只做三件事：生成任务标识、登记任务、按模式分发给 Executor，
 * 不含链路逻辑（链路在 {@code FullPipelineExecutor} 里）。新增执行模式只需多注册
 * 一个 Executor。
 */
@Service
public class CleanTaskService {

    private static final Logger log = LoggerFactory.getLogger(CleanTaskService.class);

    private static final DateTimeFormatter TASK_TIME =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /* 任务号后缀计数器 */
    private final AtomicInteger sequence = new AtomicInteger(0);

    private final PipelineExecutorRegistry executorRegistry;
    private final TaskPersistence persistence;
    private final StageBenchmarkRecorder benchmarkRecorder;

    public CleanTaskService(PipelineExecutorRegistry executorRegistry,
                            TaskPersistence persistence,
                            StageBenchmarkRecorder benchmarkRecorder) {
        this.executorRegistry = executorRegistry;
        this.persistence = persistence;
        this.benchmarkRecorder = benchmarkRecorder;
    }

    /**
     * 任务作用域的唯一开合点。{@code TraceContext} 是 ThreadLocal，开在 try 之外、
     * 关在 finally 里，保证任何情况下都收口，避免下一个任务的埋点挂到上一个名下。
     */
    public CleanTaskResultVO run(CleanTaskRequest request) {
        TaskInfo taskInfo = newTask(request);
        persistence.createTask(taskInfo);

        log.info("任务开始 taskCode={} traceId={} 需求={}",
            taskInfo.getTaskCode(), taskInfo.getTraceId(), request.getRequirement());
        TraceContext.TaskScope scope =
            TraceContext.openTask(taskInfo.getTaskCode(), taskInfo.getTraceId());
        try {
            CleanTaskResultVO result = executorRegistry.get(PipelineMode.FULL).execute(taskInfo);
            log.info("任务结束 taskCode={} status={}",
                taskInfo.getTaskCode(), result.getStatus());
            return result;
        }
        catch (RuntimeException exception) {
            // 未预期的失败也要落状态，否则任务永远停在 CREATED
            log.error("任务异常 taskCode={}", taskInfo.getTaskCode(), exception);
            persistence.updateStatus(taskInfo, "FAILED", exception.getMessage());
            throw exception;
        }
        finally {
            // 失败的任务同样要留下账，它烧掉的 Token 一分不少
            benchmarkRecorder.flush(scope);
            TraceContext.closeTask();
        }
    }

    private TaskInfo newTask(CleanTaskRequest request) {
        String stamp = LocalDateTime.now().format(TASK_TIME);
        String taskCode = "T" + stamp + String.format("%03d", sequence.incrementAndGet() % 1000);

        TaskInfo taskInfo = new TaskInfo();
        taskInfo.setTaskCode(taskCode);
        // traceId 随 X-Trace-Id 头传到 Python，两侧日志用同一个 ID
        taskInfo.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        taskInfo.setThreadId(taskCode);
        taskInfo.setRequirement(request.getRequirement());
        taskInfo.setDatasetPath(request.getDatasetPath());
        taskInfo.setDatasetCode("DS" + taskCode.substring(1));
        return taskInfo;
    }
}
