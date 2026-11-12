package com.wk.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.wk.ai.config.IntentRouterProperties;

/**
 * 意图路由：LLM 结构化分类用户问题涉及的能力类型（可多选）。
 * KNOWLEDGE 知识问答 | DATA 业务数据查询；混合问题输出两个意图，由 ChatOrchestrator 并行编排。
 *
 * <p>兜底规则全部倾向 KNOWLEDGE：路由 LLM 失败、解析失败、无合法 intent、开关关闭——
 * RAG 链路无 DB 依赖最成熟，路由错了用户重问即可，误触数据库代价更高。
 */
@Service
public class IntentRouter {

    public static final String KNOWLEDGE = "KNOWLEDGE";
    public static final String DATA = "DATA";

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private static final String ROUTER_PROMPT = """
            判断用户问题涉及哪些能力类型，输出 JSON：
            {"intents": ["KNOWLEDGE" 和/或 "DATA"], "reason": "一句话理由"}
            - KNOWLEDGE：知识/流程/制度/方法类问题（如"退货流程是什么"、"文档怎么切分"）
            - DATA：查询业务系统数据（如"订单 SO20240001 到哪了"、"SKU A100 还有货吗"）
            - 同时涉及两类时（如"退货流程是什么，我订单 SO20240001 什么状态"）输出两个元素
            无法判断时输出 ["KNOWLEDGE"]。

            用户问题：%s
            """;

    /** LLM 结构化输出的载体 */
    public record IntentClassification(List<String> intents, String reason) {
    }

    private final ChatClient chatClient;
    private final IntentRouterProperties properties;

    public IntentRouter(@org.springframework.beans.factory.annotation.Qualifier("chatClient")
                        ChatClient chatClient, IntentRouterProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /** 单意图兼容入口（等价于取 classifyIntents 的首个结果，KNOWLEDGE 兜底） */
    public String classify(String question) {
        List<String> intents = classifyIntents(question);
        return intents.contains(DATA) ? DATA : KNOWLEDGE;
    }

    /**
     * 多意图分类：解析为合法 intent 集合（保持 LLM 给出的顺序，KNOWLEDGE 优先兜底）。
     * 失败/为空/开关关闭 → [KNOWLEDGE]。
     */
    public List<String> classifyIntents(String question) {
        if (!properties.enabled()) {
            return List.of(KNOWLEDGE);
        }
        try {
            IntentClassification classification = chatClient.prompt()
                    .user(ROUTER_PROMPT.formatted(question))
                    .call()
                    .entity(IntentClassification.class);
            List<String> intents = normalize(classification == null ? null : classification.intents());
            if (intents.isEmpty()) {
                log.debug("意图分类无合法值，回退 KNOWLEDGE");
                return List.of(KNOWLEDGE);
            }
            log.debug("意图分类: {} <- [{}]", intents, question);
            return intents;
        }
        catch (Exception e) {
            log.warn("意图路由失败，回退 KNOWLEDGE: {}", e.getMessage());
            return List.of(KNOWLEDGE);
        }
    }

    /** 过滤非法值 + 去重（保序）；无合法值返回空列表由调用方兜底 */
    private List<String> normalize(List<String> raw) {
        var unique = new LinkedHashSet<String>();
        if (raw != null) {
            for (String intent : raw) {
                if (KNOWLEDGE.equals(intent) || DATA.equals(intent)) {
                    unique.add(intent);
                }
            }
        }
        var result = new ArrayList<>(unique);
        // KNOWLEDGE 排前：双意图时兜底语义优先
        result.sort((a, b) -> a.equals(KNOWLEDGE) ? -1 : b.equals(KNOWLEDGE) ? 1 : 0);
        return result;
    }
}
