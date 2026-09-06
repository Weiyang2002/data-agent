package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.TaskStageEntity;

/** 阶段链路 trace。M5 的 AOP 埋点也写这张表。 */
@Mapper
public interface TaskStageMapper extends BaseMapper<TaskStageEntity> {
}
