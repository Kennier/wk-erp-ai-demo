# 双路由并行编排（DUAL）设计文档

> 状态：已实现（2026-11-05）｜ 创建：2026-11-05 ｜ 关联：意图路由、RAG 问答、Tool Calling
> 一期范围：同步接口支持 KNOWLEDGE + DATA 双路并行；流式端点维持单 KNOWLEDGE 路径

## 1. 背景与场景

当前 IntentRouter 对意图做单选（KNOWLEDGE 或 DATA 二选一），混合问题
（「退货流程是什么？我订单 SO20240001 现在什么状态？」）只能命中一边。

本设计让路由支持多意图：问题同时涉及知识问答与数据查询时，**两条专家链路并行执行，
汇总员整合为一个连贯回答**。纯单域问题的行为完全不变。

三层能力边界（本设计为中间层）：

| 层 | 场景 | 状态 |
|---|---|---|
| 单链路多工具 | 纯 ERP 多域查询（订单+库存），tool-call 循环内连续调用多个工具 | 已支持（Spring AI 原生） |
| **双路并行编排（本文）** | 知识 + 数据混合问题 | 本设计 |
| 动态任务拆解多 Agent | 需 Planner 拆解的复杂推理 | 远期，等 SAA 支持 Boot 4 或需求出现 |

## 2. 架构

```
POST /api/chat
  → InputGuard（不变）
  → conversationId 解析（不变）
  → IntentRouter.classifyIntents(question) → List<String>（可为多元素）
  → ChatOrchestrator.orchestrate(request, conversationId, intents):
      [KNOWLEDGE]         → 直通 ragChatService.chat(request, conversationId)   （现状行为）
      [DATA]              → 直通 toolChatService.chat(request, conversationId)  （现状行为）
      [KNOWLEDGE, DATA]   → 并行编排：
          history = memory.historyAsTurns(conversationId)          ← 编排层统一取历史
          子链路 request 重建（history 注入、conversationId=null）  ← 子链路不读写记忆，防重复记录
          CompletableFuture×2（虚拟线程 + MDC 快照恢复）：
            ├─ KNOWLEDGE 路：ragChatService.chat(subRequest, null)
            └─ DATA 路：toolChatService.chat(subRequest, null)
          失败隔离：一路异常 → 该路结果替换为降级说明，另一路照常
          汇总员（DualSummarizer，一次 LLM 调用）：合并为连贯回答
          编排层 record(conversationId, 原始问题, 汇总答案)         ← 会话只记最终回答一轮
          响应：mode=DUAL、citations=知识路、toolCalls=数据路
```

## 3. 关键设计决策

| 决策点 | 方案 | 理由 |
|---|---|---|
| 并行执行 | 虚拟线程（Java 21）+ 手动 MDC 快照恢复 | `Hooks.enableAutomaticContextPropagation` 只作用于 Reactor，CompletableFuture 需手动快照/恢复；延迟 = 最慢链路而非求和 |
| 会话记忆防重复 | 子链路 conversationId=null（走 history 兼容路径），编排层统一 record 汇总回答 | 否则知识路 record 子答案 + 编排层 record 汇总 = 重复记录 |
| 引用归属 | 汇总 prompt 要求保留知识路回答中的 [n] 标注；citations 直接沿用知识路结果 | 汇总员只整合不重新推理，引用与依据不脱节 |
| 失败隔离 | 一路异常 → 该路以「XX 系统暂时不可用」占位参与汇总 | 一路故障不拖死整个回答 |
| erp.tool.enabled=false | intents 中的 DATA 剔除后分发 | 开关语义与现状一致 |
| 流式端点 | 不支持 DUAL（intents 含 DATA 时同步接口才能完整体验） | 两路流式 + 汇总的流式组合复杂度高，一期不做 |
| 汇总模型 | 复用 chatClient（spring.ai.model.chat 默认模型） | 与知识链路同模型，行为一致 |

## 4. 路由协议（IntentRouter 改造）

```java
record IntentClassification(List<String> intents, String reason) {}
// intents 元素：KNOWLEDGE | DATA；可同时含两者
```

路由提示词：单域输出单元素数组，混合问题输出两元素；无法判断输出 ["KNOWLEDGE"]。
兜底规则不变：解析失败/intent 非法/空数组 → [KNOWLEDGE]；erp.tool.enabled=false 时
DATA 从结果中剔除（orchestrator 侧）。

## 5. 汇总员 prompt 要点

```
用户问题：{question}

以下是两个子系统对同一问题的处理结果：
【知识库回答】：{knowledgeAnswer}
【业务数据查询结果】：{dataAnswer}

请将两部分整合为一个连贯、自然的中文回答：
1. 只整合以上内容，不要自行补充新信息
2. 知识库回答中的 [编号] 引用标注原样保留
3. 数据结果与知识描述冲突时，以数据结果为准
```

## 6. 改动清单

| 位置 | 内容 |
|---|---|
| `service/IntentRouter` | classify → classifyIntents（多意图协议） |
| `service/DualSummarizer` | 汇总员：一次 LLM 调用合并两路结果 |
| `service/ChatOrchestrator` | 并行编排：分发/虚拟线程/失败隔离/MDC 快照/记忆防重 |
| `controller/RagChatController` | 同步端点接入编排 |
| `dto/RagChatResponse` | mode=DUAL（citations 与 toolCalls 并存） |
| 评测集 | 补 dual 类用例（混合问题），IT 断言双路结果都体现 |
