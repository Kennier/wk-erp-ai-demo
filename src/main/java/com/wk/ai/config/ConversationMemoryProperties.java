package com.wk.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话记忆配置。
 *
 * @param enabled     会话记忆开关；false 时 conversationId 仅透传、不启用服务端记忆
 * @param maxMessages 滑动窗口消息条数（user+assistant 各一条），默认 10 = 最近 5 轮
 * @param ttlMinutes  会话过期时间（分钟），惰性清理：读写时扫描过期会话
 * @param inGeneration 生成阶段是否注入会话历史（经 MessageChatMemoryAdvisor 以结构化
 *                     messages 注入，默认关 = 严格 grounded，检索片段为唯一回答依据）；
 *                     注入量由 max-messages 窗口控制，写回经只读视图屏蔽、统一走 record()
 */
@ConfigurationProperties(prefix = "rag.chat.memory")
public record ConversationMemoryProperties(Boolean enabled, Integer maxMessages,
                                           Integer ttlMinutes, Boolean inGeneration) {

    public ConversationMemoryProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (maxMessages == null || maxMessages < 2) {
            maxMessages = 10;
        }
        if (ttlMinutes == null || ttlMinutes <= 0) {
            ttlMinutes = 30;
        }
        if (inGeneration == null) {
            inGeneration = false;
        }
    }
}
