package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dataagent.clean.pipeline.data.TaskCheckpointEntity;

/** 澄清中断的业务检查点。 */
@Mapper
public interface TaskCheckpointMapper extends BaseMapper<TaskCheckpointEntity> {

    @Select("SELECT * FROM data_agent_task_checkpoint WHERE task_code = #{taskCode}")
    TaskCheckpointEntity findByTaskCode(@Param("taskCode") String taskCode);
}
