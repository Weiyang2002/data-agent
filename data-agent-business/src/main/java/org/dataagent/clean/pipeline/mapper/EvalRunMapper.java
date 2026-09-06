package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.EvalRunEntity;

/** 评测轮次。M4 的指标序列就是这张表按 start_time 排序。 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRunEntity> {
}
