package com.wk.ai.config;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.wk.ai.advisor.TokenUsageAdvisor;

/**
 * ChatClient 装配（两条链路分开，互不污染）：
 * chatClient = KNOWLEDGE 链路（RAG）；toolChatClient = DATA 链路（挂 ERP 工具）。
 * 底层 ChatModel 由 spring.ai.model.chat 指定的默认实现提供（当前 openai）。
 */
@Configuration
public class ChatClientConfig {

    /** Token 用量统计 advisor：挂在 default advisors 上，mutate 子链（改写器/扩展器）继承，
     *  覆盖全链路所有 LLM 调用（含 tool-call 循环与流式） */
    @Bean
    public TokenUsageAdvisor tokenUsageAdvisor(MeterRegistry meterRegistry) {
        return new TokenUsageAdvisor(meterRegistry);
    }

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, TokenUsageAdvisor tokenUsageAdvisor) {
        return builder.defaultAdvisors(tokenUsageAdvisor).build();
    }

    /** DATA 链路专用：tools 在每次调用时挂载（见 ToolChatService），此处只建基础实例 */
    @Bean
    public ChatClient toolChatClient(ChatClient.Builder builder, TokenUsageAdvisor tokenUsageAdvisor) {
        return builder.defaultAdvisors(tokenUsageAdvisor).build();
    }

    /**
     * 查询改写器：多轮对话时结合历史做指代消解（如「它怎么切分」→「TokenTextSplitter 怎么切分」）。
     * 内部用 LLM 完成改写，rag.chat.query-rewrite.enabled=false 时关闭。
     */
    @Bean
    public RewriteQueryTransformer rewriteQueryTransformer(
            @Qualifier("chatClient") ChatClient chatClient) {
        return RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClient.mutate())
                .build();
    }

    /**
     * 多查询扩展器（Multi-Query）：把一个问题扩展为 N 个不同表述的变体，分别检索后融合，
     * 避免单一表述漏召回。includeOriginal=true 时原始问题也在列表中兜底。
     * rag.chat.multi-query.enabled=false 时关闭。
     *
     * <p>promptTemplate 必须显式指定：默认英文模板在 gpt-6-sol 上输出格式解析不稳定
     * （评测基线 27 条全部退化为单路检索，见知识库 2026-11-03 首跑基线）。
     * 中文指令 + 明确输出格式（每行一条/无编号/无解释）实测解析稳定。
     * 变量占位符 {query} / {number} 与框架内部约定一致，缺一不可。
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.chat.multi-query", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public MultiQueryExpander multiQueryExpander(@Qualifier("chatClient") ChatClient chatClient,
            RagChatProperties properties) {
        PromptTemplate promptTemplate = new PromptTemplate("""
                你是检索查询优化助手。请针对下面的用户问题，生成 {number} 个不同表述的检索查询变体，
                用于从向量知识库中检索相关内容。要求：
                1. 严格输出 {number} 行，每行一个变体，不要编号
                2. 不要输出任何解释、前言或总结文字
                3. 变体之间语义等价，但用词、角度、详略程度不同
                用户问题：{query}
                """);
        return MultiQueryExpander.builder()
                .chatClientBuilder(chatClient.mutate())
                .promptTemplate(promptTemplate)
                .numberOfQueries(properties.multiQueryN())
                .includeOriginal(true)
                .build();
    }
}
