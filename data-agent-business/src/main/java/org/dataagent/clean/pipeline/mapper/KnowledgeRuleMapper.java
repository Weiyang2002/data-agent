package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dataagent.clean.pipeline.data.KnowledgeRuleEntity;

import java.util.List;

/**
 * 知识库检索，全项目 RAG 检索的唯一入口。检索层就是几条 SQL 精确查询：知识库只有
 * 44 条强结构化规则，每条都有确定的 {@code (列名, 规则类型, 评分系统)} 主键，多路
 * 召回在这个规模上只增加不确定性。
 */
@Mapper
public interface KnowledgeRuleMapper extends BaseMapper<KnowledgeRuleEntity> {

    /**
     * 按规范列名 + 规则类型精确查询。返回 List 而非单个对象：命中几条本身就是信息
     * （1 条自动决定 / &gt;1 条歧义澄清 / 0 条 NO_EVIDENCE 短路），不用 {@code LIMIT 1}
     * 把歧义压成有答案。
     */
    @Select("""
        SELECT * FROM data_agent_knowledge_rule
        WHERE column_name = #{columnName}
          AND rule_type = #{ruleType}
          AND effective_flag = 1
        ORDER BY scoring_system, version DESC, id
        """)
    List<KnowledgeRuleEntity> findByColumnAndType(@Param("columnName") String columnName,
                                                  @Param("ruleType") String ruleType);

    /** 指定评分系统的精确查询，医生已说明「按 NEWS 评分」时走这条。 */
    @Select("""
        SELECT * FROM data_agent_knowledge_rule
        WHERE column_name = #{columnName}
          AND rule_type = #{ruleType}
          AND scoring_system = #{scoringSystem}
          AND effective_flag = 1
        ORDER BY version DESC
        """)
    List<KnowledgeRuleEntity> findByColumnTypeAndSystem(@Param("columnName") String columnName,
                                                        @Param("ruleType") String ruleType,
                                                        @Param("scoringSystem") String scoringSystem);

    /** 全量取生效规则，供 {@code ColumnResolver} 构建别名索引（一次性载入内存）。 */
    @Select("SELECT * FROM data_agent_knowledge_rule WHERE effective_flag = 1")
    List<KnowledgeRuleEntity> findAllEffective();
}
