# Parent-Child Retriever 设计文档

> 状态：已实现（2026-10-23）｜ 创建：2026-10-23 ｜ 关联：知识入库（rag.ingest.*）、RAG 问答（rag.chat.*）

## 1. 背景与问题

当前入库链路是单层切块：`chunkSize=200` tokens 一块，**既是向量检索单元，也是给 LLM 的内容单元**。两个要求互相矛盾：

- 向量匹配要求块小：embedding 模型（bge-small-zh-v1.5）输入有上限，块越小语义越聚焦，匹配越准；
- LLM 生成要求块大：200 tokens 仅三五句话，命中片段常缺前因后果，答案质量与引用可读性打折。

Parent-Child（父子检索）用两层结构解耦这两个要求：**小块用于精准匹配（Child），大块用于提供上下文（Parent）**。

## 2. 目标与非目标

**目标**

1. 检索匹配精度保持不变（子块粒度不变，仍 200 tokens）
2. LLM 拿到的上下文升级为完整父块，答案质量与引用可读性提升
3. 一条配置开关在「父子结构 / 现有平铺结构」之间切换，可 A/B 对比
4. 保留既有全部语义：确定性 ID 幂等、hash 台账增量、部门过滤、RRF 融合、引用解析

**非目标**

- 不做父块的向量化与检索（父块只存原文，供回溯）
- 不在本期实现持久化存储（父块存内存台账，随 SimpleVectorStore 同生命周期；pgvector 迁移见 §7）

## 3. 总体方案

### 3.1 数据模型

```
Document（docs/ 下一个文件）
 └── Parent 父块 ×N      约 800 tokens，完整语义单元（近似一个章节）
      └── Child 子块 ×M   200 tokens，向量匹配粒度（与现状一致）
```

- 只有 **Child 参与向量检索**；Parent 只存原文
- metadata 变化：子块新增 `parentId`；`source`、`department` 语义不变

### 3.2 入库流程（IngestionService）

```
Tika 解析全文
  → 一级切分：TokenTextSplitter(parentChunkSize=800)          → 父块 [P0, P1, ...]
  → 二级切分：每个父块 TokenTextSplitter(chunkSize=200)        → 子块
  → 子块向量入库，metadata = { source, department, parentId }
  → 台账扩展：source → (hash, chunks, parents)
       parents: Map<parentId, ParentBlock(parentId, index, text)>
```

**ID 规则（确定性，保持幂等语义）：**

- 父块 ID：`sha256(source + ":p" + parentIndex)`
- 子块 ID：由 `sha256(source + ":" + childIndex)` 改为 `sha256(source + ":p" + parentIndex + ":" + childIndex)`
  ——同一父块内的子块 ID 稳定；父块边界变化时其子块整体重写，旧的通过「先 add 新块、成功后清理差集」自愈，机制不变

**台账（IndexedFile）扩展：**

```java
record IndexedFile(String source, String hash, List<String> chunkIds,
                   Map<String, String> parents) {}   // parentId -> 父块原文
```

### 3.3 检索流程（RagChatService）

现状链路中「多路检索 RRF 融合」的输出从子块变为父块，其余环节不动：

```
问题 → 改写 → 扩展为多变体
  → 每路对【子块】检索（阈值/topK/部门 filter 逐路生效，精度不变）
  → RRF 融合，得到 topK 个【子块】
  → 父块回溯：按子块 metadata.parentId 去重，映射到父块
  → 父块排序：命中子块数多者优先，同数按子块最高 RRF 分
  → 截断至 maxParentBlocks（默认 3）
  → prompt 注入父块原文（编号 = 父块序号）
  → citations 返回父块的 source + 父块开头 snippet
```

**语义变化说明（需要知晓）：** 请求参数 `topK` 的含义从「返回块数」变为「子块命中数」。topK=5 时通常回溯出 2~3 个父块，上下文块数更少但信息更完整。

## 4. 详细设计

### 4.1 配置（RagIngestProperties）

| 配置 | 默认 | 说明 |
|---|---|---|
| `parent-chunk-size` | 800 | 父块 token 上限；**0 或 ≤ childChunkSize 时关闭父子结构**，退化为现状 |
| `chunk-size` | 200 | 子块 token 上限（含义不变） |
| `parent-max-chars` | 4000（prompt 侧，RagChatProperties） | 单个父块注入 prompt 的字符兜底截断 |
| `max-parent-blocks` | 3（prompt 侧，RagChatProperties） | 单次问答最多注入的父块数 |

