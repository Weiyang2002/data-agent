package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dataagent.clean.pipeline.data.TaskEntity;

/** 任务主表。 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

    @Select("SELECT * FROM data_agent_task WHERE task_code = #{taskCode}")
    TaskEntity findByTaskCode(@Param("taskCode") String taskCode);
}
