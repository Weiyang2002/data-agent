package org.dataagent.clean.pipeline.controller;

import org.dataagent.clean.prompt.PromptTemplateNames;
import org.dataagent.clean.prompt.PromptTemplateService;
import org.dataagent.clean.toolsclient.client.DataToolsClient;
import org.dataagent.clean.toolsclient.model.ToolsHealthResponse;
import org.dataagent.common.result.ApiResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 冒烟测试接口，验证三条链路是否打通：Spring 容器可响应请求、PromptTemplateService
 * 能渲染 .st 模板、Java 能通过 DataToolsClient 调通 Python 工具服务。
 */
@RestController
@RequestMapping("/api/smoke")
public class SmokeTestController {

    private final PromptTemplateService promptTemplateService;
    private final DataToolsClient dataToolsClient;
    private final ChatClient chatClient;

    public SmokeTestController(PromptTemplateService promptTemplateService,
                               DataToolsClient dataToolsClient,
                               ChatClient.Builder chatClientBuilder) {
        this.promptTemplateService = promptTemplateService;
        this.dataToolsClient = dataToolsClient;
        // 四个 Agent 都通过 ChatClient 调模型，不直接碰 ChatModel
        this.chatClient = chatClientBuilder.build();
    }

    /** 验证 Prompt 模板渲染 */
    @GetMapping("/prompt")
    public ApiResponse<String> prompt() {
        String rendered = promptTemplateService.render(
            PromptTemplateNames.SMOKE_TEST,
            Map.of("columnName", "体温", "ruleType", "VALIDITY")
        );
        return ApiResponse.success(rendered);
    }

    /** 验证 Java → Python 调用 */
    @GetMapping("/tools")
    public ApiResponse<ToolsHealthResponse> tools() {
        return ApiResponse.success(dataToolsClient.health());
    }

    /** 验证大模型调用链路。让模型只回一个词，只证明密钥有效、端点正确、能拿到回复。 */
    @GetMapping("/model")
    public ApiResponse<Map<String, Object>> model() {
        long startedNanos = System.nanoTime();
        String reply = chatClient.prompt()
            .user("只回答两个字，不要任何解释：中国的首都是哪里？")
            .call()
            .content();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply);
        result.put("costMillis", (System.nanoTime() - startedNanos) / 1_000_000L);
        return ApiResponse.success(result);
    }

    /** 一次性验证全部 */
    @GetMapping("/all")
    public ApiResponse<Map<String, Object>> all() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("springContext", "ok");
        try {
            result.put("promptRender", promptTemplateService.render(
                PromptTemplateNames.SMOKE_TEST,
                Map.of("columnName", "体温", "ruleType", "VALIDITY")));
        }
        catch (Exception exception) {
            result.put("promptRender", "FAILED: " + exception.getMessage());
        }
        try {
            result.put("pythonTools", dataToolsClient.health());
        }
        catch (Exception exception) {
            result.put("pythonTools", "FAILED: " + exception.getMessage());
        }
        try {
            result.put("modelReply", chatClient.prompt()
                .user("只回答两个字，不要任何解释：中国的首都是哪里？")
                .call()
                .content());
        }
        catch (Exception exception) {
            result.put("modelReply", "FAILED: " + exception.getMessage());
        }
        return ApiResponse.success(result);
    }
}
