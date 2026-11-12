package com.wk.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 意图路由配置。
 *
 * @param enabled 路由开关；false 时全部走知识库（现状行为，不发起路由 LLM 调用）
 */
@ConfigurationProperties(prefix = "rag.chat.intent-router")
public record IntentRouterProperties(Boolean enabled) {

    public IntentRouterProperties {
        if (enabled == null) {
            enabled = true;
        }
    }
}
