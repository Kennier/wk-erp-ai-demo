# RAG 评测基线设计文档

> 状态：已实现（2026-11-03）｜ 创建：2026-11-03 ｜ 关联：rag-eval Profile、TokenUsageAdvisor

## 1. 目的

把 RAG 链路的每个工程决策从「拍脑袋」变成「控制变量实验」：评测集是固定的考卷，
链路配置是考生，改一个配置跑同一份考卷，分数变化直接归因于该配置。
可回答的问题：topK 取多少、multi-query 开不开、要不要 rerank、换 embedding 值不值。

**方法纪律**：评测集冻结（改评测集 = 所有历史实验作废重跑基线）；单变量；质量 + 成本双列。

## 2. 评测层次

| 层次 | 内容 | LLM 依赖 |
|---|---|---|
| 检索评测 | 走真实前置链路（改写→扩展→RRF→父块回溯），命中 sources 对比期望来源 | multi-query 扩展依赖 LLM |
| 端到端评测 | 完整 chat()：引用正确率、兜底正确率 | 是 |

## 3. 评测集

`src/test/resources/eval/eval-set.csv`，竖线分隔（question 可含逗号）：

```
category|department|expected_sources(;分隔)|question
direct||shared/getting-started.md|文档切块用什么组件？
```

| 类别 | 验证什么 |
|---|---|
| direct | 直接命中：问文档里明确写的内容 |
| paraphrase | 同义改写：换说法问同一知识（检验 embedding 语义能力） |
| unanswerable | 库里没有的问题 → 期望兜底不编造 |
| department | 部门过滤正确性（期望命中本部门 + 不得出现其他部门文档） |
| multi | 跨文档：期望多个 source 都命中 |

20~50 条起步，从 docs/ 知识文档派生（每篇挑 2~3 个要点出题）。

## 4. 指标

| 指标 | 计算 | 衡量 |
|---|---|---|
| Recall@K | 期望 source 出现在命中列表的用例比例 | 召回（核心） |
| MRR | 期望 source 首次命中排名倒数的平均 | 排序质量 |
| 引用正确率 | citations 含期望 source 的比例（direct/paraphrase 用例） | 端到端可信度 |
| 兜底正确率 | unanswerable 用例正确返回「未找到」的比例 | 幻觉防线 |
| token 成本 | 每用例 LLM token 消耗（TokenUsageAdvisor counter 前后差值） | 成本列 |

## 5. 运行与报告

```bash
set OPENAI_API_KEY=...   # multi-query 与生成评测需要
JAVA_HOME='D:\JDK\jdk21' mvn verify -P rag-eval
```

- 普通构建完全不触发（@EnabledIfSystemProperty rag.eval.enabled）
- 报告输出 `target/rag-eval-report.md`：逐用例明细 + 汇总指标 + token 总耗
- **首跑只记录不断言**（阈值 0 放行）：跑出基线数字后，再在
  `RagEvaluationIT.RECALL_THRESHOLD` 收紧（建议设为基线 - 0.05 作为回归防线）
- 前置条件：内存向量库 + 本地 embedding 已就绪；真实链路含入库（启动时自动加载 docs/）

## 6. 实验模板（评测的用法）

以「topK 取多少」为例：只变 topK（2/3/5/8/10）跑五轮，记录 Recall@K + MRR + token，
找「召回不再显著提升、token 仍线性增长」的拐点。其他实验（multi-query 开关、rerank
前后、换 embedding）同理：单变量、同考卷、质量与成本对比后决策。

## 7. 二期扩展

- LLM-as-judge 忠实度评测（回答是否完全基于检索片段）
- pgvector 落地后跑对比（内存库与 PG 检索结果应一致，验证 filter 下推）
- 评测集扩充至 50+ 条，覆盖更多文档
