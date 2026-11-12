package com.wk.ai.web;

import org.slf4j.MDC;

import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

import jakarta.annotation.PostConstruct;

/**
 * Reactor 流式的 MDC 传播：TraceIdFilter 写入的 MDC 在 Reactor 线程（SSE 流式）
 * 默认会丢——注册 ThreadLocalAccessor 并启用自动上下文传播，使流式链路（含
 * TokenUsageAdvisor 的日志）延续请求 traceId。
 */
@Configuration
public class TracePropagationConfig {

    @PostConstruct
    public void registerTracePropagation() {
        ContextRegistry.getInstance()
                .registerThreadLocalAccessor(
                        TraceIdFilter.MDC_KEY,
                        () -> MDC.get(TraceIdFilter.MDC_KEY),
                        value -> MDC.put(TraceIdFilter.MDC_KEY, value),
                        () -> MDC.remove(TraceIdFilter.MDC_KEY));
        Hooks.enableAutomaticContextPropagation();
    }
}
