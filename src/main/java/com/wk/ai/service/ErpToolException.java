package com.wk.ai.service;

/**
 * ERP 工具业务异常：消息为面向用户的友好提示，由薄壳层直接转译给 LLM 组织回答。
 */
public class ErpToolException extends RuntimeException {

    public ErpToolException(String message) {
        super(message);
    }
}
