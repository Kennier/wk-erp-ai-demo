# 流式输出（SSE）设计文档

> 状态：已实现（2026-10-26）｜ 创建：2026-10-26 ｜ 关联：RAG 问答（rag.chat.*）
> 一期范围：仅 KNOWLEDGE 链路流式；DATA 链路保持同步（§6 边界说明）

## 1. 背景与目标

当前 `/api/chat` 为同步 JSON：用户需等待完整生成（RAG 链路含前置检索 1~2s + LLM 生成数秒）。
本设计提供流式端点，token 逐段推送，首字延迟从「总时长」降为「前置检索时长 + 首 token 时延」。

**选型：SSE（Server-Sent Events），不用 WebSocket**——LLM 输出是「单向推送 + 请求-响应语义」，
SSE 恰好匹配且是 OpenAI/Anthropic API 的同款协议；WebSocket 的双向能力在此全是运维负担。
服务端 Spring MVC 直接返回 Reactor 类型（`spring-boot-starter-webflux` 早已引入，MVC 容器不换）。

**非目标**：DATA 链路流式（二期，见 §6）；WebSocket；双向交互。

## 2. 接口与协议

### 2.1 端点

```
POST /api/chat/stream          # 新增，produces = text/event-stream
POST /api/chat                 # 保留同步端点，双端并存
```

请求体复用 `RagChatRequest`。显式端点而非 Accept 头协商，前端选择清晰。

### 2.2 事件帧协议

```
event: delta
data: {"type":"delta","content":"文档"}

event: end
data: {"type":"end","answer":"完整回答","citations":[...]}

event: error
data: {"type":"error","message":"生成中断，请重试"}
```

- **delta**：token 增量
- **end**：流结束后发，含完整回答与最终 citations。
  **citations 必须在流结束才发**：引用解析依赖完整回答中的 `[n]` 标注，
  流开始前无法确定「实际被引用的片段」，故不在开头发 candidates
- **error**：生成阶段异常时发一帧错误并正常收尾（HTTP 200 内的 SSE 错误语义，
  客户端据此提示；前置检索阶段的异常在连接建立前抛出，走普通 HTTP 错误）

帧载荷为类型化 `ChatStreamFrame` record（type + 各帧专属字段，NON_NULL），
由 Spring 容器消息转换器自动 JSON 序列化——service 层不出现手动 JSON 编码；
SSE 的 `event:` 字段与 data 中的 `type` 双通道保留（前者供 EventSource 类客户端
addEventListener，后者供 fetch 解析器统一分发）。

### 2.3 引用标注的 UX 说明

回答中的 `[1]` 标注会随打字机效果逐字出现，前端可做后处理美化（如渲染为角标）；
后端不做 delta 级别的标注拆分（复杂度高收益低）。

## 3. 服务端设计

### 3.1 链路改造（RagChatService）

```
chatStream(request):
  ① 前置阶段（同步，复用现有链路）：改写 → 扩展 → 多路检索 RRF → 父块回溯 → 组装 prompt
  ② 生成阶段（流式）：chatClient...stream().content() → Flux<String>
  ③ 帧装配：
     delta 帧 = token 增量
     doOnNext 累积完整回答
     concatWith(end 帧) = resolveCitations(累积回答, candidates)
  ④ onErrorResume → error 帧
```

同步 `chat()` 与流式 `chatStream()` 共享①的「准备上下文」代码（提取为内部方法），
保证两条端点的检索语义永不漂移。

### 3.2 工程配置

| 项 | 值 | 原因 |
|---|---|---|
| `spring.mvc.async.request-timeout` | 120s | MVC 流式走 async，默认超时会掐断长回答 |
| 响应头 `X-Accel-Buffering: no` | 每请求携带 | Nginx 代理禁缓冲，否则打字机变「整段砸出」 |
| Content-Type | `text/event-stream;charset=UTF-8` | 中文 token 帧，缺 charset 乱码 |
| 断连 | Reactor cancel 传播至 LLM 上游 | 客户端关连接即停止生成，不再消耗 token（SSE 隐藏福利） |

## 4. 客户端连接指南

**关键限制：浏览器原生 `EventSource` 只支持 GET**，问答接口是 POST + JSON body
（history 长度不可控，不能塞查询参数），因此：

| 客户端 | 方案 |
|---|---|
| 现代浏览器（推荐） | `fetch` + `response.body.getReader()` 手动按 `\n\n` 分帧；或 `@microsoft/fetch-event-source` |
| 浏览器（GET 可接受时） | 原生 `EventSource` |
| 小程序 | `wx.request` + `enableChunked: true`，自行切帧 |
| Java 消费方 | `WebClient` 或 JDK HttpClient `BodyHandlers.ofLines()` |
| 本地验证 | `curl -N -X POST .../api/chat/stream -H "Content-Type: application/json" -d '{...}'`（-N 禁缓冲） |

`axios` 浏览器端拿不到流式响应，不使用。

## 5. 测试计划（离线）

1. 流式帧序列：mock `stream().content()` 返回 `Flux.just("文档","切块")`，
   断言产出 2 个 delta 帧 + 1 个 end 帧；end 帧 citations 由完整回答「文档切块[1]」解析
2. 无命中：前置检索为空 → 仅 1 个 end 帧（无 delta），answer 为引导文案
3. 同步/流式语义一致：相同请求下 end 帧 answer 与同步接口 answer 同源（同一 prompt 构造）
4. 生成异常：stream 抛错 → error 帧收尾，不向上抛断流

## 6. DATA 链路边界（一期不做流式的原因与二期方向）

流式下 Spring AI 的 tool-call 在 **Reactor 线程**执行，现有 `ErpToolRecorder` 的
ThreadLocal 记录会失效；且工具多轮循环的流式收益低（等待工具返回时无 token 可推）。
一期 DATA 保持同步端点。二期改造方向：recorder 改 reactor Context 传递，
`toolChatClient` 的 stream + tools 组合验证后再开放。

## 7. 改动清单

| 位置 | 内容 |
|---|---|
| `service/RagChatService` | 提取「准备上下文」内部方法；新增 `chatStream()` 返回事件帧流；构造注入 Jackson 3 ObjectMapper（帧 JSON 编码） |
| `controller/RagChatController` | 新增 `POST /api/chat/stream`（ResponseEntity 包裹，携带 X-Accel-Buffering 头） |
| `application.yml` | `spring.mvc.async.request-timeout: 120s` |
| 测试 | §5 用例 |
