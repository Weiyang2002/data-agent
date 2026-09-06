package org.dataagent.clean.pipeline.controller;

import jakarta.validation.Valid;
import org.dataagent.clean.pipeline.dto.CleanTaskRequest;
import org.dataagent.clean.pipeline.knowledge.KnowledgeLookup;
import org.dataagent.clean.pipeline.knowledge.KnowledgeRuleService;
import org.dataagent.clean.pipeline.knowledge.RuleType;
import org.dataagent.clean.pipeline.service.CleanTaskService;
import org.dataagent.clean.pipeline.service.TaskTraceService;
import org.dataagent.clean.pipeline.vo.CleanTaskResultVO;
import org.dataagent.clean.pipeline.vo.TaskTraceVO;
import org.dataagent.common.result.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 任务处理主接口。 */
@RestController
@RequestMapping("/api/task")
public class CleanTaskController {

    private final CleanTaskService cleanTaskService;
    private final KnowledgeRuleService knowledgeRuleService;
    private final TaskTraceService taskTraceService;

    public CleanTaskController(CleanTaskService cleanTaskService,
                               KnowledgeRuleService knowledgeRuleService,
                               TaskTraceService taskTraceService) {
        this.cleanTaskService = cleanTaskService;
        this.knowledgeRuleService = knowledgeRuleService;
        this.taskTraceService = taskTraceService;
    }

    /** 发起一次完整处理：画像 → 规划 → 执行 → 校验 */
    @PostMapping("/run")
    public ApiResponse<CleanTaskResultVO> run(@Valid @RequestBody CleanTaskRequest request) {
        return ApiResponse.success(cleanTaskService.run(request));
    }

    /**
     * 按 taskCode 查出完整阶段链路与 Token 分布。stages 回答「依次发生了什么、哪一步
     * 失败了」，tokenByStage 回答「钱花在哪一层」。
     */
    @GetMapping("/{taskCode}/trace")
    public ApiResponse<TaskTraceVO> trace(@PathVariable String taskCode) {
        return ApiResponse.success(taskTraceService.trace(taskCode));
    }

    /**
     * 知识库检索探针，一次调用即可看到三种结局：
     * <pre>
     *   ?column=体温&amp;ruleType=VALIDITY            → RESOLVED
     *   ?column=体温&amp;ruleType=SEVERITY_SCORING   → AMBIGUOUS（NEWS/MEWS/SEWS 并存）
     *   ?column=升压药&amp;ruleType=MISSING_SEMANTICS → NO_EVIDENCE
     * </pre>
     */
    @GetMapping("/knowledge/probe")
    public ApiResponse<Map<String, Object>> probe(@RequestParam String column,
                                                  @RequestParam String ruleType) {
        RuleType type = RuleType.parse(ruleType);
        KnowledgeLookup lookup = knowledgeRuleService.find(column, type);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("column", column);
        view.put("ruleType", ruleType);
        view.put("outcome", lookup.getOutcome().name());
        view.put("hitCount", lookup.getRules().size());
        view.put("ruleIds", lookup.ruleIds());
        view.put("sources", lookup.conflictingSources());
        view.put("action", switch (lookup.getOutcome()) {
            case RESOLVED -> "自动决定，记录 ruleId 作为依据";
            case AMBIGUOUS -> "触发澄清：存在多套标准，系统不挑一条用";
            case NO_EVIDENCE -> "短路返回无依据：不猜测，转为澄清项交给医生";
        });
        return ApiResponse.success(view);
    }

    /** 知识库规模自检，返回 0 说明种子数据没导入 */
    @GetMapping("/knowledge/count")
    public ApiResponse<Long> knowledgeCount() {
        return ApiResponse.success(knowledgeRuleService.countEffective());
    }
}
