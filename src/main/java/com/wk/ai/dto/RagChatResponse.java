package com.wk.ai.dto;

import java.util.List;

import org.slf4j.MDC;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wk.ai.web.TraceIdFilter;

/**
 * RAG 问答响应。
 *
 * @param answer         模型回答文本（KNOWLEDGE 模式含 [编号] 引用标注）
 * @param citations      回答实际引用的知识片段（仅 KNOWLEDGE 模式；模型未标注编号时返回全部命中）
 * @param question       实际用于检索的问题（预处理/改写后）
 * @param mode           本次回答的链路：KNOWLEDGE 知识问答 | DATA 业务数据查询
 * @param toolCalls      实际发生的工具调用（仅 DATA 模式）
 * @param conversationId 会话 ID（新建会话时为服务端生成的 UUID；未启用记忆时为 null）
 * @param traceId        请求级 traceId（响应头 X-Trace-Id 同值，方便前端直接从 body 取）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagChatResponse(String answer, List<Citation> citations, String question,
                              String mode, List<ToolCallInfo> toolCalls,
                              String conversationId, String traceId) {

    public static RagChatResponse knowledge(String answer, List<Citation> citations,
                                            String question, String conversationId) {
        return new RagChatResponse(answer, citations, question, "KNOWLEDGE", List.of(),
                conversationId, MDC.get(TraceIdFilter.MDC_KEY));
    }

    public static RagChatResponse data(String answer, String question,
                                       List<ToolCallInfo> toolCalls, String conversationId) {
        return new RagChatResponse(answer, List.of(), question, "DATA", toolCalls,
                conversationId, MDC.get(TraceIdFilter.MDC_KEY));
    }
}
