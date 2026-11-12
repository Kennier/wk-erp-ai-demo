# wk-ai

ERP RAG 服务：多模型对话（DeepSeek / OpenAI 兼容 / Gemini）+ 向量检索 + 本地 Transformers Embedding。

## 技术栈

- Java 21（本机路径 `D:\JDK\jdk21`，默认 JAVA_HOME 是 JDK 8，构建时必须显式指定）
- Spring Boot 4.1.1 / Spring Framework 7
- Spring AI 2.0.0（BOM: `spring-ai-bom`）
- 向量库：**内存版 SimpleVectorStore**（开发期；pgvector 切换方案已完整验证，待 PG 环境就绪后
  按知识库 2026-11-03 节记录切回，预计改动：pom 双依赖 + DataSourceConfig 双数据源 + 删
  VectorStoreConfig + yml vectorstore.pgvector，业务代码零改动）
- Embedding：本地 ONNX Transformers（`spring-ai-starter-model-transformers`，排除 pytorch-engine），
  bge-small-zh-v1.5（384 维，模型文件在 `src/main/resources/models/`，classpath 加载）
- ORM：MyBatis-Plus 3.5.16（`mybatis-plus-spring-boot4-starter`），ERP MySQL Tool Calling
- Maven 3.9.x

## 向量库选型记录

- **ES 已排除**：公司 ES 服务端版本低于 8.x（kNN 检索 8.0 起才可用），且 Spring AI 2.0 的
  客户端（elasticsearch-java 9.4.5）只官方支持同大版本服务端，版本不够无法使用
- **MySQL 路线不存在**：Spring AI 无官方 MySQL vector store；MySQL 9.0 的 VECTOR 类型没有对应实现
- **2026-11-03 pgvector 切换方案已完整验证后回退**（本地 PG 环境未就绪）：完整步骤与
  踩坑记录在知识库 2026-11-03 节——双数据源装配（MySQL @Primary + PG）、Boot 4 的
  DataSourceAutoConfiguration 在 `org.springframework.boot.jdbc.autoconfigure` 包、
  测试需排除 PgVectorStoreAutoConfiguration 并 @MockitoBean VectorStore（只 exclude
  不 mock 会级联失败）；PG 需 15+ 预装 pgvector 扩展（推荐 Docker `pgvector/pgvector`）
- **双数据源坑（已解决）**：PgVectorStore 自动配置要求容器唯一 JdbcTemplate——
  双数据源必须全部手动装配（`DataSourceConfig`，ERP MySQL 标 @Primary），
  Boot 的 DataSourceAutoConfiguration 已在主类排除；注意 Boot 4 中该类在
  `org.springframework.boot.jdbc.autoconfigure` 包（不是 boot.autoconfigure.jdbc）
- **测试离线**：单测排除 PgVectorStoreAutoConfiguration 并 @MockitoBean VectorStore
  （Bean 初始化依赖 PG 连接，离线环境无法创建）；真实链路验证走 rag-eval Profile

## 约定

### 目录结构

```
docs/                        知识库文档（启动时自动入库，递归扫描，支持 pdf/word/ppt/html/txt/md）
                             含 project-history.md：项目历程与问题决策记录（活文档，持续追加）
src/main/java/com/wk/ai/     按分层分包：
  config/                    配置属性与 Bean 装配（RagIngestProperties、VectorStoreConfig）
  controller/                REST 接口（RagAdminController）
  service/                   业务服务（IngestionService）
  runner/                    启动任务（DocumentIngestionRunner）
src/main/resources/          配置文件；本地 ONNX 模型文件也放这里
src/test/java/com/wk/ai/     测试，与主代码包结构对应
```

### 知识入库（rag.ingest.*）

- 启动时由 `DocumentIngestionRunner` 委托 `IngestionService` 全量同步 `docs/`，进程内只跑一次；
  `rag.ingest.enabled=false` 关闭启动加载（关闭后仍可调管理接口手动触发）
- 单文件失败保留旧版本继续，全部失败则终止启动
- **管理接口**：`POST /admin/rag/reingest` 全量同步（增量：hash 未变跳过）、
  `GET /admin/rag/status` 台账状态。demo 无鉴权，生产必须加权限或收敛内网
- **幂等机制**：确定性 chunkId = `sha256(source:chunkIndex)`，重复入库即覆盖；
  台账记录 source→(hash, chunkIds)；写入顺序为「先 add 新块、成功后清理新旧差集」，
  任何一步失败库里至少留有一个可检索版本，重跑自愈
- 切块策略：`TokenTextSplitter`，中文句读标点（。？！；），chunkSize 默认 200
  （必须小于 all-MiniLM-L6-v2 的 256 token 输入上限，否则尾部内容被静默截断）