### 4.2 入库改动点（IngestionService.ingestFile）

1. `parseAndSplit` 拆为两级：先按 `parentChunkSize` 切父块；`parentChunkSize > chunkSize` 时对每个父块再切子块，否则子块=父块（平铺模式）
2. 子块 metadata 写入 `parentId`
3. 父块原文进入台账 `parents`；向量库只 add 子块
4. 增量清理逻辑不变：先 add 新子块，成功后删除「新版中不存在的旧子块 ID」；父块随台账整体替换

### 4.3 检索改动点（RagChatService）

1. `multiQuerySearch` 返回的仍是子块列表（含 RRF 排序）——不改动
2. 新增 `resolveParents(List<Document> childHits)`：
   - 读每个子块 `metadata.parentId`，从台账取父块原文；`parentId` 缺失（平铺模式或旧数据）时直接用子块自身内容——**天然兼容**
   - 去重、按（命中子块数 desc, 子块最高 RRF 序 asc）排序、截断 `maxParentBlocks`
3. prompt 组装把「片段」替换为「父块」：`[n] (来源: source)` + 父块原文（超长按 `parent-max-chars` 截断加省略号）
4. `Citation.snippet` 取父块开头 `snippetMaxLength` 字符；`score` 取该父块下最高子块分数

### 4.4 边界与异常

| 场景 | 行为 |
|---|---|
| `parentId` 在台账中找不到（旧数据混存） | 降级使用子块原文，不影响问答 |
| 全部子块命中同一父块 | 只注入 1 个父块，上下文最紧凑 |
| 父块超过 `parent-max-chars` | 截断尾部并加 `…`，引用标注不变 |
| 平铺模式（开关关闭） | 子块无 parentId 或 parentId=自身 id，行为与现状完全一致 |

### 4.5 兼容开关

`rag.ingest.parent-chunk-size: 0` 关闭后：入库只切一层（=chunkSize），检索回溯退化为子块直用。**开关切换后必须重跑 reingest**（数据形态变了），台账 hash 不含切块参数，需在关闭/开启切换时以 source 级全量重写兜底——实现上把 `parentChunkSize` 计入台账条目，参数变化视为「文件已变更」。

## 5. 测试计划（沿用离线单测风格）

1. 两级切块：长文切出 ≥2 父块，每个父块 ≥1 子块，子块 metadata 带正确 parentId
2. 平铺模式退化：parentChunkSize=0 时行为与现状一致（无父块、检索直用子块）
3. 回溯去重：5 个子块命中 3 个父块，prompt 中父块数 = 3，排序符合（命中数 desc）
4. 旧数据兼容：子块 metadata 无 parentId 时降级用子块内容
5. 台账：父块文本随文件变更整体替换；删除文件时父块随之清理
6. 参数变化触发重入库：parentChunkSize 变更后 rebuild 视为全部变更

## 6. 改动清单

| 文件 | 改动 |
|---|---|
| `config/RagIngestProperties` | +`parentChunkSize` |
| `config/RagChatProperties` | +`maxParentBlocks`、`parentMaxChars` |
| `service/IngestionService` | 两级切块、台账结构扩展、ID 规则、参数入 hash 判定 |
| `service/RagChatService` | +`resolveParents`，prompt/citations 切换为父块粒度 |
| 测试 | §5 全部用例 |

## 7. 持久化迁移路径（pgvector 阶段）

- 子块沿用 spring-ai 标准表；父块建独立表 `rag_parent_chunks(parent_id, source, department, content, hash)`
- `resolveParents` 从内存台账改为一次 `WHERE parent_id IN (...)` 查询
- 增量同步时父块表按 `source` upsert/delete，与向量数据同事务保证一致

## 8. 代价与风险

| 项 | 说明 |
|---|---|
| 内存 | 父块文本副本 ≈ 向量库文本量的 4 倍（800/200），demo 规模可忽略 |
| LLM 成本 | prompt 变长，估算 +30%~50% token；`max-parent-blocks` 封顶 |
| 延迟 | 回溯为内存 Map 查找，可忽略 |
| 风险 | prompt 变长可能稀释注意力——靠 `max-parent-blocks=3` 控制；如效果不佳，可调小父块粒度（600）或用 §4.5 开关回退对比 |
