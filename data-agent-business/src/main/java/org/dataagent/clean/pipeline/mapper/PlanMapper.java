package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.PlanEntity;

/** 处理方案。 */
@Mapper
public interface PlanMapper extends BaseMapper<PlanEntity> {
}
