package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.EvalResultEntity;

/** 单用例评测结果。 */
@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResultEntity> {
}