- **Parent-Child 两级切块**：父块 `parent-chunk-size: 800`（LLM 上下文单元，只存原文不向量化），
  子块 `chunk-size: 200`（向量检索单元，metadata 带 parentId）；检索命中子块后回溯父块
  注入 prompt（同父块多命中合并，max-parent-blocks=3 封顶）；parentId 缺失自动降级子块内容；
  `parent-chunk-size: 0` 退化为平铺切块；切换后 reingest 自动全量重建
- **目录即标签**：`docs/{department}/` 一级子目录名作为部门标签写入 chunk metadata；
  根目录与 `shared/` 视为共享库（department=shared）；改标签 = 挪目录 + 重跑 reingest
- 切换持久化向量库后，必须把启动全量加载改为独立入库接口 + 按文件变更增量入库

### RAG 问答（rag.chat.*）

- `POST /api/chat`：InputGuard 注入拦截 → conversationId 解析 → **IntentRouter 多意图分类**
  （可输出 KNOWLEDGE+DATA 双意图）→ `ChatOrchestrator` 编排：
  单意图直通对应链路；**双意图（混合问题）双路并行（虚拟线程）+ DualSummarizer 汇总**
  （mode=DUAL，citations 沿用知识路、toolCalls 沿用数据路，记忆防重由编排层统一 record）；
  KNOWLEDGE 链路：查询改写（有 history/conversationId 时）→ 查询扩展 → 多路检索 RRF 融合
  → 父块回溯 → 编号注入 prompt → LLM → 解析 [n] 引用；DATA 链路见 Tool Calling 节；
  `department` 可选，检索范围 = 该部门 + shared 共享库，不传全库检索
- `POST /api/chat/stream`：SSE 流式，**仅 KNOWLEDGE 链路**（DUAL 不支持流式，一期边界）
- `POST /api/chat/stream`：SSE 流式（仅 KNOWLEDGE 链路），delta/end/error 三种事件帧，
  end 帧携带完整回答+citations；客户端用 fetch+ReadableStream（EventSource 不支持 POST）；
  MVC async 超时已配 120s，Nginx 代理需关缓冲
- 查询改写：`RewriteQueryTransformer`，指代消解历史来源二选一：传 `conversationId`
  用服务端记忆（`ConversationMemoryService` 滑窗 10 条=5 轮，TTL 30 分钟惰性清理），
  未传但有 history 走兼容路径；响应 question 字段返回实际检索问题（改写后）
- 多查询检索：`MultiQueryExpander`（2.0 组件，1.x 的 ExpansionQueryTransformer 已改名），
  3 变体 + 原始问题共 N+1 路，RRF 融合排序；每次问答多一次 LLM 调用 + N+1 次检索，
  `rag.chat.multi-query.enabled=false` 关闭；扩展失败自动降级单路检索
- 阈值默认 0.4；bge-small-zh 相关查询 0.4~0.7、无关 <0.3
- embedding 模型：bge-small-zh-v1.5（384 维，中文），**本地文件**在
  `src/main/resources/models/bge-small-zh-v1.5/`（tokenizer.json + model.onnx，classpath 加载，
  离线可用，打包进 jar 使 jar 体积 +95MB）；EMBEDDING_MODEL_URI / EMBEDDING_TOKENIZER_URI
  环境变量可覆盖为远程地址；换回默认 all-MiniLM-L6-v2 需清空 yml 两条 uri 配置
- 引用：回答只返回实际被引用的片段；模型未标注编号时回退全量命中

### ERP Tool Calling（erp.tool.*）

- `/api/chat` 单入口，`IntentRouter` 先做意图分类（LLM 结构化输出 KNOWLEDGE|DATA），
  兜底全倾向 KNOWLEDGE（路由失败/开关关闭/解析非法）
- DATA 链路：`ToolChatService` 挂 `ErpTools`（@Tool 薄壳）→ `ErpQueryService`（业务逻辑）
  → MyBatis-Plus mapper；响应含 mode/toolCalls
- **薄壳原则**：@Tool 方法只做调用记录+异常转译+转调，查询逻辑全在 ErpQueryService
  （MCP 化时零重写）；@Tool 方法内禁止查询逻辑
- 安全：DB 账号只读、强制 max-rows（默认 20）、timeout-seconds（默认 5）、
  手机号脱敏、审计日志（INFO，tool+args+耗时）
- 示例表（订单/库存/客户）：`src/main/resources/db/schema.sql` **手动执行**，
  未启用 sql init（避免本地无 MySQL 阻断启动）
- 已知坑：**Boot 4 双 Jackson**——容器 ObjectMapper 是 Jackson 3（`tools.jackson` 包名，
  java.time 开箱即用），`com.fasterxml`（Jackson 2）只是 Spring AI 等库的传递依赖、
  无对应 Bean；注入 ObjectMapper 要用 `tools.jackson.databind.ObjectMapper`

