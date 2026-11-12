package com.wk.ai.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Flux;

/**
 * Token 用量统计：拦截链路上所有 LLM 调用（含改写/扩展等框架内部调用与
 * DATA 链路 tool-call 多轮循环），从 ChatResponse 的 Usage 读取 token 数，
 * 写入 Micrometer counter（评测报告按前后差值聚合单条用例消耗；线上按 tag 聚合观测）
 * 并打结构化日志（单次排查用）。
 *
 * <p>挂载方式：ChatClient builder 的 defaultAdvisors——mutate 的子链（改写器/扩展器）
 * 会继承，故一处挂载全链路覆盖。流式场景 usage 只在聚合响应（最后一个 ChatResponse）出现，
 * 逐元素检查非空即记录。
 *
 * <p>注意：Spring AI 2.0 的 advisor 接口为 CallAdvisor/StreamAdvisor
 * （1.x 的 CallAroundAdvisor/StreamAroundAdvisor 已更名）。
 */
public class TokenUsageAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageAdvisor.class);

    private final MeterRegistry meterRegistry;

    public TokenUsageAdvisor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getName() {
        return "TokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        recordUsage(response == null ? null : response.chatResponse());
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(request)
                .doOnNext(response -> recordUsage(response == null ? null : response.chatResponse()));
    }

    private void recordUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        Integer total = usage.getTotalTokens();
        if (total == null) {
            return;
        }
        meterRegistry.counter("llm.tokens.total").increment(total);
        if (prompt != null) {
            meterRegistry.counter("llm.tokens.prompt").increment(prompt);
        }
        if (completion != null) {
            meterRegistry.counter("llm.tokens.completion").increment(completion);
        }
        log.info("LLM token 用量: prompt={}, completion={}, total={}", prompt, completion, total);
    }
}
