package org.dataagent.clean.pipeline.service;

import org.dataagent.clean.pipeline.clarify.ClarificationStore;
import org.dataagent.clean.pipeline.data.TaskEntity;
import org.dataagent.clean.pipeline.dto.ClarifyAnswerRequest;
import org.dataagent.clean.pipeline.dto.CleanTaskRequest;
import org.dataagent.clean.pipeline.executor.PipelineExecutorRegistry;
import org.dataagent.clean.pipeline.executor.PipelineMode;
import org.dataagent.clean.pipeline.observability.StageBenchmarkRecorder;
import org.dataagent.clean.pipeline.observability.TraceContext;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.common.exception.BusinessException;
import org.dataagent.common.result.BaseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编排主服务。只做三件事：生成任务标识、登记任务、按模式分发给 Executor，
 * 不含链路逻辑（链路在各 {@code PipelineExecutor} 里）。新增执行模式只需多注册
 * 一个 Executor。
 */
@Service
public class CleanTaskService {

    private static final Logger log = LoggerFactory.getLogger(CleanTaskService.class);

    private static final DateTimeFormatter TASK_TIME =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /* 任务号后缀计数器 */
    private final AtomicInteger sequence = new AtomicInteger(0);

    /** 允许接收澄清答复的任务状态 */
    private static final String CLARIFYING = "CLARIFYING";

    private final PipelineExecutorRegistry executorRegistry;
    private final TaskPersistence persistence;
    private final StageBenchmarkRecorder benchmarkRecorder;
    private final ClarificationStore clarificationStore;

    public CleanTaskService(PipelineExecutorRegistry executorRegistry,
                            TaskPersistence persistence,
                            StageBenchmarkRecorder benchmarkRecorder,
                            ClarificationStore clarificationStore) {
        this.executorRegistry = executorRegistry;
        this.persistence = persistence;
        this.benchmarkRecorder = benchmarkRecorder;
        this.clarificationStore = clarificationStore;
    }

    /** 发起一次完整处理。 */
    public CleanTaskResultVO run(CleanTaskRequest request) {
        TaskInfo taskInfo = newTask(request);
        persistence.createTask(taskInfo);

        log.info("任务开始 taskCode={} traceId={} 需求={}",
            taskInfo.getTaskCode(), taskInfo.getTraceId(), request.getRequirement());
        return dispatch(taskInfo, PipelineMode.FULL);
    }

    /**
     * 澄清答复回传后接着跑。沿用原任务号与原 traceId：医生看到的是同一个任务的后续，
     * 账本与链路都续在原任务名下，而不是凭空多出一个任务。
     */
    public CleanTaskResultVO resume(String taskCode, ClarifyAnswerRequest request) {
        TaskEntity entity = persistence.findTask(taskCode).orElseThrow(
            () -> new BusinessException(BaseCode.PARAM_INVALID, "任务不存在: " + taskCode));
        if (!CLARIFYING.equals(entity.getStatus())) {
            throw new BusinessException(BaseCode.PARAM_INVALID,
                "任务 %s 当前状态是 %s，只有 %s 的任务能接收澄清答复"
                    .formatted(taskCode, entity.getStatus(), CLARIFYING));
        }

        TaskInfo taskInfo = new TaskInfo();
        taskInfo.setTaskCode(entity.getTaskCode());
        taskInfo.setTraceId(entity.getTraceId());
        taskInfo.setThreadId(entity.getThreadId());
        taskInfo.setRequirement(entity.getRequirement());
        taskInfo.setDatasetPath(entity.getDatasetPath());
        taskInfo.setDatasetCode("DS" + entity.getTaskCode().substring(1));
        request.getAnswers().forEach(
            answer -> taskInfo.getAnswers().put(answer.getClarifyCode(), answer.getAnswer()));

        if (!request.isResume()) {
            return answerOnly(taskInfo);
        }
        log.info("澄清恢复开始 taskCode={} 答复 {} 条", taskCode, taskInfo.getAnswers().size());
        return dispatch(taskInfo, PipelineMode.CLARIFY_RESUME);
    }

    /**
     * 只落答复不恢复链路，供医生分几次答完再一起跑。这条路径不做归一：归一要读画像，
     * 而画像在 checkpoint 里，加载它就等于把恢复跑了一半。
     */
    private CleanTaskResultVO answerOnly(TaskInfo taskInfo) {
        taskInfo.getAnswers().forEach(
            (clarifyCode, answer) -> clarificationStore.saveAnswer(clarifyCode, answer, null));
        CleanTaskResultVO result = new CleanTaskResultVO();
        result.setTaskCode(taskInfo.getTaskCode());
        result.setTraceId(taskInfo.getTraceId());
        result.setStatus(CLARIFYING);
        clarificationStore.loadByTask(taskInfo.getTaskCode()).forEach(item -> {
            CleanTaskResultVO.ClarifyOutcomeView view = new CleanTaskResultVO.ClarifyOutcomeView();
            view.setClarifyCode(item.getClarifyCode());
            view.setTopic(item.getTopic());
            view.setAnswer(item.getAnswer());
            result.getClarifyOutcomes().add(view);
        });
        log.info("澄清答复已落库但未恢复链路 taskCode={}", taskInfo.getTaskCode());
        return result;
    }

    /**
     * 任务作用域的唯一开合点。{@code TraceContext} 是 ThreadLocal，开在 try 之外、
     * 关在 finally 里，保证任何情况下都收口，避免下一个任务的埋点挂到上一个名下。
     */
    private CleanTaskResultVO dispatch(TaskInfo taskInfo, PipelineMode mode) {
        TraceContext.TaskScope scope =
            TraceContext.openTask(taskInfo.getTaskCode(), taskInfo.getTraceId());
        try {
            CleanTaskResultVO result = executorRegistry.get(mode).execute(taskInfo);
            log.info("任务结束 taskCode={} mode={} status={}",
                taskInfo.getTaskCode(), mode, result.getStatus());
            return result;
        }
        catch (RuntimeException exception) {
            // 未预期的失败也要落状态，否则任务永远停在 CREATED
            log.error("任务异常 taskCode={} mode={}", taskInfo.getTaskCode(), mode, exception);
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
