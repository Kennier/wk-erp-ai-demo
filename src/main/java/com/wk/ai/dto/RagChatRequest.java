package com.wk.ai.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * RAG 问答请求。
 *
 * @param question       用户问题
 * @param topK           可选，检索片段数，缺省用配置值
 * @param threshold      可选，相似度阈值，缺省用配置值
 * @param history        可选，会话历史（旧->新顺序）；仅当未传 conversationId 时生效
 *                       （老客户端兼容路径），触发查询改写做指代消解
 * @param department     可选，部门过滤（目录即标签：docs/{department}/xxx.md）；检索范围 =
 *                       该部门文档 + shared 共享库；不传则全库检索
 * @param conversationId 可选，会话 ID；传入后启用服务端会话记忆（优先于 history），
 *                       首次不传由服务端生成并在响应中返回
 */
public record RagChatRequest(@NotBlank(message = "question 不能为空") String question,
                             Integer topK, Double threshold,
                             @Valid List<ChatTurn> history,
                             String department,
                             String conversationId) {

    /** 便捷构造：不带会话（conversationId=null，单轮或走 history 兼容路径） */
    public RagChatRequest(String question, Integer topK, Double threshold,
                          List<ChatTurn> history, String department) {
        this(question, topK, threshold, history, department, null);
    }
}
