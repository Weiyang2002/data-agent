package org.dataagent.clean.pipeline.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.validation.Valid;
import org.dataagent.clean.pipeline.data.EvalResultEntity;
import org.dataagent.clean.pipeline.data.EvalRunEntity;
import org.dataagent.clean.pipeline.dto.EvalRunRequest;
import org.dataagent.clean.pipeline.eval.EvalRunner;
import org.dataagent.clean.pipeline.eval.FailureCategory;
import org.dataagent.clean.pipeline.mapper.EvalResultMapper;
import org.dataagent.clean.pipeline.mapper.EvalRunMapper;
import org.dataagent.clean.pipeline.vo.EvalRunReportVO;
import org.dataagent.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测轮次接口。{@code /run} 是长请求（每个用例含数据集生成 + 完整链路，十几分钟起），
 * 中途断开不丢数据，每个用例跑完立刻落 {@code data_agent_eval_result}。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalRunController {

    private final EvalRunner evalRunner;
    private final EvalRunMapper evalRunMapper;
    private final EvalResultMapper evalResultMapper;

    public EvalRunController(EvalRunner evalRunner,
                             EvalRunMapper evalRunMapper,
                             EvalResultMapper evalResultMapper) {
        this.evalRunner = evalRunner;
        this.evalRunMapper = evalRunMapper;
        this.evalResultMapper = evalResultMapper;
    }

    /** 发起一轮评测。{@code changeNote} 必填（校验在 DTO 上），否则指标序列无法解释。 */
    @PostMapping("/run")
    public ApiResponse<EvalRunReportVO> run(@Valid @RequestBody EvalRunRequest request) {
        return ApiResponse.success(evalRunner.run(request));
    }

    /** 取某一轮的指标快照与全部单用例结果 */
    @GetMapping("/run/{runCode}")
    public ApiResponse<Map<String, Object>> report(@PathVariable String runCode) {
        EvalRunEntity run = evalRunMapper.selectOne(
            new QueryWrapper<EvalRunEntity>().eq("run_code", runCode));
        List<EvalResultEntity> results = evalResultMapper.selectList(
            new QueryWrapper<EvalResultEntity>().eq("run_code", runCode).orderByAsc("case_id"));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("run", run);
        view.put("results", results);
        return ApiResponse.success(view);
    }

    /** 全部轮次，按时间倒序，用于查看指标序列 */
    @GetMapping("/runs")
    public ApiResponse<List<EvalRunEntity>> runs() {
        return ApiResponse.success(evalRunMapper.selectList(
            new QueryWrapper<EvalRunEntity>().orderByDesc("start_time")));
    }

    /** 归因分类字典，用于解释归因直方图里每一簇的含义。 */
    @GetMapping("/failure-categories")
    public ApiResponse<List<Map<String, Object>>> failureCategories() {
        return ApiResponse.success(java.util.Arrays.stream(FailureCategory.values())
            .map(category -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", category.name());
                item.put("label", category.getLabel());
                item.put("meaning", category.getMeaning());
                item.put("countedAsPass", category.isPass());
                return item;
            })
            .toList());
    }
}
