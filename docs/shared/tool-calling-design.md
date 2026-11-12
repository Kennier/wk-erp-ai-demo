# ERP Tool Calling 设计文档

> 状态：已实现（2026-10-23）｜ 创建：2026-10-23 ｜ 关联：RAG 问答（rag.chat.*）、意图路由
> 示例业务域（订单/库存/客户）与表结构均为占位，切换实际 ERP 时更新实体、mapper 与 @Tool 描述

## 1. 背景与目标

项目定位是 ERP RAG 服务，但 ERP 数据查询侧目前为零（MyBatis-Plus/mysql-connector 已引入未使用）。
本设计补齐另一半：让用户能通过自然语言查询 ERP 业务数据，并与现有 RAG 知识问答共存于同一入口。

**目标**

1. 用户问「订单 SO20240001 到哪了」→ 走数据库查询并组织成自然语言回答
2. 用户问「退货流程是什么」→ 走现有 RAG 链路，行为不变
3. 路由可观测：响应暴露 `mode` 与 `toolCalls`，调试一眼看清
4. 工具层薄壳化，为将来 MCP 化保留零重写迁移路径（§8）

**非目标（本期不做）**

- 任何写操作（增删改）；混合意图的两路合并（二期）；MCP Server（仅记录迁移路径）；
- 鉴权（沿用 demo 现状，上生产前统一补）

## 2. 总体架构

```
POST /api/chat
  → IntentRouter（LLM 结构化分类：KNOWLEDGE | DATA，解析失败兜底 KNOWLEDGE）
  → KNOWLEDGE → 现有 RAG 链路（RagChatService，完全不动）
  → DATA      → ToolChainChatClient（注册 @Tool 工具，Spring AI 自动 tool-call 循环）
  → 响应扩展：{ answer, mode, toolCalls: [{name, args, resultSummary}], citations?, question }
```

**关键决策**

| 决策点 | 结论 | 理由 |
|---|---|---|
| 路由模式 | 显式意图路由（先分类再分发），非「全丢给 LLM」、非 Agentic | 企业场景要可预测、可审计、可调试；Agentic 模式 LLM 可能跳过检索直接编 |
| 工具实现 | 本地 `@Tool` 薄壳 + `ErpQueryService` 承载业务逻辑 | 单应用阶段最短路径；逻辑下沉保证将来 MCP 化零重写（§8） |
| 数据源 | 沿用现有唯一 MySQL 数据源，**账号必须只读** | 数据库层兜底安全，不靠代码自觉 |
| ChatClient | 新增独立 Bean 挂 tools，与 RAG 用的 ChatClient 分开 | 互不污染，各自调优 |

## 3. 配置设计

```yaml
erp:
  tool:
    enabled: true          # 工具总开关；false 时 DATA 意图也回退知识库
    max-rows: 20           # 工具查询强制行数上限，防止 LLM 触发全表捞取
    timeout-seconds: 5     # 单次工具执行超时

rag:
  chat:
    intent-router:
      enabled: true        # 意图路由开关；false 时全部走知识库（现状行为）
```

## 4. 工具层设计（示例业务域：订单/库存/客户）

### 4.1 分层与薄壳原则

```
tools/ErpTools（@Tool 薄壳：参数校验 + 转调 + 异常转译，不含查询逻辑）
  → service/ErpQueryService（业务逻辑：查询组装、行数限制、脱敏）
    → mapper/ErpOrderMapper / ErpStockMapper / ErpCustomerMapper（MyBatis-Plus）
```

**约束：`@Tool` 方法内禁止出现 SQL/查询逻辑**——工具逻辑全部在 `ErpQueryService`，
将来 MCP Server 直接包同一 Service。

### 4.2 示例工具清单

| 工具 | 参数 | 返回 | 使用时机（写入 @Tool description） |
|---|---|---|---|
| `queryOrder` | orderNo（SO+8 位数字） | 状态/金额/客户/交期 | 用户询问订单进度、状态、发货情况；**未提供订单号时不要调用** |
| `queryStock` | sku | 可用量/仓库分布 | 用户询问某商品库存、是否有货 |
| `queryCustomer` | customerName（模糊） | 信用额度/联系方式/主营 | 用户询问客户信息；名称不完整也可调用 |

### 4.3 工具定义示例（ErpTools 薄壳）

```java
public class ErpTools {

    @Tool(description = """
            按订单号查询销售订单详情（状态/金额/客户/交期）。
            当用户询问订单进度、订单状态、发货情况时使用。
            用户未提供订单号时不要调用，先向用户询问订单号。
            """)
    public String queryOrder(
            @ToolParam(description = "订单号，格式 SO+8位数字，如 SO20240001") String orderNo) {
        return ErpToolSupport.safe(() -> erpQueryService.findOrder(orderNo));
    }
}
```

描述写清「什么时候用、什么时候不用、参数格式」——工具描述质量直接决定 LLM 选择准确率。

### 4.4 异常语义（统一在薄壳层转译）

| 异常 | 转译给 LLM 的文本 |
|---|---|
| 参数格式非法 | 「订单号格式不正确，应为 SO+8 位数字」 |
| 查无数据 | 「未找到订单 {orderNo}，请确认订单号是否正确」 |
| 数据库/超时异常 | 「订单系统暂时不可用，请稍后再试」（不暴露堆栈） |

