package com.wk.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识入库配置。
 *
 * @param enabled         是否在启动时执行文档入库
 * @param docsDir         文档目录（相对工作目录），递归扫描子目录
 * @param chunkSize       子块 token 上限（向量检索单元）；须小于 embedding 模型输入上限
 * @param parentChunkSize 父块 token 上限（LLM 上下文单元，Parent-Child 结构）；
 *                        0 或 ≤ chunkSize 时关闭父子结构，退化为平铺切块
 */
@ConfigurationProperties(prefix = "rag.ingest")
public record RagIngestProperties(boolean enabled, String docsDir, int chunkSize,
                                  Integer parentChunkSize) {

    public RagIngestProperties {
        if (docsDir == null || docsDir.isBlank()) {
            docsDir = "./docs";
        }
        if (chunkSize <= 0) {
            chunkSize = 200;
        }
        if (parentChunkSize == null) {
            parentChunkSize = 800;
        }
    }
}
