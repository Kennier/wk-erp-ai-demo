package com.wk.ai.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import com.wk.ai.config.ConversationMemoryProperties;
import com.wk.ai.dto.ChatTurn;

/**
 * 服务端会话记忆封装：滑动窗口保存最近 N 条消息（默认 10 = 5 轮），
 * 惰性 TTL 清理（读写时顺带扫描过期会话，不引入 @Scheduled）。
 *
 * <p>手动读写而非 advisor 挂载：RagChatService 手工组装 prompt
 * （改写器与生成共用同一份历史），advisor 形式会与手动 prompt 冲突。
 * 存储为内存实现（与内存向量库同节奏），上生产换 JDBC Repository。
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private final ChatMemory chatMemory;
    /** 只读视图：供生成链路的 MessageChatMemoryAdvisor 注入历史——
     *  advisor 的 after 阶段会把整段 user prompt 写回记忆（污染历史），故 add 置空，
     *  写回由 {@link #record(String, String, String)}（用户原始问题）控制 */
    private final ChatMemory readOnlyMemory = new ChatMemory() {
        @Override
        public void add(String conversationId, List<Message> messages) {
            // 只读：生成链路禁止写回，写入统一走 record()
        }

        @Override
        public List<Message> get(String conversationId) {
            return chatMemory.get(conversationId);
        }

        @Override
        public void clear(String conversationId) {
            chatMemory.clear(conversationId);
        }
    };
    private final ConversationMemoryProperties properties;
    /** convId -> 最后访问时间，TTL 惰性清理 */
    private final Map<String, Instant> lastAccess = new ConcurrentHashMap<>();

    public ConversationMemoryService(ConversationMemoryProperties properties) {
        this.properties = properties;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(properties.maxMessages())
                .build();
    }

    /** 只读 ChatMemory 视图：挂给生成链路的 MessageChatMemoryAdvisor */
    public ChatMemory readOnlyMemory() {
        return readOnlyMemory;
    }

    public record SessionSummary(String conversationId, String title, int rounds,
                                 Instant lastAccessAt, Instant expiresAt) {
    }

    /** 会话列表（管理面）：按最后访问时间倒序；title = 首轮问题截断 30 字 */
    public List<SessionSummary> listSessions() {
        evictExpired();
        Instant now = Instant.now();
        return lastAccess.entrySet().stream()
                .map(entry -> {
                    String conversationId = entry.getKey();
                    Instant accessedAt = entry.getValue();
                    List<Message> messages = chatMemory.get(conversationId);
                    String title = messages.stream()
                            .filter(UserMessage.class::isInstance)
                            .map(m -> ((UserMessage) m).getText())
                            .findFirst()
                            .map(t -> t.length() > 30 ? t.substring(0, 30) + "…" : t)
                            .orElse("(空会话)");
                    return new SessionSummary(conversationId, title,
                            messages.size() / 2, accessedAt,
                            accessedAt.plusSeconds(properties.ttlMinutes() * 60L));
                })
                .sorted((a, b) -> b.lastAccessAt().compareTo(a.lastAccessAt()))
                .toList();
    }

    /** 删除单个会话（管理面）；不存在返回 false */
    public boolean delete(String conversationId) {
        boolean existed = lastAccess.remove(conversationId) != null;
        chatMemory.clear(conversationId);
        return existed;
    }

    /** 会话 ID 解析：请求未带时生成新 UUID；开关关闭时返回 null（不启用记忆） */
    public String resolve(String requestedId) {
        evictExpired();
        if (!properties.enabled()) {
            return null;
        }
        if (requestedId != null && !requestedId.isBlank()) {
            return requestedId.trim();
        }
        return UUID.randomUUID().toString();
    }

    /** 读取窗口历史并转为改写器用的 ChatTurn 列表（旧→新） */
    public List<ChatTurn> historyAsTurns(String conversationId) {
        evictExpired();
        List<Message> messages = chatMemory.get(conversationId);
        List<ChatTurn> turns = new ArrayList<>();
        String pendingQuestion = null;
        for (Message message : messages) {
            if (message instanceof UserMessage user) {
                pendingQuestion = user.getText();
            }
            else if (message instanceof AssistantMessage assistant && pendingQuestion != null) {
                turns.add(new ChatTurn(pendingQuestion, assistant.getText()));
                pendingQuestion = null;
            }
        }
        return turns;
    }

    /** 记录一轮问答（窗口截断由 MessageWindowChatMemory 在 add 时执行） */
    public void record(String conversationId, String question, String answer) {
        evictExpired();
        chatMemory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(answer)));
        lastAccess.put(conversationId, Instant.now());
    }

    /** 惰性清理过期会话（每次读写触发，会话量级小无压力） */
    private void evictExpired() {
        Instant threshold = Instant.now().minusSeconds(properties.ttlMinutes() * 60L);
        lastAccess.forEach((conversationId, accessedAt) -> {
            if (isExpired(accessedAt, threshold)) {
                chatMemory.clear(conversationId);
                lastAccess.remove(conversationId);
                log.debug("会话过期清理: {}", conversationId);
            }
        });
    }

    /** 过期判定（包可见供单测）：最后访问时间早于等于阈值即视为过期 */
    boolean isExpired(Instant accessedAt, Instant threshold) {
        return !accessedAt.isAfter(threshold);
    }
}