返回 String（JSON 序列化的查询结果或友好提示），由 LLM 组织自然语言回答。

## 5. 意图路由设计（IntentRouter）

### 5.1 分类协议

LLM 结构化输出（Spring AI `.entity()` / BeanOutputConverter）：

```java
record IntentClassification(String intent, String reason) {}
// intent 取值：KNOWLEDGE（知识/流程/制度类问题）| DATA（查订单/库存/客户等业务数据）
```

路由提示词要点：给出两类定义 + 各 2 个示例；无法判断时输出 KNOWLEDGE。

### 5.2 兜底规则（全部倾向知识库）

- 路由 LLM 调用失败、解析失败、intent 值非法 → 一律 KNOWLEDGE
- `erp.tool.enabled=false` 或 `rag.chat.intent-router.enabled=false` → 全部 KNOWLEDGE
- 理由：RAG 链路无 DB 依赖、最成熟；路由错了用户重问即可，而误触数据库的代价更高

### 5.3 DATA 链路

```java
String answer = toolChatClient.prompt()
        .system(DATA_SYSTEM_PROMPT)   // 你是 ERP 数据助手，基于工具返回结果回答，查不到就直说
        .user(question)
        .tools(erpTools)
        .call().content();
```

Spring AI 自动完成 tool-call 循环（LLM 请求工具 → 框架执行 → 结果回传 → LLM 生成），
工具执行日志在框架层与审计层各记一份（§7）。

## 6. 接口与响应扩展

`POST /api/chat` 响应新增字段（向后兼容，旧字段不动）：

```json
{
  "answer": "订单 SO20240001 当前状态为已发货…",
  "mode": "DATA",                    // KNOWLEDGE | DATA
  "toolCalls": [                     // 仅 DATA 模式返回
    { "name": "queryOrder", "args": {"orderNo": "SO20240001"}, "resultSummary": "1 行" }
  ],
  "citations": [],                   // DATA 模式为空数组
  "question": "…"
}
```

实现上 controller 层按路由结果分别调用 `RagChatService` / `ToolChatService`，
两者返回统一的内部模型后组装响应。

## 7. 安全与治理

| 项 | 措施 |
|---|---|
| 数据库权限 | 工具专用 MySQL 账号仅 SELECT（**数据库层兜底，先于代码实现**） |
| 行数限制 | `ErpQueryService` 内强制 `max-rows`（默认 20），超限截断并在结果中注明 |
| 超时 | 工具执行超时 `timeout-seconds`（默认 5s），超时按异常语义转译 |
| 审计 | 每次 tool call 记录：question、intent、tool name、args、返回行数、耗时（INFO 日志，格式化便于检索） |
| 脱敏 | 手机号/证件号在 `ErpQueryService` 输出前掩码；金额字段首期保留 |
| 描述质量 | 工具与参数描述按 §4.2 模板评审后合入，description 改动视为接口变更 |

## 8. MCP 迁移路径（本期不实施）

当前为单应用，工具本地实现最短路径。出现以下任一信号时重估 MCP 化：
第二个 AI 应用需要查询 ERP / 工具维护权移交 ERP 团队 / 公司立项统一 AI 工具平台。

迁移方式（得益于薄壳原则，核心逻辑零重写）：
`ErpQueryService` 原样复用，新增 MCP Server（Spring AI MCP Server starter）把
`ErpQueryService` 方法注册为 MCP tools，wk-ai 增加 MCP Client 依赖并指向该 Server，
删除本地 `@Tool` 薄壳与 `tools/` 包即可。

## 9. 测试计划（沿用离线单测风格）

1. 意图路由：知识问题→KNOWLEDGE、数据问题→DATA（mock ChatClient 结构化输出）
2. 兜底：路由 LLM 抛异常/解析失败/intent 非法 → 全部回退 KNOWLEDGE
3. 开关：intent-router=false 时路由器不装配，全部走知识库（现状行为）
4. 工具执行：mock ErpQueryService，验证薄壳的参数校验与三类异常转译文本
5. 行数限制：service 返回超限列表时截断并注明（service 层单测）
6. 响应组装：KNOWLEDGE 模式 citations 正常、toolCalls 为空；DATA 模式反之
7. 集成冒烟：contextLoads 验证双 ChatClient 与工具 Bean 装配（无 DB 依赖，mapper mock 或不触发查询）

## 10. 改动清单

| 位置 | 内容 |
|---|---|
| `config/ErpToolProperties` | erp.tool.* 配置（enabled/max-rows/timeout） |
| `config/ToolChatClientConfig` | DATA 链路专用 ChatClient Bean |
| `tools/ErpTools` | @Tool 薄壳（订单/库存/客户 3 个示例工具） |
| `service/ErpQueryService` | ERP 查询业务逻辑（行数限制/脱敏/组装） |
| `service/IntentRouter` | LLM 结构化意图分类 + 兜底 |
| `service/ToolChatService` | DATA 链路编排（prompt + tools + 审计日志） |
| `mapper/`、`model/` | MyBatis-Plus mapper 与实体（按实际 ERP 表） |
| `dto/RagChatResponse` 等 | 响应扩展 mode/toolCalls |
| `controller/RagChatController` | 路由分发汇聚 |
| yml | §3 配置 |
