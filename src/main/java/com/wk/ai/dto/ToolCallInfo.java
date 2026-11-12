package com.wk.ai.dto;

import java.util.Map;

/**
 * 一次问答中实际发生的工具调用记录（DATA 模式响应）。
 *
 * @param name          工具名
 * @param args          LLM 传入的工具参数
 * @param resultSummary 结果摘要（成功/业务失败原因/系统异常）
 */
public record ToolCallInfo(String name, Map<String, Object> args, String resultSummary) {
}
