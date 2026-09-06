package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.PlanStepEntity;

/** 方案步骤。 */
@Mapper
public interface PlanStepMapper extends BaseMapper<PlanStepEntity> {
}
