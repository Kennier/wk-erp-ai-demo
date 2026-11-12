package com.wk.ai.controller;

import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.wk.ai.dto.RagChatRequest;
import com.wk.ai.dto.RagChatResponse;
import com.wk.ai.dto.ChatStreamFrame;
import com.wk.ai.service.ChatOrchestrator;
import com.wk.ai.service.ConversationMemoryService;
import com.wk.ai.service.InputGuardService;
import com.wk.ai.service.IntentRouter;
import com.wk.ai.service.RagChatService;
import com.wk.ai.service.ToolChatService;

import reactor.core.publisher.Flux;

/**
 * 问答统一入口：意图路由分发——KNOWLEDGE 走 RAG 链路，DATA 走 ERP 工具链路。
 * 流式端点仅覆盖 KNOWLEDGE 链路（DATA 链路流式为二期，见 streaming-design.md §6）。
 */
@RestController
@RequestMapping("/api/chat")
public class RagChatController {

    private final RagChatService ragChatService;
    private final IntentRouter intentRouter;
    private final ConversationMemoryService conversationMemory;
    private final InputGuardService inputGuardService;
    private final ChatOrchestrator chatOrchestrator;

    public RagChatController(RagChatService ragChatService,
                             IntentRouter intentRouter,
                             ConversationMemoryService conversationMemory,
                             InputGuardService inputGuardService,
                             ChatOrchestrator chatOrchestrator) {
        this.ragChatService = ragChatService;
        this.intentRouter = intentRouter;
        this.conversationMemory = conversationMemory;
        this.inputGuardService = inputGuardService;
        this.chatOrchestrator = chatOrchestrator;
    }

    @PostMapping
    public RagChatResponse chat(@RequestBody @Valid RagChatRequest request) {
        // Prompt 注入防护（L1 输入拦截）：命中直接 400 拒绝，不打 LLM
        var guard = inputGuardService.check(request.question());
        if (guard.blocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, guard.reason());
        }
        // 会话 ID 解析：未传时生成新 UUID（随响应返回，后续轮次回传即可启用记忆）
        String conversationId = conversationMemory.resolve(request.conversationId());
        // 多意图路由：编排器分发——单意图直通对应链路，双意图（混合问题）并行编排汇总
        List<String> intents = intentRouter.classifyIntents(request.question());
        return chatOrchestrator.orchestrate(request, conversationId, intents);
    }

    /**
     * 流式问答（SSE）：delta 帧逐 token 推送，end 帧携带完整回答、引用与会话 ID。
     * X-Accel-Buffering: no 防止 Nginx 代理缓冲导致打字机效果失效。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<ChatStreamFrame>>> chatStream(
            @RequestBody @Valid RagChatRequest request) {
        var guard = inputGuardService.check(request.question());
        if (guard.blocked()) {
            return ResponseEntity.badRequest().build();
        }
        String conversationId = conversationMemory.resolve(request.conversationId());
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header("Cache-Control", "no-cache")
                .body(ragChatService.chatStream(request, conversationId));
    }
}
