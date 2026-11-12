package com.wk.ai.dto;

import java.util.List;

import org.slf4j.MDC;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wk.ai.web.TraceIdFilter;

/**
 * 流式问答事件帧（SSE data 载荷，容器自动 JSON 序列化）。
 * 单一 record + type 字段 + 静态工厂；NON_NULL 保证各帧只含自己的字段。
 *
 * @param type           帧类型：delta（token 增量）| end（完整回答+引用）| error（生成中断）
 * @param content        delta 帧的 token 增量
 * @param answer         end 帧的完整回答
 * @param citations      end 帧的最终引用（解析回答中 [n] 后确定）
 * @param message        error 帧的提示
 * @param conversationId end 帧携带的会话 ID（SSE 响应头在流开始已发出，首请求新建会话
 *                       时前端只能从帧内拿到 id，故不走响应头）
 * @param traceId        end/error 帧携带的请求 traceId（同响应头 X-Trace-Id）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamFrame(String type, String content,
                              String answer, List<Citation> citations,
                              String message, String conversationId, String traceId) {

    public static ChatStreamFrame delta(String content) {
        return new ChatStreamFrame("delta", content, null, null, null, null, null);
    }

    public static ChatStreamFrame end(String answer, List<Citation> citations, String conversationId) {
        return new ChatStreamFrame("end", null, answer, citations, null, conversationId,
                MDC.get(TraceIdFilter.MDC_KEY));
    }

    public static ChatStreamFrame error(String message) {
        return new ChatStreamFrame("error", null, null, null, message, null,
                MDC.get(TraceIdFilter.MDC_KEY));
    }
}
