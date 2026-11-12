package com.wk.ai.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

import com.wk.ai.config.RagIngestProperties;

/**
 * 知识入库核心服务：解析、两级切块（Parent-Child）、向量化写入向量库，
 * 并维护台账实现增量幂等同步。
 *
 * <p>切块结构：子块（chunkSize，向量检索单元）入库向量化；父块（parentChunkSize，
 * LLM 上下文单元）只存原文，检索命中子块后按 parentId 回溯。
 * parentChunkSize=0 或 ≤ chunkSize 时为平铺模式（无父块，行为退化为单层切块）。
 *
 * <p>幂等机制：
 * <ul>
 *   <li>确定性 chunkId：平铺模式 sha256(source:i)；父子模式 sha256(source:p{n}:{i})，
 *       同一文件重复入库是覆盖而非追加</li>
 *   <li>台账 source -&gt; (hash, 切块参数, chunkIds, parents)：hash 与参数均未变才跳过；
 *       变更先 add 新块、成功后清理新旧差集；文件从目录消失时清理对应向量</li>
 *   <li>入库失败保留旧版本数据可检索，不让坏文件拖垮整个知识库</li>
 * </ul>
 */
@Service
public class IngestionService {

    /**
     * 已入库文件台账。
     *
     * @param parents      parentId -&gt; 父块原文（平铺模式为空 Map）
     * @param ingestParams 切块参数指纹，参数变化视为文件变更触发重建
     */
    public record IndexedFile(String source, String hash, List<String> chunkIds,
                              Map<String, String> parents, String ingestParams) {
    }

    public record RebuildResult(int scanned, int updated, int removed, int failed, int totalChunks) {
    }

    public record IngestStatus(int files, int totalChunks, Instant lastRebuildAt) {
    }

    /** 单文件入库结果 */
    private record IngestResult(List<String> chunkIds, Map<String, String> parents) {
    }

