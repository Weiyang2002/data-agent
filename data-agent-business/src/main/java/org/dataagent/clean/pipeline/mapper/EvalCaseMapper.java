package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.dataagent.clean.pipeline.data.EvalCaseEntity;

/** 评测用例目录快照。每轮评测开始前从 Python 侧同步一次。 */
@Mapper
public interface EvalCaseMapper extends BaseMapper<EvalCaseEntity> {
}
