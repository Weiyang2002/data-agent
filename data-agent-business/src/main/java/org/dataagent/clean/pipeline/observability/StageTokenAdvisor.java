package org.dataagent.clean.pipeline.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;

/**
 * 模型调用拦截器：把每次调用的 Token 用量记到当前阶段上。用 Advisor 而非在每个
 * 调用点手写，新加的调用点自动被覆盖。
 *
 * <p>模型没回 usage 时 Token 计 0，同时 {@code usageMissingCalls} 加一，区别于
 * 「没调模型」，一路带到 {@code data_agent_stage_benchmark} 和指标口径说明里。
 * 不记 Prompt 正文和响应正文，只记数字。
 */
public class StageTokenAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(StageTokenAdvisor.class);

    @Override
    public String getName() {
        return "stageTokenAdvisor";
    }

    /** 站在整条 advisor 链的最外层，量「这次业务调用一共花了多少 Token」。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        try {
            collect(response);
        }
        catch (Exception exception) {
            // 采集失败不影响模型调用的返回
            log.warn("Token 采集失败（不影响业务）：{}", exception.getMessage());
        }
        return response;
    }

    private void collect(ChatClientResponse response) {
        if (!TraceContext.inTask()) {
            // 不属于任何任务的调用（如 smoke 自检），不记账
            return;
        }
        Usage usage = usageOf(response);
        if (usage == null || usage.getTotalTokens() == null) {
            TraceContext.recordModelCall(0, 0, 0, true);
            log.warn("模型未返回 usage 元数据，本次 Token 记为 0 并单独计数，该阶段 Token 是下界");
            return;
        }
        TraceContext.recordModelCall(
            orZero(usage.getPromptTokens()),
            orZero(usage.getCompletionTokens()),
            orZero(usage.getTotalTokens()),
            false);
    }

    private Usage usageOf(ChatClientResponse response) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return null;
        }
        return chatResponse.getMetadata().getUsage();
    }

    private long orZero(Integer value) {
        return value == null ? 0L : value.longValue();
    }
}
