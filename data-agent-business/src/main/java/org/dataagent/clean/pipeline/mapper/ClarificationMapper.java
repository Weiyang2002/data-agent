package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.ClarificationEntity;

/** 澄清项。查询按 coverage_ratio 降序。 */
@Mapper
public interface ClarificationMapper extends BaseMapper<ClarificationEntity> {
}