### 检索一致性策略

- 默认接受「会话中知识库更新，前后轮命中不同版本」：答案带 source 引用可回溯，
  系统提示词声明知识库可能更新
- 重建按文件粒度增量执行，未变更文件不动，半新半旧窗口最小化；demo 规模秒级重建可接受
- 强一致场景（合规/审计）扩展方向：chunk metadata 加 indexVersion，会话创建时绑定版本，
  检索按版本 filter；代价是双倍存储与旧版本清理，未实现

### 密钥管理

- 密钥、token 一律通过环境变量注入，`application.yml` 中只允许出现占位符默认值
- 当前环境变量：`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL`、`DEEPSEEK_API_KEY`、
  `GEMINI_API_KEY`、`MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`、
  `PG_URL` / `PG_USERNAME` / `PG_PASSWORD`
- 本地调试用的密钥放 `.env` 或 `application-local.yml`（已在 .gitignore 中排除）

### 多模型共存

多个 chat/embedding starter 同时在 classpath 时，必须用 `spring.ai.model.chat` / `spring.ai.model.embedding`
显式指定默认实现（当前：chat=openai，embedding=transformers），否则 Bean 歧义。

### Boot 4 注意事项（与 Boot 3 的差异）

- `spring-boot-starter-web` → `spring-boot-starter-webmvc`
- 测试 starter 按技术栈拆分：web 用 `spring-boot-starter-webmvc-test`
- `@MockBean` → `@MockitoBean`
- `DataSourceAutoConfiguration` 在 `org.springframework.boot.jdbc.autoconfigure` 包
  （不是 boot.autoconfigure.jdbc）
- Spring AI 2.0 advisor 接口更名：`CallAdvisor` / `StreamAdvisor`（原 CallAroundAdvisor 系）

### 已知坑（改配置前先看）

1. **Transformers embedding 懒加载**：Bean 创建不触发下载，首次调用 embed 才从 HF/DJL 下载
   all-MiniLM-L6-v2（384 维），内网环境需提前预热本地缓存；未来切正式向量库时索引维度须为 384
2. **SimpleVectorStore 无持久化**：知识入库数据重启即丢，集成测试造的向量数据不能跨重启复用

### RAG 评测

真实链路评测只跑 `RagEvaluationIT`，需显式激活 Profile：
`JAVA_HOME='D:\JDK\jdk21' mvn verify -P rag-eval`（需 OPENAI_API_KEY）。
评测集 `src/test/resources/eval/eval-set.csv`（27 条五类覆盖）；
报告 `target/rag-eval-report.md`（Recall@K / MRR / 引用正确率 / 兜底正确率 / token 成本）。
**基线策略：首跑只记录不断言（RECALL_THRESHOLD=0），基线出来后收紧**；
之后每次链路改动重跑对比（决策看数字，实验方法见 knowledge evaluation-design.md §6）。

### Token 用量统计

`TokenUsageAdvisor` 挂两个 ChatClient 的 defaultAdvisors，覆盖全链路 LLM 调用
（含框架内部改写/扩展与 tool-call 循环、流式）；数据进 Micrometer counter
（`/actuator/metrics/llm.tokens.total` 可查）+ INFO 日志；评测报告按 counter
差值聚合成本列。**注意 2.0 advisor 接口更名**：CallAdvisor/StreamAdvisor
（1.x 的 CallAroundAdvisor 系已改名），adviseCall 返回 ChatClientResponse。

## 构建与验证

```bash
# 本机默认 JDK 是 8，必须显式指定 JDK 21
JAVA_HOME='D:\JDK\jdk21' mvn clean test

# 本地启动（需可达的 PG + MySQL：PG 存向量、MySQL 供 Tool Calling）
JAVA_HOME='D:\JDK\jdk21' mvn spring-boot:run
```

改完代码必须跑 `mvn clean test` 验证，不要只改不验。

## 版本升级记录

- 2026-10-21 初始化：Boot 4.1.1 + Spring AI 2.0.0
- 2026-10-21 引入 RAG 依赖集：多模型 + ES 向量库 + 本地 embedding + MyBatis-Plus + Tika
- 2026-10-22 向量库由 ES 换为内存 SimpleVectorStore（ES 服务端版本不够，正式库候选 pgvector/Qdrant）
- 2026-11-03 pgvector 切换完整验证后回退内存版（本地 PG 未就绪），切换步骤与踩坑记录在知识库
- 2026-11-05 双路由并行编排（DUAL 模式）：混合问题双路并行 + 汇总员，见知识库/dual-route-design.md
