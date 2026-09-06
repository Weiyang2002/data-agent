package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.ColumnProfileEntity;

/** 列画像快照，处理前后各一份。 */
@Mapper
public interface ColumnProfileMapper extends BaseMapper<ColumnProfileEntity> {
}
