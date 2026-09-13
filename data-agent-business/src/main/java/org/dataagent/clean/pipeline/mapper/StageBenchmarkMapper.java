package org.dataagent.clean.pipeline.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dataagent.clean.pipeline.data.StageBenchmarkEntity;

/** 阶段耗时 / Token 账本（M5）。 */
@Mapper
public interface StageBenchmarkMapper extends BaseMapper<StageBenchmarkEntity> {

    /**
     * 累加式落账。唯一键是 (task_code, stage_code)，而一个任务可以跑不止一轮
     * （首轮停在澄清，答复后再跑一轮），直接 insert 第二轮会撞唯一键把账丢掉。
     * 累加而不是分行：账本回答的是「这个任务一共花了多少」，轮次明细在 task_stage
     * 流水里。
     */
    @Insert("""
        INSERT INTO data_agent_stage_benchmark
            (task_code, trace_id, stage_code, stage_order, depth, enter_count, model_calls,
             prompt_tokens, completion_tokens, total_tokens, usage_missing_calls,
             self_cost_millis, total_cost_millis)
        VALUES
            (#{e.taskCode}, #{e.traceId}, #{e.stageCode}, #{e.stageOrder}, #{e.depth},
             #{e.enterCount}, #{e.modelCalls}, #{e.promptTokens}, #{e.completionTokens},
             #{e.totalTokens}, #{e.usageMissingCalls}, #{e.selfCostMillis}, #{e.totalCostMillis})
        ON DUPLICATE KEY UPDATE
            enter_count         = enter_count + VALUES(enter_count),
            model_calls         = model_calls + VALUES(model_calls),
            prompt_tokens       = prompt_tokens + VALUES(prompt_tokens),
            completion_tokens   = completion_tokens + VALUES(completion_tokens),
            total_tokens        = total_tokens + VALUES(total_tokens),
            usage_missing_calls = usage_missing_calls + VALUES(usage_missing_calls),
            self_cost_millis    = self_cost_millis + VALUES(self_cost_millis),
            total_cost_millis   = total_cost_millis + VALUES(total_cost_millis)
        """)
    int accumulate(@Param("e") StageBenchmarkEntity entity);
}
