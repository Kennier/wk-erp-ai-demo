package com.wk.ai.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 会话历史一轮：上一轮的问题与回答，用于查询改写时的指代消解。
 *
 * @param question 上一轮问题
 * @param answer   上一轮回答
 */
public record ChatTurn(@NotBlank String question, String answer) {
}
