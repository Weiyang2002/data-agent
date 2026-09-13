package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dataagent.clean.pipeline.data.ClarificationEntity;

import java.util.List;

/** 澄清项。查询按 coverage_ratio 降序。 */
@Mapper
public interface ClarificationMapper extends BaseMapper<ClarificationEntity> {

    /** 按覆盖率降序：医生从上往下答，中途停止也已覆盖绝大部分数据。 */
    @Select("""
        SELECT * FROM data_agent_clarification
        WHERE task_code = #{taskCode}
        ORDER BY coverage_ratio DESC, topic
        """)
    List<ClarificationEntity> findByTaskCode(@Param("taskCode") String taskCode);

    @Select("SELECT * FROM data_agent_clarification WHERE clarify_code = #{clarifyCode}")
    ClarificationEntity findByClarifyCode(@Param("clarifyCode") String clarifyCode);
}
