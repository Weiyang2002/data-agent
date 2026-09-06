package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.ExecutionEntity;

/** 沙箱执行记录，含全部自修复轮次。 */
@Mapper
public interface ExecutionMapper extends BaseMapper<ExecutionEntity> {
}
