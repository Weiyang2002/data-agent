package org.dataagent.clean.pipeline.clarify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dataagent.clean.pipeline.data.TaskCheckpointEntity;
import org.dataagent.clean.pipeline.mapper.TaskCheckpointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 业务检查点的读写。
 *
 * <p>写失败要喊出来并让上层把任务标成不可恢复：其余落库（trace、执行记录）失败只
 * 影响可观测，checkpoint 失败直接决定医生答完还能不能继续。
 */
@Component
public class TaskCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(TaskCheckpointStore.class);

    private final TaskCheckpointMapper checkpointMapper;
    private final ObjectMapper objectMapper;

    public TaskCheckpointStore(TaskCheckpointMapper checkpointMapper,
                               ObjectMapper objectMapper) {
        this.checkpointMapper = checkpointMapper;
        this.objectMapper = objectMapper;
    }

    /** @return 是否写成功；false 表示这个任务答复后无法恢复 */
    public boolean save(String traceId, String stage, TaskCheckpoint checkpoint) {
        try {
            String payload = objectMapper.writeValueAsString(checkpoint);
            TaskCheckpointEntity existing =
                checkpointMapper.findByTaskCode(checkpoint.taskCode());
            if (existing == null) {
                TaskCheckpointEntity entity = new TaskCheckpointEntity();
                entity.setTaskCode(checkpoint.taskCode());
                entity.setTraceId(traceId);
                entity.setStage(stage);
                entity.setPayloadJson(payload);
                checkpointMapper.insert(entity);
            }
            else {
                existing.setTraceId(traceId);
                existing.setStage(stage);
                existing.setPayloadJson(payload);
                checkpointMapper.updateById(existing);
            }
            log.info("checkpoint 已写入 taskCode={} stage={} 大小={} 字节",
                checkpoint.taskCode(), stage, payload.length());
            return true;
        }
        catch (Exception exception) {
            log.error("checkpoint 写入失败，taskCode={} 答复后将无法恢复",
                checkpoint.taskCode(), exception);
            return false;
        }
    }

    public Optional<TaskCheckpoint> load(String taskCode) {
        TaskCheckpointEntity entity = checkpointMapper.findByTaskCode(taskCode);
        if (entity == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                objectMapper.readValue(entity.getPayloadJson(), TaskCheckpoint.class));
        }
        catch (Exception exception) {
            log.error("checkpoint 解析失败 taskCode={}", taskCode, exception);
            return Optional.empty();
        }
    }
}
