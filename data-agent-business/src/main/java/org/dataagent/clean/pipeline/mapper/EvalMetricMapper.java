package org.dataagent.clean.pipeline.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 指标聚合查询，从链路表里把指标查出来。每条 SQL 都走
 * {@code JOIN data_agent_eval_result} 按 run_code 收口，口径写在 SQL 里而非 Java 侧。
 * 不建汇总表，每次现算与明细一致。
 */
@Mapper
public interface EvalMetricMapper {

    /**
     * 自修复成功率的分子分母。口径是步骤而非执行行，用 {@code (task_code, step_no)}
     * 去重（一个步骤重试多次会落多行）。
     *
     * @return repairedSteps = 发生过自修复的步骤数；repairedOk = 其中最终成功的
     */
    @Select("""
        SELECT
          COUNT(DISTINCT CASE WHEN e.retry_no > 0
                THEN CONCAT(e.task_code, '#', e.step_no) END)                 AS repairedSteps,
          COUNT(DISTINCT CASE WHEN e.retry_no > 0 AND e.success = 1
                THEN CONCAT(e.task_code, '#', e.step_no) END)                 AS repairedOk,
          COUNT(*)                                                            AS attemptRows
        FROM data_agent_execution e
        JOIN data_agent_eval_result r ON r.task_code = e.task_code
        WHERE r.run_code = #{runCode}
        """)
    Map<String, Object> selectRepairStats(@Param("runCode") String runCode);

    /**
     * 每个任务的端到端耗时。用各阶段 {@code cost_millis} 求和而非
     * {@code MAX(end_time)-MIN(start_time)}（DATETIME 只到秒）。只求和 {@code depth = 0}：
     * 子阶段耗时已含在父阶段里。
     */
    @Select("""
        SELECT s.task_code AS taskCode, SUM(s.cost_millis) AS totalMillis
        FROM data_agent_task_stage s
        JOIN data_agent_eval_result r ON r.task_code = s.task_code
        WHERE r.run_code = #{runCode} AND s.depth = 0
        GROUP BY s.task_code
        ORDER BY totalMillis
        """)
    List<Map<String, Object>> selectTaskLatencies(@Param("runCode") String runCode);

    /**
     * 一轮评测的 Token 总账。{@code SUM(total_tokens)} 不重复计算（每行记的是本阶段
     * 自身的消耗）。{@code taskCount} 单独查出，用于区分「Token 是 0」和「本轮没有
     * 账本」；{@code usageMissingCalls} 大于 0 说明总量是下界。
     */
    @Select("""
        SELECT COALESCE(SUM(b.total_tokens), 0)        AS totalTokens,
               COALESCE(SUM(b.prompt_tokens), 0)       AS promptTokens,
               COALESCE(SUM(b.completion_tokens), 0)   AS completionTokens,
               COALESCE(SUM(b.model_calls), 0)         AS modelCalls,
               COALESCE(SUM(b.usage_missing_calls), 0) AS usageMissingCalls,
               COUNT(DISTINCT b.task_code)             AS taskCount
        FROM data_agent_stage_benchmark b
        JOIN data_agent_eval_result r ON r.task_code = b.task_code
        WHERE r.run_code = #{runCode}
        """)
    Map<String, Object> selectTokenStats(@Param("runCode") String runCode);

    /** Token 的按阶段分布。CODEGEN 和 REPAIR 分行列出，可直接读出自修复烧了多少 Token。 */
    @Select("""
        SELECT b.stage_code                            AS stageCode,
               MIN(b.stage_order)                      AS stageOrder,
               MIN(b.depth)                            AS depth,
               COALESCE(SUM(b.enter_count), 0)         AS enterCount,
               COALESCE(SUM(b.model_calls), 0)         AS modelCalls,
               COALESCE(SUM(b.total_tokens), 0)        AS totalTokens,
               COALESCE(SUM(b.usage_missing_calls), 0) AS usageMissingCalls,
               COALESCE(SUM(b.self_cost_millis), 0)    AS selfCostMillis
        FROM data_agent_stage_benchmark b
        JOIN data_agent_eval_result r ON r.task_code = b.task_code
        WHERE r.run_code = #{runCode}
        GROUP BY b.stage_code
        ORDER BY MIN(b.stage_order)
        """)
    List<Map<String, Object>> selectTokenByStage(@Param("runCode") String runCode);

    /**
     * 规范遵从率的按层明细。必须按层分组：分布级 / 结构级 / 常识级本来就没有院内
     * 规范可引，{@code source_rule_id} 为空是正确行为，混在一起统计会误导。
     */
    @Select("""
        SELECT f.level                                       AS level,
               COUNT(*)                                      AS findingCount,
               SUM(CASE WHEN f.source_rule_id IS NOT NULL
                        AND f.source_rule_id <> '' THEN 1 ELSE 0 END) AS withRuleCount
        FROM data_agent_validation_finding f
        JOIN data_agent_eval_result r ON r.task_code = f.task_code
        WHERE r.run_code = #{runCode} AND f.phase = 'AFTER'
        GROUP BY f.level
        """)
    List<Map<String, Object>> selectComplianceByLevel(@Param("runCode") String runCode);
}
