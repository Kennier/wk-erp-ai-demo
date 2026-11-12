package com.wk.ai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wk.ai.service.IngestionService;

/**
 * 知识库管理接口：手动触发重建、查看台账状态。
 *
 * <p>当前 demo 未接安全框架，生产环境必须加权限控制（或收敛到内网管理端口）。
 */
@RestController
@RequestMapping("/admin/rag")
public class RagAdminController {

    private final IngestionService ingestionService;

    public RagAdminController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** 全量同步 docs 目录与向量库（增量：未变更文件跳过） */
    @PostMapping("/reingest")
    public IngestionService.RebuildResult reingest() throws Exception {
        return ingestionService.rebuildAll();
    }

    /** 当前台账：已入库文件数、文本块总数、最近一次同步时间 */
    @GetMapping("/status")
    public IngestionService.IngestStatus status() {
        return ingestionService.status();
    }
}
