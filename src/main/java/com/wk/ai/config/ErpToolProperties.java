package com.wk.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ERP 工具配置。
 *
 * @param enabled        工具总开关；false 时 DATA 意图也回退知识库
 * @param maxRows        工具查询强制行数上限，防止触发大结果集捞取
 * @param timeoutSeconds 单次工具执行超时（秒）
 */
@ConfigurationProperties(prefix = "erp.tool")
public record ErpToolProperties(Boolean enabled, Integer maxRows, Integer timeoutSeconds) {

    public ErpToolProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (maxRows == null || maxRows <= 0) {
            maxRows = 20;
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 5;
        }
    }
}