    /** 切块中间结果 */
    private record SplitChunks(List<Document> stored, List<String> chunkIds, Map<String, String> parents) {
    }

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Tika 可解析且常见于知识库的扩展名 */
    private static final Set<String> SUPPORTED_EXTENSIONS =
            Set.of("txt", "md", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx", "html", "htm");

    private final VectorStore vectorStore;
    private final RagIngestProperties properties;

    /** 已入库文件台账：source -&gt; (hash, 切块参数, 全部chunkId, parents) */
    private final Map<String, IndexedFile> ledger = new ConcurrentHashMap<>();

    /** parentId -&gt; 父块原文，供检索回溯（RagChatService 查询），重建时整体刷新 */
    private final Map<String, String> parentTexts = new ConcurrentHashMap<>();

    private volatile Instant lastRebuildAt;

    public IngestionService(VectorStore vectorStore, RagIngestProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * 全量同步 docs 目录与向量库：新增/变更文件入库，删除文件清理向量，
     * hash 与切块参数均未变的文件跳过。
     * 单文件失败保留旧版本继续处理其余文件。
     */
    public synchronized RebuildResult rebuildAll() throws IOException {
        Path docsDir = docsDir();
        List<Path> files = scan(docsDir);

        Map<String, IndexedFile> newLedger = new ConcurrentHashMap<>();
        int updated = 0;
        int failed = 0;
        int totalChunks = 0;
        String params = ingestParams();

        for (Path file : files) {
            String source = relativeSource(docsDir, file);
            String hash = sha256(file);
            IndexedFile existing = ledger.get(source);
            if (existing != null && existing.hash().equals(hash) && params.equals(existing.ingestParams())) {
                // 内容与切块参数均未变：跳过处理，向量库不动，缩小重建的中间态窗口
                newLedger.put(source, existing);
                totalChunks += existing.chunkIds().size();
                continue;
            }
            try {
                IngestResult result = ingestFile(file, source);
                newLedger.put(source, new IndexedFile(source, hash, result.chunkIds(), result.parents(), params));
                totalChunks += result.chunkIds().size();
                updated++;
            }
            catch (Exception e) {
                failed++;
                if (existing != null) {
                    newLedger.put(source, existing);
                }
                log.error("文档入库失败，保留旧版本可检索: {}", source, e);
            }
        }

        // 目录中已消失的文件：清理对应向量，避免检索命中过期内容
        int removed = 0;
        for (IndexedFile stale : ledger.values()) {
            if (!newLedger.containsKey(stale.source())) {
                vectorStore.delete(stale.chunkIds());
                removed++;
            }
        }

        ledger.clear();
        ledger.putAll(newLedger);
        refreshParentIndex(newLedger);
        lastRebuildAt = Instant.now();

        log.info("知识同步完成: 扫描 {} 个文件, 更新 {}, 清理 {}, 失败 {}, 共 {} 块",
                files.size(), updated, removed, failed, totalChunks);
        return new RebuildResult(files.size(), updated, removed, failed, totalChunks);
    }

    public IngestStatus status() {
        int totalChunks = ledger.values().stream().mapToInt(f -> f.chunkIds().size()).sum();
        return new IngestStatus(ledger.size(), totalChunks, lastRebuildAt);
    }

    /** 按 parentId 取父块原文；不存在返回 null（检索端降级用子块内容） */
    public String getParent(String parentId) {
        return parentTexts.get(parentId);
    }

    /** 解析 + 两级切块 + 向量化写入单个文件，返回 chunkIds 与父块原文 */
    private IngestResult ingestFile(Path file, String source) throws IOException {
        // ① 解析：Tika 读文件
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(file));
        List<Document> documents = reader.get();

        // 元数据随切分复制到每个块：source 用于引用回溯，department 用于检索时部门过滤
        // 目录即标签：docs/it/xxx.md -> department=it；根目录与 shared/ 视为共享库
        String department = resolveDepartment(source);
        documents.forEach(doc -> {
            doc.getMetadata().put("source", source);
            doc.getMetadata().put("department", department);
        });

        // ② 两级切块：父块（800）→ 子块（200）；子块 metadata 带 parentId
        SplitChunks split = splitIntoChunks(documents, source);

        // ③ 写入：先 add 新块（同 id 覆盖），成功后清理新版中不存在的旧块：
        // 任何一步失败，库里至少留有一个可检索的版本，重跑可自愈
        vectorStore.add(split.stored());
        IndexedFile existing = ledger.get(source);
        if (existing != null) {
            List<String> staleIds = existing.chunkIds().stream()
                    .filter(id -> !split.chunkIds().contains(id))
                    .toList();
            if (!staleIds.isEmpty()) {
                vectorStore.delete(staleIds);
            }
        }
        return new IngestResult(split.chunkIds(), split.parents());
    }

    /**
     * 两级切块：parentChunkSize &gt; chunkSize 时为父子结构
     * （父块 id = sha256(source:p{n})，子块 id = sha256(source:p{n}:{i})）；
     * 否则平铺单层切块（子块 id = sha256(source:{i})，与历史数据兼容，无父块）。
     */
    private SplitChunks splitIntoChunks(List<Document> documents, String source) {
        TokenTextSplitter childSplitter = splitter(properties.chunkSize());
        int parentChunkSize = properties.parentChunkSize();
        List<Document> stored = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();

        if (parentChunkSize <= 0 || parentChunkSize <= properties.chunkSize()) {
            // 平铺模式：单层切块，无父块
            List<Document> chunks = childSplitter.apply(documents);
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                String id = sha256(source + ':' + i);
                stored.add(new Document(id, chunk.getText(), chunk.getMetadata()));
                chunkIds.add(id);
            }
            return new SplitChunks(stored, chunkIds, Map.of());
        }

        // 父子模式：一级切父块存原文，二级切子块入库
        TokenTextSplitter parentSplitter = splitter(parentChunkSize);
        List<Document> parentDocs = parentSplitter.apply(documents);
        Map<String, String> parents = new LinkedHashMap<>();
        for (int p = 0; p < parentDocs.size(); p++) {
            Document parentDoc = parentDocs.get(p);
            String parentId = sha256(source + ":p" + p);
            parents.put(parentId, parentDoc.getText());

            List<Document> children = childSplitter.apply(List.of(parentDoc));
            for (int c = 0; c < children.size(); c++) {
                Document child = children.get(c);
                String id = sha256(source + ":p" + p + ":" + c);
                child.getMetadata().put("parentId", parentId);
                stored.add(new Document(id, child.getText(), child.getMetadata()));
                chunkIds.add(id);
            }
        }
        return new SplitChunks(stored, chunkIds, parents);
    }

    private TokenTextSplitter splitter(int chunkSize) {
        // 中文句读边界 + chunkSize 适配 embedding 模型的输入上限
        return TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withPunctuationMarks(List.of('。', '？', '！', '；', '\n'))
                .build();
    }

    /** 重建 parentId -> 原文索引（全量刷新，与台账一致） */
    private void refreshParentIndex(Map<String, IndexedFile> newLedger) {
        parentTexts.clear();
        newLedger.values().forEach(f -> parentTexts.putAll(f.parents()));
    }

    /** 切块参数指纹：参数变化视为文件变更，触发重建（开关切换后数据形态改变） */
    private String ingestParams() {
        return "pcs=" + properties.parentChunkSize();
    }

    private Path docsDir() throws IOException {
        Path docsDir = Path.of(properties.docsDir()).toAbsolutePath().normalize();
        Files.createDirectories(docsDir);
        return docsDir;
    }

    private List<Path> scan(Path docsDir) throws IOException {
        try (Stream<Path> walk = Files.walk(docsDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(this::isSupported)
                    .sorted()
                    .toList();
        }
    }

    private String relativeSource(Path docsDir, Path file) {
        return docsDir.relativize(file).toString().replace('\\', '/');
    }

    /**
     * 目录即标签：取相对路径第一段作为部门标签（如 it/vpn.md -> it）；
     * 根目录或 shared/ 目录视为共享库（department=shared），所有部门检索时都可见
     */
    private String resolveDepartment(String source) {
        int slash = source.indexOf('/');
        if (slash <= 0) {
            return "shared";
        }
        String first = source.substring(0, slash);
        return "shared".equals(first) ? "shared" : first;
    }

    private boolean isSupported(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes()));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
