package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.StageBenchmarkEntity;

/** 阶段耗时 / Token 账本（M5）。 */
@Mapper
public interface StageBenchmarkMapper extends BaseMapper<StageBenchmarkEntity> {
}
