package com.wk.ai.dto;

/**
 * 引用条目：回答中 [编号] 标注对应的知识片段来源。
 *
 * @param index   片段编号（与 prompt 中编号一致）
 * @param source  文档相对路径（入库时写入的 metadata.source）
 * @param snippet 命中片段原文（超长截断）
 * @param score   相似度分数（余弦相似度，越大越相关）
 */
public record Citation(int index, String source, String snippet, double score) {
}
