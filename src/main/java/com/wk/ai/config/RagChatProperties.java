package com.wk.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 问答配置。
 *
 * @param topK                检索返回的相似片段数量
 * @param similarityThreshold 相似度阈值（余弦相似度 0~1），低于该值的片段被过滤；
 *                            all-MiniLM 类模型经验值：相关查询 0.4~0.7，无关查询 &lt;0.3
 * @param snippetMaxLength    引用展示时截取的片段最大字符数
 * @param maxQuestionLength   问题最大长度（超长截断，避免浪费 token 与 embedding 截断失真）
 * @param queryRewriteEnabled 是否启用查询改写（多轮对话时指代消解，有 history 才触发）；
 *                            每次问答会多一次 LLM 调用
 * @param maxHistoryTurns     查询改写携带的最大历史轮数（超出取最近 N 轮）
 * @param multiQueryEnabled   是否启用多查询检索：LLM 把问题扩展为 N 个变体，分别检索后
 *                            RRF 融合，提高召回；每次问答多一次 LLM 调用 + N 次向量检索
 * @param multiQueryN         多查询扩展的变体数量
 * @param maxParentBlocks     单次问答最多注入 prompt 的父块数（Parent-Child 回溯后截断）
 * @param parentMaxChars      单个父块注入 prompt 的字符兜底截断长度
 */
@ConfigurationProperties(prefix = "rag.chat")
public record RagChatProperties(Integer topK, Double similarityThreshold,
                                Integer snippetMaxLength, Integer maxQuestionLength,
                                Boolean queryRewriteEnabled, Integer maxHistoryTurns,
                                Boolean multiQueryEnabled, Integer multiQueryN,
                                Integer maxParentBlocks, Integer parentMaxChars) {

    public RagChatProperties {
        if (topK == null || topK <= 0) {
            topK = 5;
        }
        if (similarityThreshold == null || similarityThreshold < 0 || similarityThreshold > 1) {
            similarityThreshold = 0.4;
        }
        if (snippetMaxLength == null || snippetMaxLength <= 0) {
            snippetMaxLength = 200;
        }
        if (maxQuestionLength == null || maxQuestionLength <= 0) {
            maxQuestionLength = 512;
        }
        if (queryRewriteEnabled == null) {
            queryRewriteEnabled = true;
        }
        if (maxHistoryTurns == null || maxHistoryTurns <= 0) {
            maxHistoryTurns = 5;
        }
        if (multiQueryEnabled == null) {
            multiQueryEnabled = true;
        }
        if (multiQueryN == null || multiQueryN <= 0) {
            multiQueryN = 3;
        }
        if (maxParentBlocks == null || maxParentBlocks <= 0) {
            maxParentBlocks = 3;
        }
        if (parentMaxChars == null || parentMaxChars <= 0) {
            parentMaxChars = 4000;
        }
    }
}
