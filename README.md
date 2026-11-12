# wk-ai

基于 **Spring Boot 4.1.1 + Spring AI 2.0.0 + JDK 21** 的 ERP RAG 服务：多模型对话（DeepSeek / OpenAI 兼容 / Gemini）+ 向量检索（当前为内存版 SimpleVectorStore，开发期）+ 本地 Transformers Embedding（ONNX，无需外部 embedding 服务）+ MyBatis-Plus（ERP MySQL Tool Calling）。

## 快速开始

```bash
# 1. 配置环境变量（真实密钥不进代码库，按需设置）
export OPENAI_API_KEY=sk-xxx          # OpenAI 兼容网关
export OPENAI_BASE_URL=https://api.openai.com
export DEEPSEEK_API_KEY=sk-xxx        # DeepSeek
export GEMINI_API_KEY=xxx             # Google Gemini
export MYSQL_URL=jdbc:mysql://localhost:3306/wk_ai

# 2. 构建并测试（本机默认 JDK 是 8，需显式指定 JDK 21；单测完全离线）
JAVA_HOME='D:\JDK\jdk21' mvn clean test

# 3. 启动（当前无需 ES/向量数据库，开箱即起）
JAVA_HOME='D:\JDK\jdk21' mvn spring-boot:run
```

服务启动后监听 `8080`。默认模型组合：chat=openai、embedding=transformers（本地 ONNX），
通过 `spring.ai.model.*` 可切换。

## 本地 embedding 模型（必须手动下载）

embedding 模型文件不入 git（90MB 二进制），需手动下载放到指定路径：

```bash
mkdir -p src/main/resources/models/bge-small-zh-v1.5
# 国内从 hf-mirror 下载（两个文件都要）
curl -L -o src/main/resources/models/bge-small-zh-v1.5/tokenizer.json \
  "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/tokenizer.json"
curl -L -o src/main/resources/models/bge-small-zh-v1.5/model.onnx \
  "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx"
```

放置路径：`src/main/resources/models/bge-small-zh-v1.5/`（tokenizer.json 439KB + model.onnx 94.8MB）。
也可通过环境变量 `EMBEDDING_MODEL_URI` / `EMBEDDING_TOKENIZER_URI` 指向远程地址，跳过本地文件。

## RAG 链路说明

| 组件 | 依赖 | 说明 |
|---|---|---|
| 对话模型 | `spring-ai-starter-model-openai` / `-deepseek` / `-google-genai` | 三者共存，`spring.ai.model.chat` 指定默认 |
| Embedding | `spring-ai-starter-model-transformers` | 本地 ONNX all-MiniLM-L6-v2（384 维），排除 pytorch-engine |
| 向量库 | `spring-ai-vector-store`（SimpleVectorStore） | 内存版，`VectorStoreConfig` 手动装配；正式库候选 pgvector / Qdrant，切换只改配置（见 CLAUDE.md 选型记录） |
| RAG 编排 | `spring-ai-vector-store-advisor`（QuestionAnswerAdvisor）、`spring-ai-rag`（RetrievalAugmentationAdvisor） | 简单场景用前者，精细检索控制用后者 |
| 文档入库 | `spring-ai-tika-document-reader` | PDF/Word/PPT/HTML 通吃 |

## 依赖版本

| 组件 | 版本 |
|---|---|
| Java | 21（本机路径 `D:\JDK\jdk21`） |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.0（`spring-ai-bom`） |
| MyBatis-Plus | 3.5.16（`mybatis-plus-spring-boot4-starter`） |
