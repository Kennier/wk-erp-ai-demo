package com.wk.ai.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库装配：当前为内存版 SimpleVectorStore，仅用于开发期跑通 RAG 链路。
 *
 * <p>注意：
 * <ul>
 *   <li>数据存内存，重启即丢；需要持久化时可调用 {@link SimpleVectorStore#save(java.io.File)} 落盘</li>
 *   <li>无多实例共享能力，不可用于生产</li>
 *   <li>pgvector 切换方案已验证（双数据源装配 + 自动配置接管，见知识库 2026-11-03 节），
 *       待 PG 环境就绪后删除本配置类并按记录切回</li>
 * </ul>
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
