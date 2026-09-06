package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.ValidationFindingEntity;

/** 三层校验发现。M3 指标的主要数据源。 */
@Mapper
public interface ValidationFindingMapper extends BaseMapper<ValidationFindingEntity> {
}
