package org.dataagent.clean.pipeline.executor;

import org.dataagent.common.exception.BusinessException;
import org.dataagent.common.result.BaseCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 执行器注册表。构造器注入 {@code List<PipelineExecutor>}，按 {@code mode()} 建索引，
 * 新增模式只需加一个 Bean。启动时检查模式重复。
 */
@Component
public class PipelineExecutorRegistry {

    private final Map<PipelineMode, PipelineExecutor> executors = new EnumMap<>(PipelineMode.class);

    public PipelineExecutorRegistry(List<PipelineExecutor> executorList) {
        for (PipelineExecutor executor : executorList) {
            PipelineExecutor existing = executors.putIfAbsent(executor.mode(), executor);
            if (existing != null) {
                throw new IllegalStateException(
                    "执行模式 %s 有两个实现：%s 与 %s".formatted(
                        executor.mode(),
                        existing.getClass().getSimpleName(),
                        executor.getClass().getSimpleName()));
            }
        }
    }

    public PipelineExecutor get(PipelineMode mode) {
        PipelineExecutor executor = executors.get(mode);
        if (executor == null) {
            throw new BusinessException(BaseCode.PARAM_INVALID, "尚未实现的执行模式: " + mode);
        }
        return executor;
    }
}
