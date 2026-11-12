package com.wk.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.stereotype.Service;

import com.wk.ai.config.ErpToolProperties;
import com.wk.ai.dto.RagChatRequest;
import com.wk.ai.dto.RagChatResponse;
import com.wk.ai.web.TraceIdFilter;

/**
 * 双路由并行编排：KNOWLEDGE 单意图直通 RAG 链路、DATA 单意图直通工具链路
 * （行为与改造前完全一致）；双意图时两条专家链路并行执行（虚拟线程），
 * 汇总员整合为一个连贯回答。
 *
 * <p>关键机制：
 * <ul>
 *   <li>失败隔离：一路异常 → 该路以降级说明占位，另一路照常返回</li>
 *   <li>MDC 快照恢复：CompletableFuture 的异步线程不走 Reactor 传播，
 *       手动快照/恢复使并行链路的日志延续请求 traceId</li>
 *   <li>记忆防重：子链路 conversationId=null（不读写记忆），编排层统一 record 汇总回答——
 *       否则知识路 record 子答案 + 编排层 record 汇总 = 重复记录</li>
 * </ul>
 */
@Service
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final RagChatService ragChatService;
    private final ToolChatService toolChatService;
    private final DualSummarizer dualSummarizer;
    private final ConversationMemoryService conversationMemory;
    private final ErpToolProperties erpToolProperties;
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ChatOrchestrator(RagChatService ragChatService, ToolChatService toolChatService,
                            DualSummarizer dualSummarizer, ConversationMemoryService conversationMemory,
                            ErpToolProperties erpToolProperties) {
        this.ragChatService = ragChatService;
        this.toolChatService = toolChatService;
        this.dualSummarizer = dualSummarizer;
        this.conversationMemory = conversationMemory;
        this.erpToolProperties = erpToolProperties;
    }

    public RagChatResponse orchestrate(RagChatRequest request, String conversationId, List<String> intents) {
        List<String> effective = effectiveIntents(intents);
        if (effective.size() == 1) {
            // 单意图：直通对应链路（与历史行为一致）
            return IntentRouter.DATA.equals(effective.get(0))
                    ? toolChatService.chat(request, conversationId)
                    : ragChatService.chat(request, conversationId);
        }
        return orchestrateDual(request, conversationId);
    }

    /** 双路并行：KNOWLEDGE 与 DATA 同时执行，汇总员整合 */
    private RagChatResponse orchestrateDual(RagChatRequest request, String conversationId) {
        // 会话历史由编排层统一读取（子链路 conversationId=null，避免子链路写记忆）
        List<com.wk.ai.dto.ChatTurn> history = conversationId != null
                ? conversationMemory.historyAsTurns(conversationId)
                : request.history();
        RagChatRequest subRequest = new RagChatRequest(request.question(), request.topK(),
                request.threshold(), history, request.department());

        long start = System.currentTimeMillis();
        Map<String, String> mdc = MDC.getCopyOfContextMap();

        CompletableFuture<RagChatResponse> knowledgeFuture = CompletableFuture
                .supplyAsync(mdcPreserving(() -> ragChatService.chat(subRequest, null)), virtualExecutor);
        CompletableFuture<RagChatResponse> dataFuture = CompletableFuture
                .supplyAsync(mdcPreserving(() -> toolChatService.chat(subRequest, null)), virtualExecutor);

        RagChatResponse knowledgeResult = joinIsolated(knowledgeFuture, "知识库");
        RagChatResponse dataResult = joinIsolated(dataFuture, "业务数据");

        String mergedAnswer = dualSummarizer.summarize(
                request.question(), knowledgeResult.answer(), dataResult.answer());

        if (conversationId != null) {
            conversationMemory.record(conversationId, request.question().trim(), mergedAnswer);
        }
        log.info("DUAL 链路完成: 耗时 {} ms, token 曲线见 llm.tokens.*", System.currentTimeMillis() - start);
        return new RagChatResponse(mergedAnswer, knowledgeResult.citations(), request.question(),
                "DUAL", dataResult.toolCalls(), conversationId, currentTraceId());
    }

    /** intents 修正：ERP 工具开关关闭时剔除 DATA（开关语义与现状一致） */
    private List<String> effectiveIntents(List<String> intents) {
        List<String> effective = new ArrayList<>(intents);
        if (!erpToolProperties.enabled()) {
            effective.remove(IntentRouter.DATA);
        }
        if (effective.isEmpty()) {
            effective.add(IntentRouter.KNOWLEDGE);
        }
        return effective;
    }

    /** 失败隔离：一路异常不拖死整体，以降级说明占位参与汇总 */
    private RagChatResponse joinIsolated(CompletableFuture<RagChatResponse> future, String subsystem) {
        try {
            return future.get(120, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            return RagChatResponse.knowledge(subsystem + "查询超时。", List.of(), "", null);
        }
        catch (Exception e) {
            log.error("{} 链路执行失败，降级占位", subsystem, e);
            return RagChatResponse.knowledge(subsystem + "暂时不可用。", List.of(), "", null);
        }
    }

    /** MDC 快照恢复：异步线程延续请求 traceId */
    private <T> java.util.function.Supplier<T> mdcPreserving(java.util.function.Supplier<T> delegate) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                return delegate.get();
            }
            finally {
                MDC.remove(TraceIdFilter.MDC_KEY);
            }
        };
    }

    private String currentTraceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
