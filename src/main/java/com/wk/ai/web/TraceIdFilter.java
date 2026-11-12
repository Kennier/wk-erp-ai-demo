package com.wk.ai.web;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 请求级 traceId：入站优先取外部传入（对齐公司日志平台的 trackId 体系，联调时可直接
 * 在日志平台检索），否则生成；写入 MDC（所有日志自动携带，含 LLM 调用链）并回传响应头。
 *
 * <p>Reactor 流式线程的 MDC 传播由 TracePropagationConfig 注册（micrometer
 * context-propagation）。finally 清理 MDC 防止线程池复用串号。
 */
@Component
public class TraceIdFilter implements Filter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String traceId = Optional.ofNullable(httpRequest.getHeader(HEADER))
                .filter(StringUtils::hasText)
                .orElseGet(() -> UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        httpResponse.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_KEY);
        }
    }
}
