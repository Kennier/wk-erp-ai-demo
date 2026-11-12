package com.wk.ai.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.wk.ai.config.RagIngestProperties;
import com.wk.ai.service.IngestionService;

/**
 * 启动时知识入库入口：委托 {@link IngestionService} 全量同步 docs 目录。
 *
 * <p>可通过 rag.ingest.enabled=false 关闭启动加载（关闭后仍可调
 * POST /admin/rag/reingest 手动触发）。全部文件入库失败时抛异常终止启动，
 * 避免服务带空库静默运行。
 */
@Component
public class DocumentIngestionRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionRunner.class);

    private final IngestionService ingestionService;
    private final RagIngestProperties properties;

    public DocumentIngestionRunner(IngestionService ingestionService, RagIngestProperties properties) {
        this.ingestionService = ingestionService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!properties.enabled()) {
            log.info("启动入库已关闭（rag.ingest.enabled=false），可调用 POST /admin/rag/reingest 手动触发");
            return;
        }
        long start = System.currentTimeMillis();
        IngestionService.RebuildResult result = ingestionService.rebuildAll();
        if (result.scanned() > 0 && result.failed() == result.scanned()) {
            throw new IllegalStateException(
                    "知识入库全部失败（%d/%d），检查日志定位解析/向量化问题".formatted(result.failed(), result.scanned()));
        }
        log.info("启动知识入库完成, 耗时 {} ms", System.currentTimeMillis() - start);
    }
}
