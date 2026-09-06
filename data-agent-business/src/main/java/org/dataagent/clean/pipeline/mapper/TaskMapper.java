package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.TaskEntity;

/** 任务主表。 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
}
