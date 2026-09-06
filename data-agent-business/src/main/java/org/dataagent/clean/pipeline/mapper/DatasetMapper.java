package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.DatasetEntity;

/** 数据集登记。 */
@Mapper
public interface DatasetMapper extends BaseMapper<DatasetEntity> {
}
