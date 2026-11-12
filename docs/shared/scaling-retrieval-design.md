# 向量库规模化检索与知识治理设计

> 状态：设计（分阶段实施，阶段①可先行）｜ 创建：2026-11-07 ｜ 关联：知识入库（rag.ingest.*）、RAG 问答（rag.chat.*）
> 前提：本文档是「文档量增长后的检索效率与准确率」路线图——在真实文档灌入前，
> 各手段都属技术储备，**实施以真实规模触达对应档位为信号**。

## 1. 规模分界（决策起点）

| chunk 规模 | 特征 | 核心手段 |
|---|---|---|
| < 10 万 | pgvector HNSW 默认参数毫秒级 | 当前方案直接够用，不过度设计 |
| 10 万 ~ 100 万 | 索引内存吃紧、查询波动 | HNSW 参数调优、表分区、混合检索启动 |
| 100 万 ~ 1000 万 | 候选池噪声上升、纯向量排序失真 | 量化降维、分集合路由、**rerank 成为标配** |
| > 1000 万 | pgvector 逼近舒适区上限 | 评估专用向量库（Milvus/Qdrant 集群）或 ES |

注意：规模大后准确率下降的本质是「候选池变大、语义相近的噪声文档变多」——
靠检索架构升级 + 知识治理双管齐下，纯算法优化治不了脏数据。

## 2. 效率侧：四层方案（pgvector）

| 层 | 手段 | 说明 |
|---|---|---|
| 索引调优 | HNSW `ef_search`（40→100~200）、`m`；halfvec 半精度（存储减半）；bit 二值量化（×1/32）；**Matryoshka 降维**（bge 1024 维可截 512 用） | 第一优先，零架构改动；换 bge-m3 时降维特性直接砍半成本 |
| 物理分区 | PG 原生表分区（按 department 或时间），filter 下推 + 分区裁剪 | 与现有 department filter 对齐，零架构冲突 |
| 分集合路由 | 每部门独立向量表/索引，按 department 路由；跨部门用 RRF 合并（复用现成融合） | 部门间物理隔离，顺带解决安全隔离；索引变小变快 |
| 查询缓存 | 高频 FAQ 检索结果缓存（question hash → chunks），失效信号 = 入库完成时间戳 | 运营高频场景收益大 |

## 3. 准确率侧：四道工序

| 工序 | 手段 | 时机 |
|---|---|---|
| 检索前 | 查询改写 + multi-query（已有）；**HyDE**（LLM 生成假设性答案，用答案向量检索）；metadata 预过滤强化 | paraphrase 召回不足时 |
| 检索中 | **混合检索**：向量 + BM25 双路，RRF 融合（复用现成）；pgvector 0.8 sparsevec 同库实现 | 规模化标配；精确词（错误码/型号/专名）召回的解法 |
| 检索后 | **rerank**：粗筛放大（5→50）→ 精排（50→3~5）；gte-rerank（API）或 bge-reranker（本地 ONNX 同栈） | 百万级后从可选变标配；评测前后对比定夺 |
| 知识治理 | 去重（首跑已暴露冗余漂移）、过期下线、chunk 元数据丰富（§4） | **比算法更重要**，冗余在规模下指数级放大 |

## 4. 元数据模型（核心设计）

每个 chunk 携带完整档案，字段按用途分三类（在 source/department/parentId 基础上扩展）：

| 类别 | 字段 | 用途 |
|---|---|---|
| 过滤类（下推到库） | `docType`（manual/policy/faq/spec）、`status`（active/superseded/archived）、`updatedAt`、`effectiveUntil` | 检索前硬筛：类型、状态、时间区间 |
| 排序类（加权用） | `authorityLevel`（1=组织制度/2=部门规范/3=团队 wiki）、`updatedAt`、`publishedAt` | 检索后加权：权威度 + 新鲜度 |
| 溯源类 | `version`、`supersedes`、`docTitle`、`sectionPath`（手册>第4章>安装） | 版本链路、引用展示、rerank 信号 |

**字段来源**：优先文档头 front-matter 显式声明（`updated: 2026-11-01` 等），
缺省回退文件 mtime（会因拷贝/同步漂移，故显式优先）。
实现：Tika 对 md 按纯文本处理会混入 front-matter——IngestionService 读文件时
先解析剥离（简单 key-value 解析，不引 YAML 库），写入 metadata 后再喂解析器。

## 5. 时间检索的四种用法

| 用法 | 实现 | 适用 |
|---|---|---|
| ① 硬过滤 | `updatedAt > '2026-01-01'`（filter 支持比较运算） | 用户明确要近期内容 |
| ② 软加权（**默认**） | 最终分 = 语义分 × `exp(-days/90)`（半衰期可配） | 默认检索：新文档排前，老文档仍可达 |
| ③ 生效区间 | `effectiveUntil` 未过期才命中 | 制度类文档（失效制度不可引用） |
| ④ 版本共存 | 旧版本 status=superseded 保留 | 「2023 年的老规定」等历史问题 |

**原则：②是默认、①③是例外**——硬过滤误杀历史问题，软加权只影响排序不影响可达性。
参数进 yml，用评测验证调参。

## 6. 文档更新的两种策略

| 策略 | 机制 | 适用 |
|---|---|---|
| 覆盖式（**现状默认**） | 台账 hash 变 → 确定性 chunkId 覆盖旧块 | 文档只有一个有效版本的绝大多数场景，零改动 |
| 版本共存式 | chunkId = `sha256(source:version:chunkIndex)`；台账 key=(source,version)；新版 active / 旧版自动 superseded / supersedes 指向新版 | 制度/规范类需多版本可检索（「旧版规定」历史问题） |

衔接：默认检索加 `status=active` filter；入库时间戳作为检索缓存的失效信号。

## 7. 分层与排序融合

「分层」（组织制度 > 部门规范 > 团队 wiki）的用途是**加权**而非过滤——
同级知识多出处竞争时权威层级排前。融合公式（RRF 融合后、父块回溯前加权重排）：

```
finalScore = rrfScore × authorityWeight × freshness
  authorityWeight: level 1→1.2, 2→1.0, 3→0.8（yml 可配）
  freshness: exp(-updatedAt 距今天数 / 90)
```

纯排序逻辑，无库层改动；权重进 yml，用评测调参。

## 8. 落地节奏

| 阶段 | 内容 | 依赖 |
|---|---|---|
| ①（可先行） | metadata 扩展（front-matter 解析 + mtime + sectionPath 入库）+ 时间软加权 | 无 |
| ② | status/supersedes 版本共存（id 加版本维度、台账按 (source,version) 记录） | 制度类真实文档出现 |
| ③ | 时间/状态/docType 过滤进检索 API（请求加 updatedAfter/docType 参数） | 阶段① |
| ④ | 混合检索（BM25/sparsevec 路） | 规模 10 万+ |
| ⑤ | rerank 接入 + 评测对比 | 规模 100 万+ 或精度瓶颈 |
| ⑥ | HNSW 调优/量化/分区/分集合 | pgvector 落地 + 对应档位 |

每阶段跑评测验证（权威加权对 Recall 的影响、时间衰减对老文档用例的误伤检查）。
触发信号：**真实文档灌入 → 规模触达档位 → 按表选手段 → 评测验证每一步**。

## 9. 已具备 / 待实施对照

| 已具备 | 待实施（本文档范围） |
|---|---|
| HNSW 索引、department filter、RRF 融合、Parent-Child、评测框架、token 统计、台账增量 | §4 元数据扩展、§5 时间加权、§6 版本共存、§7 分层加权、§3 混合检索与 rerank、§2 分区量化 |
