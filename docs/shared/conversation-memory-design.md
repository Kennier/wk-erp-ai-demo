# 服务端会话记忆设计文档

> 状态：已实现（2026-10-30）｜ 创建：2026-10-30 ｜ 关联：RAG 问答（rag.chat.*）
> 一期范围：KNOWLEDGE 链路记忆（内存存储）；DATA 链路二期

## 1. 背景与目标

当前会话历史由前端显式传入（history 字段），服务端无状态。问题：每次请求打包全部历史
（token 成本线性增长）、前端接入成本高、非浏览器客户端不友好。

**目标**：conversationId 标识会话，服务端按滑动窗口保存最近几轮对话并自动用于查询改写与生成；
与现有 history 传入方式渐进兼容。

## 2. 记忆量：滑动窗口 10 条消息（5 轮）

**不是记得越多越好**，三条依据：
1. 指代消解实际只依赖最近 1~3 轮（「它怎么切分」的「它」几乎总是上一轮话题）
2. token 成本随历史线性增长，旧历史对当前问题无贡献
3. 注意力稀释（lost-in-the-middle）：历史过长反而降低答案质量

| 用途 | 配额 | 说明 |
|---|---|---|
| 查询改写 | 最近 **3 轮**（6 条消息） | 取自窗口尾部；`max-history-turns` 语义不变 |
| 生成阶段 | 窗口全部（默认 **10 条 = 5 轮**） | `MessageWindowChatMemory.maxMessages(10)`，与 Spring AI 默认一致 |
| 长会话压缩 | 不做 | LLM 摘要压缩为二期，真有 20+ 轮场景再上 |

记忆内容 = **对话原文**（用户原始问题 + 回答），不缓存检索结果（检索每轮实时做，
避免会话-索引一致性问题）。

## 3. 接口变更（渐进兼容）

```
请求：RagChatRequest 新增可选 conversationId
响应：RagChatResponse 新增 conversationId（新建会话时返回服务端生成的 UUID）

优先级：
  传 conversationId  → 服务端记忆（忽略 history 字段）
  未传但有 history   → 走现状（老客户端零改动）
  都没有             → 单轮
```

同步与流式端点行为一致；`POST /api/chat/stream` 的 end 帧也携带 conversationId
（帧结构复用 ChatStreamFrame，需扩展字段——见 §5 决策）。

## 4. 技术方案

### 4.1 Spring AI ChatMemory

```java
MessageWindowChatMemory.builder()
        .chatMemoryRepository(InMemoryChatMemoryRepository())  // 内存版，随向量库一起升级 JDBC
        .maxMessages(10)
        .build();
```

手动读写而非 advisor 挂载：现有 RagChatService 手工组装 prompt（改写器与生成共用
同一份历史），advisor 形式会与手动 prompt 冲突。读：`memory.get(convId)`；
写：回答完成后 `memory.add(convId, UserMessage)` + `memory.add(convId, AssistantMessage)`。

### 4.2 ConversationMemoryService（封装层）

- `resolve(String requestedId)`：null/空 → 生成 UUID；否则原样返回
- `historyAsTurns(convId)`：读窗口并转为 ChatTurn 列表（喂改写器）
- `record(convId, question, answer)`：写窗口 + 刷新 TTL
- TTL：`ConcurrentHashMap<convId, lastAccessAt>`，默认 30 分钟；**惰性清理**
  （每次读写时顺带扫描过期会话），不引入 @Scheduled 依赖
- 记忆存对话原文；窗口截断由 MessageWindowChatMemory 在 add 时执行

### 4.3 生成侧记忆（in-generation，可选开关，默认关）

~~初版实现：在 user prompt 文本里手拼历史摘要段~~ → **重构为 Spring AI 原生 advisor 方式**：
`MessageChatMemoryAdvisor` + `advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))`。
区别：手拼把历史混进 user 文本（模型看到的是"文档"），advisor 以**结构化 messages**
（role 分明的 User/AssistantMessage 序列）注入——这是 LLM 更自然的对话格式。

**关键坑与对策：advisor 的 after 阶段会把整段 user prompt 写回记忆**（RAG 场景的
user prompt 是拼接的片段+问题，直接写回会污染历史——窗口被大段文本塞满，下一轮
改写历史也烂掉）。对策：给 advisor 挂**只读 ChatMemory 包装**（add 置空），
写回统一走 `record(用户原始问题, 回答)`。改写阶段保持手动传 Query.history
（改写链挂 advisor 会在改写时也写回，造成重复记录）。

注入量由 max-messages 窗口控制（in-generation-turns 配置已删）。

## 4.4 数据流

```
chat(request, conversationId):
  history = conversationId 非空 ? memory.historyAsTurns(id) : request.history()
  prepared = prepare(request, history)      // 改写用最近 3 轮
  answer = LLM(...)
  conversationId 非空 → memory.record(id, 用户原始问题, answer)
  响应携带 conversationId
```

流式在 end 帧组装处 record（fullAnswer 聚合完成时）；生成中断不 record。

## 5. 帧结构扩展决策

ChatStreamFrame 增加 `conversationId` 字段（end 帧携带，delta/error 帧为 null，
NON_NULL 自动隐藏）。备选：HTTP 响应头携带——SSE 场景响应头在流开始已发出，
首请求新建会话时前端无法从头中拿到 id，故走帧内字段。

## 6. 边界

- DATA 链路一期不接记忆（工具参数继承的多轮交互单独设计），conversationId 原样回传
- 会话内知识库更新：记忆存对话原文不含检索快照，一致性策略不变（L1 接受 + 引用缓解）
- 内存实现重启即丢，与内存向量库同节奏：上生产时 ChatMemory 换 JDBC Repository
  （Spring AI 官方 starter）

## 7. 测试计划（离线）

1. 记忆往返：record 2 轮 → historyAsTurns 返回 2 轮且顺序正确
2. 窗口截断：maxMessages=10 时记录 6 轮 → 只剩最近 5 轮
3. TTL：ttl=0 时任意读写触发清理，历史为空
4. resolve：null → 生成新 UUID；传入原样返回
5. 集成：带 conversationId 的问答 → 改写使用服务端历史；响应回传 conversationId
6. 兼容：不带 conversationId 带 history → 行为与现状一致（不触记忆）

## 8. 改动清单

| 位置 | 内容 |
|---|---|
| `config/ConversationMemoryProperties` | rag.chat.memory.*（enabled/max-messages/ttl-minutes） |
| `service/ConversationMemoryService` | 封装 ChatMemory：resolve/historyAsTurns/record/惰性 TTL |
| `dto/RagChatRequest` / `RagChatResponse` | +conversationId；ChatStreamFrame +conversationId |
| `service/RagChatService` | chat/chatStream 重载（带 conversationId）；历史来源切换 |
| `controller/RagChatController` | resolve conversationId 并透传 |
| yml | rag.chat.memory.* |
| 测试 | §7 |
