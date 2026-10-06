# AI / RAG Guidelines

> AI 调用与知识检索（三域 RAG）的跨层契约。适用于新增「AI 合成」或「知识检索」链路。

---

## Overview

- AI 文本统一走 `com.sparkora.ai.AiClient`；**C1 起内部委托 Spring AI 2.0 `ChatClient` + `OpenAiChatModel`**
  （公共 API 不变，仅实现从手写 RestClient 换为框架调用；base-url 由 `application.yml` 归一化补 `/v1`）。
  AxonHub 的 OpenAI 兼容端点是 `/v1/chat/completions`。
- 知识检索统一走 `com.sparkora.car.service.CarRagService`（pgvector 三域统一检索）。
- 失败抛 `com.sparkora.ai.AiException`，由控制器映射 500（见 error-handling.md）。

---

## Scenario: AI 文本合成（单轮 / 多轮）

### 1. Scope / Trigger
- Trigger: 新增 AI 文本生成/合成链路（简报、正文、问答…）。

### 2. Signatures
```java
// 现有（保持不动）
AiClient.ChatResult chat(String systemPrompt, String userPrompt, int maxTokens)      // 纯文本
AiClient.ChatResult chatJson(String systemPrompt, String userPrompt, int maxTokens)  // 强制 response_format=json_object
// 多轮（保留兼容；C4 起问答链路改用下方 chatWithMemory）
AiClient.ChatResult chatMessages(List<Map<String,String>> messages, int maxTokens)
// C4 新增（非破坏）：由 Spring AI ChatMemory 装配历史
AiClient.ChatResult chatWithMemory(String conversationId, String systemPrompt, String userPrompt,
                                   List<Map<String,String>> history, int maxTokens)
```
- `messages` 每项 `{role, content}`，`role∈{system,user,assistant}`；顺序即对话顺序。
- `ChatResult{content, model, totalTokens, finishReason, reasoning}`（09-27-brief-writing-linkage-fix：第 4 分量 `finishReason` 透出 `finish_reason`；10-02-brief-reasoning-maxtokens：第 5 分量 `reasoning` 透出推理过程——读 `message.reasoning`，缺省回退 `message.reasoning_content`（不同模型字段名不同，实测 axonhub→`deepseek-v4.1-flash` 用 `reasoning`），落库/透出前按 `REASONING_MAX_CHARS=20000` 截断；**保留 3 参/4 参构造器**默认 null——既有 `new ChatResult(content,model,tokens[,finishReason])` 调用方与测试编译不受影响）
  - **非 JSON 调用的截断判定**：`chat`/`chatMessages`（不强制 JSON）在 `finish_reason=length` 时**不抛异常**（内容为半截），调用方须自判 `"length".equals(cr.finishReason())` 并提额重试（先例 `DeepWriterService.write` 4096→8192）；`chatJson` 仍由 `parseChat` 直接抛截断 `AiException`。
  - **reasoning 模型的额度陷阱（10-02 实测）**：`AI_MODEL=deepseek-v4-pro-cus` 被 axonhub 路由到 reasoning 模型 `deepseek-v4.1-flash`，先吐大段 `reasoning` 再吐 `content`。`max_tokens` 是**含推理的总预算**——额度不足时推理吃光预算、`content` 为空、`finish_reason=length`（实测 4096 全烧在推理上、`content=""`；同一 prompt 提到 16384 得 `finish_reason=stop`、约 6000 推理 + 2580 正文）。故 reasoning 模型下 JSON 类调用额度须按「推理 + 正文」估算，并一律配「截断/空内容/非法 JSON → 提额一倍重试一次」范式（ClarifyService 8192→16384、BriefService 同构）。

### 3. Contracts
- 三方法共用同一懒构建 `ChatClient`（`OpenAiChatModel`；注入路径复用自动配置的 `ChatModel`，单测直 `new` 时按 `AiProperties` 自建）与 `parseChat()`。
- **任务级温度（C1）**：`chatJson`→结构化低温（`sparkora.ai.temperature-structured`，默认 0.2）、`chat`→正文高温（`temperature-prose`，默认 0.7）、`chatMessages`/`chatWithMemory`→问答中温（`temperature-qa`，默认 0.5）；任务类型由调用方法唯一决定（`TaskType`）。`max_tokens` 仍由调用点显式传入。
- `chatMessages`/`chatWithMemory` 不设 `response_format`。
- **`chatWithMemory`（C4）**：局部 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor`（per-call advisor + `.param(ChatMemory.CONVERSATION_ID, cid)`）装配历史；advisor 线序 `system → 历史升序 → 本轮 user`；`maxMessages=history.size()+2`；每调用独立 memory，**不跨调用持久化**（DB 为历史唯一权威）。
- **`chatJson` 强制 `response_format=json_object`**（`OpenAiChatOptions.responseFormat(JSON_OBJECT)`），等价旧实现。
- **base-url 归一化**：Spring AI OpenAI SDK 只追加 `chat/completions`（**不带 `/v1`**），故 `application.yml` 配 `${AI_BASE_URL}/v1`（`AiClient.normalizeBaseUrl` 自建路径同规则补 `/v1`）。`AI_BASE_URL` 语义仍为网关根地址（不含 `/v1`）。
- **finish_reason 归一**：Spring AI/OpenAI SDK 返回大写（`STOP`/`LENGTH`，来自 `Generation.metadata.finishReason`），`parseChat` 归一为小写后再透出/判定，保持旧契约（调用方判 `"length"`）。
- **reasoning 透出**：Spring AI 已把 `reasoning_content`→`reasoning` 回退后写入 `assistantMessage.metadata["reasoningContent"]`，`parseChat` 读该 key，超 `REASONING_MAX_CHARS` 截断。
- `content` 为空（reasoning 截断）→ `parseChat` 抛 `AiException`，**不要返回半截内容**。
- **Prompt 资产化（C1）**：固定指令外置到 `src/main/resources/prompts/**`（首行 `# version: vN`，Git 管理）；用 `com.sparkora.ai.PromptTemplateLoader`（静态、`{{var}}` 占位、对 JSON 花括号零侵入）加载。动态数据（主题/手册/RAG/风格/排版分档）由 Java 组装后作为变量传入；**生产代码无内联长 prompt 文本块**。
- **超时/重试必须显式配置（C1 踩坑）**：Spring AI 的 OpenAI 客户端默认 **60s 读超时 + 3 次自动重试**，会掐断长 reasoning/长正文并放大失败代价；旧手写实现是 `AI_TIMEOUT_MS`（默认 120s）且不重试。约定 `application.yml` 配 `spring.ai.openai.timeout: ${AI_TIMEOUT_MS:120000}ms` + `spring.ai.openai.max-retries: 0`（重试语义仍由上层「截断/非法 JSON 提额重试一次」承担）。改 AI 超时只动 `AI_TIMEOUT_MS`。
- **观测依赖 Actuator（C1）**：`AiObservabilityAdvisor` 经 `ObjectProvider<MeterRegistry>` 取指标注册表；`MeterRegistry` bean 由 `spring-boot-starter-actuator` 提供（仅此依赖；micrometer-core 虽为 Spring AI 传递依赖，但**没有 starter 就没有 bean**，advisor 会退化为「只打日志」）。`application.yml` 仅暴露 `management.endpoints.web.exposure.include: health`（不对外暴露 metrics/env）。
- **base-url 双重 `/v1` 边界**：`application.yml` 直接拼 `${AI_BASE_URL}/v1`，若使用者把 `AI_BASE_URL` 填成含 `/v1` 的值会得到 `/v1/v1`；覆盖整地址请用 `SPRING_AI_OPENAI_BASE_URL`（自带 `/v1`）。`AiClient.normalizeBaseUrl` 仅用于单测/自建路径，会去重。

### 4. Validation & Error Matrix
- messages 为空/null → 上层保证非空（当前未做显式校验；调用方必传 system+user）。
- AI 超时/非 2xx/无 choices/空 content → `AiException`。
- JSON 场景模型偶发裸控制字符 → 统一 `AiClient.sanitizeAiJson(raw)` 后再 `readTree`。

### 5. Good/Base/Bad Cases
- Good: 多轮问答历史经 `chatWithMemory`（`ChatMemory` advisor 装配，线序 system→历史升序→本轮）一次合成。
- Base: 单轮文本 `chat`；无历史时 `chatWithMemory` 等价 `chat`。
- Bad: 直接把历史逐条调 `chat` 再拼接（丢失对话结构、成本高）。

### 6. Tests Required
- 断言多轮组装的 messages 顺序/角色（`chatWithMemory`：wire 线序 system→历史→本轮，`AiClientTaskOptionsTest`；`chatMessages` 既有用例保留）；AI 返回空 content 抛 `AiException`。
- **reasoning 透出**（10-02）：`parseChat` 读 `message.reasoning`；缺省回退 `message.reasoning_content`；超 `REASONING_MAX_CHARS` 截断；旧 3/4 参构造器兼容（`AiClientReasoningTest`）。

### 7. Wrong vs Correct
#### Wrong
```java
// 追问把历史压成一段字符串塞进 userPrompt，模型无法区分轮次
aiClient.chat(sys, history + "\n" + question, 2048);
```
#### Correct
```java
// 历史经 ChatMemory 装配（C4）：只传窗口历史 + system + 本轮，advisor 负责拼接
aiClient.chatWithMemory(sessionId, systemPrompt, question, historyWindow, 2048);
```

---

## Scenario: 三域知识检索（CAR / KB / NEWS）

### 1. Scope / Trigger
- Trigger: 新增需要知识库上下文的链路（生成注入、问答）。

### 2. Signatures
```java
CarRagService.RagResult retrieveForGeneration(String query, int topK, List<Long> anchorModelIds)
// RagResult{status, context, hitCount, maxScore, coveredText, citations}
// Citation{source, modelName, chunkType, score, chunkText, docId}; source∈{CAR,KB,NEWS}
//   docId 可空（09-15 qa-auto-illustrate 补读；CAR=car_chunk.id/KB=kb_chunk.id/NEWS=news_doc.id）；
//   保留 5 参兼容构造器（docId=null），既有调用方不受影响；详见本文「问答答案配图」Scenario
// RagStatus{OK, LOW_CONFIDENCE, FAILED, NO_KNOWLEDGE}
```

### 3. Contracts
- **来源标注**：`context` 首行 `知识来源：…`；块内 `【车型数据：name】/【通用知识：title】/【官方新闻：title】`。
- **配额**：CAR 核心块 `carQuota`；KB 独立 `ragKbTopk`（受 `ragKbEnabled`）；NEWS 独立 `ragNewsTopk`（**不受** `ragKbEnabled` 控制，0=关闭）。
- **锚点加权**：仅 `source=CAR` 且 `modelId∈anchorModelIds` 乘 `ragAnchorBoost`；KB/NEWS 不受影响。
- **候选窗口按域隔离**：见 database-guidelines.md「多域统一检索」。调用方 `limit` 用 `max(topK*4,32)`。

### 4. Validation & Error Matrix
- 空 query → `RagResult.EMPTY`（NO_KNOWLEDGE）。
- 检索异常 → `RagStatus.FAILED`（不抛出，降级可见）。
- 命中但 `maxScore < ragRejectScore` → `LOW_CONFIDENCE`（context 空）。
- 无命中 → `NO_KNOWLEDGE`。

### 5. Good/Base/Bad Cases
- Good: 消费 `status` 做降级提示，OK 才注入 `context`。
- Base: 未关联车型 `anchorModelIds=null`，仍检索三域。
- Bad: 无视 `status` 直接把空 `context` 当知识注入；或把 `LOW_CONFIDENCE` 的块强行使用。

### 6. Tests Required
- `status` 四态分支；`citations` 与 `context` 同源；NEWS 独立配额生效、不受 KB 开关影响；锚点仅作用 CAR。

### 7. Wrong vs Correct
#### Wrong
```java
var rag = ragService.retrieveForGeneration(q, 8, anchors);
prompt += rag.context();   // LOW_CONFIDENCE/FAILED 时 context 为空，却不告知模型
```
#### Correct
```java
var rag = ragService.retrieveForGeneration(q, 8, anchors);
prompt += rag.ok() ? rag.context() : degradeNote(rag.status());
```

---

## Scenario: 检索重排（A rerank，10-03-a-rerank）

### 1. Scope / Trigger
- Trigger: 新增/修改检索重排（`Reranker`/`LlmReranker`）、rerank 开关，或改动 `CarRagService.retrieveForGeneration` 候选合并后的阶段顺序。

### 2. Signatures
```java
// 新接口（独立可测）
List<CarRagService.UnifiedHit> Reranker.rerank(String query,
        List<CarRagService.UnifiedHit> candidates, int keepTopN)
// 实现 LlmReranker：AiClient.structured + prompts/rag/rerank-system.st + RerankOrderDto{List<Integer> order}
// 纯静态（可单测）：LlmReranker.topIndices(candidates, n) / LlmReranker.applyOrder(head, tail, order)
```

### 3. Contracts
- **插入点**：`CarRagService.retrieveForGeneration` 候选合并去重后、锚点加权/配额**之前**。
- **只改顺序、不改分数**：`maxScore`/`minScore`/`rejectScore` 与四态（`OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE`）
  判定基于原分，重排不改变四态与候选集；配额分层语义不变，仅各桶内部按重排名次取块。
- **参与集**：按原始分数降序前 `ragRerankTopN` 个（下标定位，文本重复也不丢不重），单条文本截断 300 字；
  其余原序追加。NEWS 无独立窗口，统一走全局 top-N。
- **后验校验**：越界/重复下标丢弃、缺项按原序补尾 → 返回集合与输入元素一一对应（仅顺序变）。
- **best-effort 降级**：order 空/全非法 / 超时 / 调用异常 / 开关关闭 → **原序返回 + warn，绝不抛出、绝不阻断生成**；
  超时在独立虚拟线程按 `ragRerankTimeoutMs`（默认 10s，短于 `AI_TIMEOUT_MS`）兜底。
- **位置映射**：锚点 boost 会重建 `UnifiedHit`，故重排名次在 boost 后按**位置**建映射（`IdentityHashMap`），
  关闭时回退既有「按分数降序」逐字等价。

### 4. Validation & Error Matrix
- 开关 false / 未注入 Reranker / 候选 ≤1 → 不调用；关闭态结果与现状逐条一致（零回归）。
- order 含越界/重复 → 丢弃；缺项 → 补尾；空/全非法 → 原序。
- 超时/异常 → 原序 + warn（warn 只记异常类型，不回传异常原文/密钥）。

### 5. Good/Base/Bad Cases
- Good: 开启且模型返回合法 order → 相关块上提，MRR/top-1 提升（report 5/5 例）。
- Base: 关闭态（默认）/ 模型返回空 order → 原序，行为不变。
- Bad: 重排**改写 score** 参与门槛判定（会改变四态）；或重排后仍按原分排序（重排失效）；或调用失败抛出阻断生成。

### 6. Tests Required
- `LlmRerankerTest`：正常重排；order 越界/重复/缺项集合不变仅顺序变；空 order/异常/超时回退原序；候选 ≤1 不调模型；`topIndices`。
- `CarRagServiceTest`：关闭态不调用 reranker（零回归）；开启态按新序注入；重排不改 `maxScore`；reranker 抛异常降级原序不阻断。

### 7. Wrong vs Correct
#### Wrong
```java
// 重排后仍按原分排序 → 重排白做；或把重排分数写回去 → 四态被改
selected.sort((a,b) -> Double.compare(b.score(), a.score()));
```
#### Correct
```java
// 重排后按重排名次排序（关闭时回退按分数），分数本身不动
selected.sort(orderCmp);   // orderCmp = rerankRank 或 score 降序
```

---

## Scenario: 开关契约（浏览/问答 vs 生成注入）

- `sparkora_setting.kb_enabled`（`SettingService.kbEnabled`）**只控制创作生成时是否注入知识库**。
- **浏览页与问答链路不受其控制**：`QaService` 等不得注入/读取 `SettingService`（C4 先例，`QaService` 构造器仅有 mapper + `CarRagService` + `AiClient` + `ObjectMapper`）。
- KB 域在**检索层**由 `AiProperties.ragKbEnabled` 决定（既有生成语义）；NEWS 由 `AiProperties.ragNewsTopk` 决定。

---

## Scenario: 搜索工具可用性契约（SearchTool 健康状态）

### 1. Scope / Trigger
- Trigger: 新增/修改外部搜索工具（实现 `SearchTool`），或改动工具健康展示（`/deep/status` 的 `toolHealth`）。

### 2. Signatures
```java
public interface SearchTool {
    String name();
    boolean available();                  // 仅判配置就绪(见契约)
    default boolean configured() { return true; }     // 密钥/地址是否就绪,不随调用结果变化
    default boolean lastCallOk() { return true; }     // 最近一次调用是否成功(初值乐观,仅供展示)
    List<SearchHit> search(String query, int maxResults);
    // 10-04 A:垂直搜索;web/news。默认委托 search(不支持垂直的工具零改动即合规)
    default List<SearchHit> searchVertical(String query, String vertical, int maxResults) { return search(query, maxResults); }
    default List<SearchHit> extract(List<String> urls, String query) { return List.of(); }  // 09-27 R1:正文补抓
}
```
- **认证差异（跨实现约定）**：Serper 用 Header `X-API-KEY`；Tavily 用 body `api_key`，两者不通用。
- **未知 vertical** 回落 `web` + warn（运行时启发式，**不抛异常**）；与 `WebProvider.from()` 对配置错误抛 `IllegalArgumentException` 刻意不同。

### 3. Contracts
- **`available()` 只表示「配置就绪」**：Tavily/Serper = 密钥非空；SEARXNG = 地址非空。**不得包含 `lastCallOk()`**——否则调用失败后 `available()==false`，调用方（`SubAgentRunner`）跳过 `search()`，而失败标志只能在被跳过的 `search()` 里重置 → **永久禁用，直到重启**（自锁死）。
- `configured()` = 配置态（与 `available()` 同源，供 `toolHealth` 三态判定复用）。
- `lastCallOk()` = 最近一次调用健康态，仅用于**展示**；调用失败置 false 不再影响门控，故下次研究天然重试（自恢复）。
- `toolHealth`（`/deep/status` 响应）值为状态码字符串，非布尔：
  - `OK` | `DISABLED`（被设置门控关闭）| `UNCONFIGURED`（无 key/地址）| `FAILED`（最近一次调用失败）
  - `KB` = `SettingService.isKbEnabled() ? "OK" : "DISABLED"`（反映 DB 运行时门控，**不恒 true**）。
  - `SEARXNG`/`TAVILY`/`SERPER`：`webAllowed = DeepProperties.isSearchWebEnabled() && SettingService.isWebSearchEnabled()`；优先级 `DISABLED > UNCONFIGURED > FAILED > OK`。`SERPER` 为 10-04 A 增量键（未配置 → `UNCONFIGURED`），既有三键值域不变。
- 前端 `ResearchProgress.vue` 未拿到 `toolHealth`（首轮前/接口异常）时渲染 `--`，**不得**乐观默认全部可用。

### 4. Validation & Error Matrix
- 无 key/地址 → `configured()=false` → `available()=false` → 调用方跳过（`toolHealth=UNCONFIGURED`）。
- 密钥有效但单次调用异常 → `lastCallOk()=false`、`available()` 仍 true → 下次研究重新调用（`toolHealth=FAILED`）。
- 设置门控关闭（DB `web_search_enabled=f` 或部署级 `SEARCH_WEB_ENABLED=false`）→ `toolHealth=DISABLED`，`DeepResearchService` 配额置 0。
- KB 停用（`kb_enabled=f`）→ `toolHealth.KB=DISABLED`，`applySettingGates` 剔除 KB 工具。

### 5. Good/Base/Bad Cases
- Good: `available()` 纯配置判定；健康态另经 `configured()/lastCallOk()` 组合成状态码。
- Base: 未调用过 `lastCallOk()=true`（乐观），前端显示 `✓`。
- Bad: `available() = hasKey && lastOk`（惰性闩锁）——一次失败永久禁用。

### 6. Tests Required
- 无 key → `available()=false`；有 key → `available()=true`。
- 调用失败后 `available()` **仍为 true**（回归断言，防闩锁回潮），且 `lastCallOk()=false`。
- `toolHealth` 四态与优先级；KB `DISABLED` 反映 `kb_enabled=f`。

### 7. Wrong vs Correct
#### Wrong
```java
public boolean available() { return apiKey != null && !apiKey.isBlank() && lastOk; }
// 调用方: if (!webTool.available()) continue;  → 失败后再也不调用,lastOk 永无机会复位
```
#### Correct
```java
@Override public boolean available() { return configured(); }
@Override public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
@Override public boolean lastCallOk() { return lastOk; }   // 仅展示,失败自恢复
```

---

## Scenario: 外部搜索策略路由（WEB provider 顺序与证据治理）

### 1. Scope / Trigger
- Trigger: 新增/修改 WEB 搜索 provider 顺序、URL/sourceId 治理、搜索策略配置，或改动 `WebSearchRouter` / `WebResultNormalizer` / `WebProviderOrder`。

### 2. Signatures
```java
// 策略值对象（解析失败明确拒绝,不静默）
enum WebProvider { TAVILY, SEARXNG, SERPER }   // 10-04 A 追加 SERPER 于末尾
record WebProviderOrder(List<WebProvider> providers) {
    static WebProviderOrder parse(String csv);     // 空回退 TAVILY,SEARXNG;未知值抛 IllegalArgumentException
    static WebProviderOrder defaults();
    String raw();                                   // 规范化串(落库/日志/响应)
    String strategyLabel();                         // TAVILY_FIRST / SEARXNG_FIRST / PRIMARY_FANOUT(含 SERPER 时)
}

// 启动时解析一次的快照（同批次共享）
record WebSearchSnapshot(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults)

// 路由（@Component,注入 TavilySearchTool + SearxngSearchTool + SerperSearchTool）
WebSearchOutcome search(String query, int maxResults, WebSearchSnapshot snapshot)
// 10-04 A:vertical 非空时走 searchVertical(news);null 走既有 search(零回归)
WebSearchOutcome searchVertical(String query, int maxResults, WebSearchSnapshot snapshot, String vertical)
record WebSearchOutcome(List<WebHit> hits, WebProvider usedProvider, List<Attempt> attempts)
record Attempt(WebProvider provider, int resultCount, long latencyMs, String fallbackReason, boolean ok)

// 治理（纯静态,可单测）
static List<WebHit> normalize(List<SearchHit> raw, int maxResults)   // 协议校验+规范化+去重+截断+sourceId
static String normalizeUrl(String url)                                // 非法返回 null
record WebHit(String sourceId, String title, String url, String snippet, String provider)
```

### 3. Contracts
- **策略路由在子代理之前**：`WebSearchRouter` 逐个 provider 尝试，首个产出有效命中即停止；未配置跳过（`UNCONFIGURED`），异常/空/全部无效 URL 记 `fallbackReason` 后尝试后备。每 provider 每次最多调用一次——**不让付费 provider 无条件重复调用**（无 BOTH 聚合）。
- **快照一次解析**：`DeepResearchService.run` 启动时解析策略与双开关（`SEARCH_WEB_ENABLED && web_search_enabled`），同批次全部子代理共用；启动后改设置不改变该批次。运行时设置非空优先于部署级 `DEEP_WEB_PROVIDER_ORDER`。
- **新增 provider 必须同时放开「设置面」（10-04 A-R9 踩坑）**：运行时 provider order **永远优先从 DB `sparkora_setting.web_provider_order` 取**（`DeepResearchService.resolveSnapshot`），而 `SettingService.get()` 在行缺失时插入默认 `TAVILY,SEARXNG`（**永远非空**）→ `.env DEEP_WEB_PROVIDER_ORDER` 只在 DB 行为空时才生效。因此**光把 provider 加进 `WebProvider` 枚举并注册进 `WebSearchRouter` 不足以启用它**——还必须同步放开设置面：`SettingUpdateDto` 的 `@Pattern`、`SettingsView.vue` 的选项、以及列宽迁移（`web_provider_order VARCHAR(20)→VARCHAR(50)`，三源串 `TAVILY,SERPER,SEARXNG`=21 字符超原宽）。否则该 provider 在任何受支持路径下都不会被路由到（配置项形同虚设）。
- **sourceId 稳定且可回溯**：`W1,W2…` 按本次输入顺序；URL 规范化后去重（fragment 变体视为同条）。事实只能引用本次输入的 sourceId。
- **后验校验**：WEB 事实的 sourceId 未知 / URL 不匹配 / provider 不匹配 → 剔除并转 gap，**不整条 agent 失败**；KB 事实不受此校验。**凡携带 `url` 或 `sourceId` 的事实一律按 WEB 声明校验**（防模型漏标 `type` 而自造 URL 混入），通过后 `type` 归一为 `WEB`（否则 FactSheet 默认按 KB 0.9 采信）。
- **降级原因不含异常原文/密钥**：异常路径只记类型化 `ERROR`，不回传 `e.getMessage()`（可能含密钥/URL）。
- `webCount` = 实际接受的 WEB 结果数（不再用事实条数 `webCalls`）；`search.resultCount` 与之同口径，**LLM 汇总失败走 `rawFallback` 时不归零**（搜索发生的事实不变），此时 `search.fallbackReason=LLM_FALLBACK`，provider 层 `attempts` 的 `ok`/`fallbackReason` 保持原样，两类失败不混淆。
- `available()` 语义不变（仅配置就绪，无失败闩锁）；`toolHealth` 原有三键值域不变（10-04 A 增量追加 `SERPER` 键），`webStrategy`/`webProviderOrder` 为增量字段。

### 4. Validation & Error Matrix
- provider 未配置 → 跳过，`fallbackReason=UNCONFIGURED`。
- provider 异常/超时 → `ERROR`，尝试后备；异常文本不入 notes/响应/日志。
- provider 返回空 → `EMPTY`；有结果但全部无有效 URL → `INVALID_URL`；两者都降级后备。
- 两路均不可用 → 空 hits，研究笔记记 gaps，不生成无来源事实。
- 策略串含未知值 → `IllegalArgumentException`（配置错误明确暴露）；空串回退默认。

### 5. Good/Base/Bad Cases
- Good: 默认 `TAVILY_FIRST`，Tavily 命中即 `usedProvider=TAVILY` 且 SearxNG 调用 0 次。
- Base: Tavily 未配置 → 跳过，SearxNG 兜底。
- Bad: 在 `SubAgentRunner` 里硬编码 provider 顺序；或把模型自由输出的 URL 当证据写入事实手册。

### 6. Tests Required
- 策略解析（默认/去重/大小写/未知值拒绝）；首源命中不调后备（`verify(never())`）；失败降级；开关门控（不发起请求）；URL 协议校验/规范化/去重/截断；sourceId 后验校验（合法/未知/URL 与 provider 不匹配）；降级原因不含异常文本；`rawFallback` JSON 转义完整。
- **设置面放开新 provider（A-R9）**：`PUT /settings` 接受含新 provider 的顺序组合（如 `SERPER,TAVILY`、`TAVILY,SERPER,SEARXNG`）并落库；非法值仍 400；默认值不变；列宽迁移可容三源串。
- **迁移不可由单测证明（A-R9 教训）**：本仓测试**不启动 Flyway/DB**（无 `src/test/resources`、无 `@SpringBootTest`），`mvn test` 全绿**不能**证明新增 `V<n>__*.sql` 会在真实库干净应用——须另以「对真实 PG 在 `BEGIN…ROLLBACK` 内跑该 DDL」或启动后端观察 `flyway_schema_history` 佐证。新增迁移编号须确认无 `V<n>__*.sql` 占用（跨任务预留也要核对）。

### 7. Wrong vs Correct
#### Wrong
```java
for (SearchTool webTool : List.of(searxngTool, tavilyTool)) {      // 硬编码顺序
    if (!webTool.available()) continue;
    List<SearchHit> web = webTool.search(question, quota);          // query 无锁定答案
    if (!web.isEmpty()) { hits.addAll(web); break; }                // 不去重/无 sourceId
}
```
#### Correct
```java
WebSearchOutcome outcome = webRouter.search(webQuery(topic, question, lockedAnswers),
        Math.min(5, quota), snapshot);                              // 快照策略 + 主题/锁定答案
for (WebHit wh : outcome.hits()) hits.add(wh.toSearchHit());        // 带稳定 sourceId/provider
String factsJson = validateFacts(chat(system, ctx), outcome.hits()); // 后验校验,非法转 gap
```

---

## Scenario: 事实手册近似 claim 归并（09-25-fact-claim-merge）

### 1. Scope / Trigger
- Trigger: 新增/修改事实手册汇总（`FactSheetService.merge`）、claim 相似度判定，或改动 `fact_sheet` 条目的来源聚合/`crossCount`/`confidence` 语义。

### 2. Signatures
```java
// ClaimSimilarity（纯静态、无状态、无依赖、不调 LLM，包级可见）
static String normalize(String raw)                       // 去 Markdown/HTML/标点空白，小写，仅留字母+数字
static List<String> numberValues(String... texts)         // 数值签名：去千分位/万/亿 → BigDecimal 归一，去重稳定排序
static double similarity(String a, String b)              // ((3-gram 重合率) + (最长公共片段/min(len))) / 2
static boolean sameClaim(String c1, String v1,
                         String c2, String v2)            // 硬前提(数值签名相等) + 阈值(0.45/0.70)
static final double TH_NUMERIC = 0.45;                    // 有数值：数值已锁定同一事实
static final double TH_TEXT    = 0.70;                    // 无数值定性：仅措辞级差异

// FactSheetService.merge(String notesJson) → JSON 字符串（签名不变）
// entry 增量字段：sourcesList:[{type,sourceId,provider,url,modelName,docId}]、sourceCount:int
```

### 3. Contracts
- **数值签名是硬前提，短路优先于相似度**：`sameClaim` 对 claim **原文（trim 后）完全相同**的直接判同一事实（严格保留旧精确匹配行为，KB+WEB 同 claim 冲突裁决不回归）；其余一概先比较两侧 `numberValues` 集合，**不相等立即 false**——数值冲突（第2000座 vs 第1500座）与「一侧有数值另一侧没有」都绝不合并。相似度阈值仅在同数值前提下生效。
- **不得用「规范化后相同」短路（踩坑）**：`normalize` 丢弃小数点/千分位，`1.5万`≡`15万`、`2.9米`≡`29米` 规范化成同一串 → 若在数值签名前先按规范化相同短路，会把数值冲突误并（违反 R2）。原样判等必须基于**原文 trim**，近似分支必须落到数值签名硬前提。回归用例：`ClaimSimilarityTest.sameClaim_规范化后相同但小数点致数值冲突_绝不合并`。
- **误合并防护优先于召回**：无数值定性 claim 用高阈值 `0.70`（仅措辞级差异才并）；相似度取「3-gram 重合率 + 最长公共片段占比」的**平均**而非 max——只取 max 会让单条共享长片段主导、把语义相反的 claim 误并。
- **`crossCount`/`sourceCount` = 去重后来源数**（同 `url+modelName` 只计一次），不再是原始 fact 条数；同一 URL 的重复命中**不计**交叉来源（沿用旧 `sameSource` 语义），故不产生 0.85。
- **类型/置信优先级不变**：同簇同时含 KB 与 WEB → KB 胜出（0.9）+ WEB 进 `alternatives` + 警告「以知识库为准」；否则去重来源 ≥2 → `MULTI` 0.85；纯 KB → 0.9；单一 WEB → 0.4 + 「待核实」。合并只改聚类，不绕过冲突裁决路径。
- **`MULTI` 必须落进 `sources.type`**：旧实现仅在局部变量赋值、从未写回 JSON，前端 `sources.type==='MULTI'` 分支永不命中——多源交叉须显式改写序列化来源的 `type`。
- **JSON 主结构向后兼容**：`entries[{key,claim,value,sources,crossCount,confidence}]` 不变；`sourcesList`/`sourceCount` 为增量字段，旧前端不读也不报错。
- **归并不得破坏数值回查**：代表 fact 的 `key/value/claim` 覆盖全部被并 claim 的数值（数值签名相等保证）；`DeepWriterService.verifyNumbers` **自 C7 起以同一套数值签名**比对手册（见本文「正文数值回查归一化」Scenario），归并后仍命中。

### 4. Validation & Error Matrix
- 数值解析失败 → 回退原 token 字符串比较，绝不抛异常（`normalizeNumber` 内 try/catch）。
- 规范化后过短（<4 字）→ 相似度 0，不合并。
- claim 为空/空白 → 不进入聚类（既有 skip 行为）。
- 一侧有数值、一侧无数值 → 数值签名不等 → 不合并。
- 异 URL 同义 → 合并 + `MULTI` 0.85；同 URL 同义 → 合并但 `crossCount=1`、保持 0.4。

### 5. Good/Base/Bad Cases
- Good: 真实样本「比亚迪第2000座…落成」(stnn.cc) 与「…第 2000 座…落成 - IT之家」(ithome) 合并为 `MULTI` 0.85、`crossCount=2`。
- Base: 单 KB / 单 WEB 行为不变；KB+WEB 同义仍 KB 胜出。
- Bad: 只按字符串精确匹配分组（同义被拆成三条单 WEB 全「待核实」）；或放宽阈值把「续航 700km」与「起售价 200000」并成一条。

### 6. Tests Required
- `ClaimSimilarityTest`：normalize；数值签名（千分位/万/亿等价、去重排序、无数字空集、解析异常不抛出）；相似度过短为 0；`sameClaim` 数值冲突/一侧无数值/空 claim 不合并、同值近义合并、定性高阈值。
- `FactSheetServiceTest`（AC-01..AC-09）：同 URL 近义合并为一条；异 URL 近义 → `MULTI` 0.85 + `crossCount`/`sourceCount`=2 + `sourcesList` 两条；数值冲突保持两条；语义不同共享文字不合并；同 URL 不产生交叉；KB+WEB 仍 KB 胜出、WEB 进 alternatives、不标待核实、`sourcesList` 保 URL；归并后 `verifyNumbers` 不回归；**既有 6 用例向后兼容**。

### 7. Wrong vs Correct
#### Wrong
```java
// 只按 claim 精确字符串分组：措辞不同 → 同义拆成多条单一 WEB 源(0.4+待核实)，交叉验证失效
Map<String, List<JsonNode>> byClaim = new LinkedHashMap<>();
byClaim.computeIfAbsent(claim, k -> new ArrayList<>()).add(f);
```
#### Correct
```java
// 贪心簇 + 数值签名硬前提：同义归并 → 去重来源 ≥2 得 MULTI 0.85；数值冲突/一侧无数值不合并
if (ClaimSimilarity.sameClaim(rep.path("claim").asText(""), rep.path("value").asText(""),
        f.path("claim").asText(""), f.path("value").asText(""))) target = cluster;
```

---

## Scenario: 降级必须保真原始证据（snippet；09-26-deep-research-coverage）

### 1. Scope / Trigger
- Trigger: 新增/修改子代理 LLM 汇总降级（`SubAgentRunner.rawFallback`）、研究笔记事实结构、或事实手册条目透传字段（`FactSheetService` entry）。

### 2. Signatures
```java
// SubAgentRunner（降级 JSON 增量字段，不改签名）
static String rawFallback(List<SearchTool.SearchHit> hits)
// 每条 fact：{"claim":<title>,"snippet":<≤200 转义原文>,"source":{...},"confidence":0.4|0.6}
static String snippet(String s)          // 复用：null→""，>200 截断

// FactSheetService
private static Map<String,Object> entry(..., List<String> alternatives, String snippet)  // snippet 增量参数
private static String firstSnippet(List<JsonNode> facts)   // 簇内首个非空 snippet，均无→null

// SubAgentRunner.chat（R4：失败先提额重试一次）
private String chat(String system, String user)
```

### 3. Contracts
- **降级不得丢失搜索命中正文**：LLM 汇总失败走 `rawFallback` 时，每条 fact 必须同时带 `claim`(title) 与 `snippet`(检索正文，≤200 字)——关键背景/长期目标常写在 snippet 而非标题；只取 title 会让「素材缺口」在降级路径人为放大。`snippet` 转义完整（引号/换行/小数/反斜杠），降级 JSON 仍合法。
- **snippet 只保真、不替代抽取**：降级仍 `status=FALLBACK` + gap；snippet 是「素材可用」而非「已核验事实」。
- **手册条目透传为可选增量字段**：`FactSheetService` 取簇内**首个非空** snippet 写入 `entry.snippet`；无则字段**完全不出现**（旧契约与既有消费方零回归，对齐 `sourcesList` 增量范式）。`DeepWriterService` 与 `BriefService` prompt 可见该证据（写作/简报阶段提取背景素材）。
- **汇总失败先提额重试（R4）**：`chat` 首次 `chatJson(...,2048)`；任何失败（`finish_reason=length` 截断 / 空内容 / 非法 JSON）提额 `4096` 重试一次，仅仍失败才抛出 → FALLBACK。净调用 ≤2 次/agent。
- **不改口径/不改红线**：`webCount`/`search.resultCount` 降级不归零、`search.fallbackReason=LLM_FALLBACK`、`available()` 无闩锁、`toolHealth` 优先级、`research_notes` 主字段集与状态值域均不变（`toolHealth` 10-04 A 起增量含 `SERPER`，原有键不变）。

### 4. Validation & Error Matrix
- snippet 为空/null → 写空串（字段仍在，不破坏结构）；>200 字 → 截断。
- 簇内首条无 snippet、后续有 → 取首个非空，不因首条为空而丢证据。
- 无任何 snippet → entry 不出现 `snippet` 字段（非 `null` 序列化）。
- 首次 LLM 失败 → 提额 4096 重试；两次均失败 → `FALLBACK`（日志告警，不阻断其余 agent）。

### 5. Good/Base/Bad Cases
- Good: 真实样本「比亚迪计划2026年底前建成2万座闪充站,其中包含这2000座高速站」写在 snippet → 降级后仍进入 facts/manual，写作阶段可见。
- Base: 有 title 无 snippet 的旧命中 → snippet 空串，行为与旧实现一致。
- Bad: 降级 claim 只取 `h.title()`，丢弃 `h.snippet()`（关键背景永久丢失）；或把 snippet 当已核验事实提升置信。

### 6. Tests Required
- `SubAgentRunnerTest`：`rawFallback` 保留 snippet 正文；含引号/换行/小数/反斜杠的 snippet 产出合法 JSON；`chat` 首次截断（AiException）→ 第二次成功 → DONE；两次均失败 → FALLBACK（并断言第二次用 4096）。
- `FactSheetServiceTest`：降级 fact 带 snippet → entry 透传；无 snippet → entry 不含该字段。

### 7. Wrong vs Correct
#### Wrong
```java
// 降级只取标题：关键背景写在 snippet 里 → 永远进不了手册/正文
raw.append("{\"claim\":\"").append(esc(h.title())).append("\",\"source\":{...");
```
#### Correct
```java
// 降级同时保留正文证据（≤200 转义），手册透传、写作可见
raw.append("{\"claim\":\"").append(esc(h.title()))
   .append("\",\"snippet\":\"").append(esc(snippet(h.snippet())))
   .append("\",\"source\":{...");
```

---

## Scenario: WEB 正文补抓 + 按问题类型分档注入（09-27-tavily-extract-kind-hypotheses R1/R3，机制 B）

### 1. Scope / Trigger
- Trigger: 新增/修改背景题 WEB 正文补抓（`SearchTool.extract` / `TavilySearchTool` / `SubAgentRunner.enrichContent`），或改动命中正文载体（`SearchHit.content`/`WebHit.content`）与注入分档。

### 2. Signatures
```java
// SearchTool（default 方法,不支持的工具零成本跳过）
default List<SearchHit> extract(List<String> urls, String query) { return List.of(); }

// SearchHit 10 参主构造器（content nullable）+ 9 参/7 参兼容构造器
record SearchHit(String type, String title, String url, String snippet,
                 String modelName, Long docId, double score,
                 String sourceId, String provider, String content) { … 
    static SearchHit web(String toolName, String title, String url, String snippet, String content);
    static SearchHit webContent(String toolName, String url, String content);   // 抽取结果条目
}
// WebResultNormalizer.WebHit 6 参（content）+ 5 参兼容构造器；toSearchHit() 透传 content
// WebSearchRouter.extract(query, urls)  // 按 provider 顺序尝试支持 extract 的工具,首个非空即采信

// DeepProperties
int effectiveWebContentMaxChars();   // 默认 2000（<env> DEEP_WEB_CONTENT_MAX_CHARS）
```

### 3. Contracts
- **机制 B**：`search` 保持 `search_depth=basic` 拿摘要；**仅背景题**（`ClarifyService.isBackgroundQuestion`）且 WEB 命中后，对 **top 1–2 URL** 调 `POST /extract`（`query` + `chunks_per_source:3` + `extract_depth:"basic"`）取 `raw_content`，**工具层截断**（唯一上限 `effectiveWebContentMaxChars`，避免「工具截一次、注入再截一次」）。
- **provider 无关**：`WebSearchRouter.extract` 按 provider 顺序委托支持 extract 的工具，不在子代理硬编码 `TavilySearchTool` 依赖；Tavily 不可用 → 不补正文。
- **降级绝不抛**：失败/空（`failed_results`/空 `results`）/异常 → 命中保持 `content=null`，降级回摘要；`extract` **不修改 `lastCallOk()`**（不污染健康态/门控）。
- **载体分离**：`snippet`=摘要（引用/预览语义不动）；`content`=正文片段（仅研究注入与降级留证）。两者均**保留旧构造器**（record 加字段先例见 `Citation.docId`）。
- **注入分档**：背景题 ctx 追加 `正文片段:` 行；**参数题只用 `snippet`**（且从不触发 extract）。
- **降级留证**：`SubAgentRunner.rawFallback` 在 `content` 非空时增 `"content":esc(...)`（转义完整），空则字段不出现（旧契约零回归）。

### 4. Validation & Error Matrix
- Tavily 未配置 / urls 空 → 直接空列表（不发起请求）。
- `failed_results` / 空 `results` / 空 `raw_content` / HTTP 非 2xx / 超时 → 空列表（降级回摘要）。
- 超上限 → 工具层截断到 `effectiveWebContentMaxChars`。
- 抽取返回 URL 与命中 URL 有 fragment/大小写差异 → `normalizeUrl` 对齐回填。
- WEB 开关关闭（`webAllowed=false`）→ 既不 search 也不 extract。

### 5. Good/Base/Bad Cases
- Good: 背景题命中 2 条 → 对 2 个 URL extract，正文截断 2000 注入 ctx。
- Base: extract 返回空 → 降级回摘要，`status=DONE` 不受影响。
- Bad: 对参数题也 extract（浪费 credits）、或在子代理里 `new TavilySearchTool()` 硬编码、或让抽取失败冒泡为 `FAILED`。

### 6. Tests Required
- `TavilySearchToolExtractTest`：正文非空且 ≤ 上限；请求参数（query/chunks_per_source/extract_depth）；`failed_results`/空 raw/HTTP 500 → 空列表；未配置/空 urls 不发起请求。
- `SubAgentRunnerTest`：背景题 ctx 含正文、参数题不含；背景题触发 extract 一次、参数题 `never()`；WEB 关闭不 search/extract；extract 空 → 降级不抛；`rawFallback` content 转义可解析 / 空则无字段。
- `WebResultNormalizerTest`：content 经 `WebHit` 透传到 `SearchHit`；旧构造器 content=null。

### 7. Wrong vs Correct
#### Wrong
```java
// 在子代理里硬编码 provider + 失败冒泡
TavilySearchTool t = new TavilySearchTool(props, json);
List<SearchHit> body = t.extract(urls, q);   // 抛异常 → 整条 agent FAILED
```
#### Correct
```java
// 工具抽象 + 仅背景题 + 失败降级回摘要
if (background && !webHits.isEmpty()) webHits = enrichContent(question, webHits);  // 内部 catch,空则原样
```

---

## Scenario: 事实手册条目 `kind` 分类 + 写作/简报按类消费（09-27-tavily-extract-kind-hypotheses R4/R5/R6）

### 1. Scope / Trigger
- Trigger: 新增/修改 `fact_sheet.entries[].kind`、`FactSheetService.merge` 的 kind 归属、写作/简报 prompt 的按类分组，或简报注入 `research_plan.hypotheses`。

### 2. Signatures
```java
// FactSheetService（签名不变）
public String merge(String notesJson)
private static Map<String,Object> entry(..., String snippet, String kind)   // kind 增量参数
// entry 增量字段：kind ∈ {param, background}（显式写,缺省兜底 param）

// DeepWriterService
private static StringBuilder buildFactContext(JsonNode entries)  // 有 kind → 两段分组;全无 → 旧平铺
// BriefService
private String hypothesesBlock(String researchPlan)              // null = 跳过(不注入)
```

### 3. Contracts
- **kind 继承产出该 fact 的「研究问题类型」**：`ClarifyService.isBackgroundQuestion(note.question)` → `background`，其余/无问题信号/历史数据 → `param`。`merge` 展开 facts 时并行记录每条 fact 的 kind，聚类后取**簇首条（代表 fact）**的 kind。
- **`param`/`background` 都显式写**（增量字段，旧前端不读不报错，对齐 `sourcesList`/`snippet` 范式）；消费侧对缺 kind 兜底 `param`。
- **写作按 kind 分组**：有任一条带 kind → 分「【参数事实】(可逐字引用数值)」与「【背景素材】(仅用于叙事,不得据此新增数值)」两段；**全无 kind（历史 fact_sheet）时退化为原平铺行为**（prompt 逐字与旧实现等价，AC-05）。
- **简报注入假设**：user prompt 追加 `research_plan.hypotheses`（数组逐项）；system prompt 要求 `coreViewpoints` 回应假设被证实/推翻。`research_plan` 缺失/无 hypotheses/空数组/畸形 JSON → 跳过（兼容退化,不报错）。
- **不改红线**：归并/数值回查/冲突裁决路径不变；`verifyNumbers` 以 `sheet.toString()` 为 haystack 仍覆盖归并条目；无 schema 变更。

### 4. Validation & Error Matrix
- 无问题信号（历史 notes 无 question）→ `kind=param`，不抛。
- 归并后 kind = 簇首条问题类型（不是各条混合）。
- 全无 kind → 旧平铺，不出现分组块头。
- `research_plan` 畸形 JSON → 跳过假设块且不抛（仅 warn）。

### 5. Good/Base/Bad Cases
- Good: 背景题产出的 2 条近义 claim 合并 → `kind=background`、写作进背景素材段。
- Base: 参数题 → `kind=param`，写作进参数事实段。
- Bad: 归并后 kind 取最后一条（覆盖簇首语义）、或缺 kind 时写作直接报错。

### 6. Tests Required
- `FactSheetServiceTest`：背景题→background、参数题→param、无问题信号→param 不抛、归并取簇首 kind。
- `DeepWriterServicePromptTest`：有 kind → 两段出现且归属正确；全无 kind → 无分组块头且平铺格式逐字保留；混合 kind → 缺 kind 兜底参数组。
- `BriefServiceTest`：假设注入 user prompt；null/无 hypotheses/空数组/畸形 → 不注入且不抛。

### 7. Wrong vs Correct
#### Wrong
```java
// 只写 background,param 省略 → 写作无法识别「参数组」,分组退化
if ("background".equals(kind)) e.put("kind", kind);
```
#### Correct
```java
// param/background 都显式写;消费侧缺 kind 兜底 param
e.put("kind", kind == null || kind.isBlank() ? "param" : kind);
```

---

## Scenario: 长手册深度简报输出提额 + 失败重试一次（09-26-deep-research-coverage R6）

### 1. Scope / Trigger
- Trigger: 新增/修改深度简报生成（`BriefService.generateFromFactSheet`）的 `max_tokens` 额度、失败重试语义，或事实手册体积显著变化。

### 2. Signatures
```java
// BriefService（签名不变；内部额度/重试变更）
public ArticleBriefEntity generateFromFactSheet(Long projectId, Long briefId)
// 首次 aiClient.chatJson(buildDeepBriefSystemPrompt(), buildDeepBriefUserPrompt(p,b), 8192)
// 失败时 aiClient.chatJson(同上 + 纠错说明, 16384) 一次
```

### 3. Contracts
- **额度随手册体积扩容**：R5 放大事实手册后（实测 17 条 / 10294 字）旧 `2048` 会被 `finish_reason=length` 截断 → 项目回 DRAFT。深度简报首次调用固定 `8192`。
- **任何失败翻倍重试一次**：截断（`AiException`）/ 空内容 / 非法 JSON / `readValue` 失败均触发 `16384` 重试一次（附纠错说明提示只输出完整合法 JSON），仅两次均失败才落 DRAFT + `lastBriefError`（截断 1000）。净调用上限 2 次。
- **重试独立实现**：`BriefService` 内联实现，**不抽公共 helper、不与已删除的 FAST 路径共用**（FAST `generate` 已随模式收敛删除）。
- **不改红线**：`/deep/*` 响应主结构、`research_notes` 字段集、`SearchTool.available()`/`toolHealth` 均不变；无 schema 变更。
- **失败回退语义不变**：失败 `p.status=DRAFT` + `p.lastBriefError`，brief 行不写简报字段（前端保留深度面板可重试）；成功 `status=READY` + `currentBriefId` 指向该行 + 清空旧错误。

### 4. Validation & Error Matrix
- 首次成功 → 仅一次调用（8192），不触发 16384。
- 首次截断/空/非法 JSON → 16384 重试；重试成功 → 字段落库 + READY。
- 两次均失败 → 抛 `AiException`、项目回 DRAFT + `lastBriefError`、brief 行不动。
- `fact_sheet` 空/非 DEEP brief → 前置 `IllegalArgumentException`/`IllegalStateException`，不进入 AI 调用。

### 5. Good/Base/Bad Cases
- Good: 10294 字手册在 8192 内产出完整简报（字段齐全、项目 READY）。
- Base: 首次截断 → 16384 重试成功（净 2 次调用）。
- Bad: 沿用 `2048`（长手册必截断）；或失败不重试直接落 DRAFT。

### 6. Tests Required
- `BriefServiceTest`（Mock AiClient/mapper，无 DB）：① 首次截断 → 断言第二次 16384 且字段落库 + READY + `currentBriefId`；② 两次均失败 → DRAFT + `lastBriefError` 且第二次 16384；③ 首次成功 → 断言只用 8192、无 16384 调用。

### 7. Wrong vs Correct
#### Wrong
```java
// 长手册固定 2048：R5 放大手册后必然 finish_reason=length → 项目回 DRAFT
aiClient.chatJson(buildDeepBriefSystemPrompt(), buildDeepBriefUserPrompt(p, b), 2048);
```
#### Correct
```java
try {
    cr = aiClient.chatJson(system, user, 8192);          // 首次提额
    dto = json.readValue(AiClient.sanitizeAiJson(cr.content()), BriefDto.class);
} catch (Exception first) {
    cr = aiClient.chatJson(system, user + 纠错说明, 16384); // 失败翻倍重试一次
    dto = json.readValue(AiClient.sanitizeAiJson(cr.content()), BriefDto.class);
}
```

---

## Scenario: 图片向量域（第四域，09-15 img-semantic-search）

### 1. Scope / Trigger
- Trigger: 新增/修改图片语义检索（`ImageEmbeddingService`）、图片向量表，或改动图片嵌入文本规则。

### 2. Signatures
```java
// ImageEmbeddingTextBuilder（纯静态，可单测，零 AI）
static String build(ImageAssetEntity img, List<String> tags, String newsTitle)  // 2000 字符截断，全空兜底 (图片 <id>)
// ImageEmbeddingService
record EmbedStats(int total, int success, int failed)          // 09-27 起为 com.sparkora.ai.EmbedStats 共享定义
void embedOne(ImageAssetEntity img)                            // 先物理删旧向量再插（幂等）
void embedQuietly(Long imageId)                                // 吞全部异常仅 warn，绝不影响图片入库
EmbedStats rebuildAll() / rebuildMissing()                     // 全量 / 仅补 LEFT JOIN 差集
void deleteByImageId(Long imageId)                             // 删图联动
List<ImageSearchHit> searchImages(String query, Integer topK, Double minScore, List<String> tags)
```

### 3. Contracts
- **同空间硬约束**：`sparkora_image_embedding.embedding VECTOR(1024)` + HNSW `vector_cosine_ops`，复用 `EmbeddingClient`（Qwen3-Embedding-8B）。**不新增 embedding 客户端**。~~不存模型名/维度列~~（**09-27 P1-⑧ 推翻**：加 `embedding_model` 列并检索按当前模型过滤，见本文「知识域写入侧统一 + 向量模型防护」Scenario 与 database-guidelines.md——原「列存模型名只会制造错觉」的判断已被「同维换模型静默混空间」风险证伪）。
- **一图一向量**：`UNIQUE(image_id)` 即幂等保证（重建先清后插）；不加 `deleted`（物理表）、不建 FK（应用层维护，删图同事务清向量，同 `sparkora_image_tag` 惯例）。
- **图片无自身文本 → 描述性文本代理**：byd-news=来源新闻标题（`source_ref` 反查）+ 标签；ai-*=promptText+标签；upload/byd=文件名去扩展名+标签。标签**原样拼**（保留 `主题/` 前缀，「销量」即检索信号）。
- **全空仍嵌入**（兜底 `(图片 <id>)`）：缺向量图在语义检索中永久不可见，比低质向量更糟；低质命中交给门槛过滤。
- **标签预过滤 = AND 交集**（与 `GET /api/images` 同语义，复用 `resolveTagIds`），交集为空**早返回且不调 embedding**；候选集 ≤500 截断保底。
- **门槛下推到 SQL WHERE**（`1 - (embedding <=> vec) >= minScore`），不传输注定被丢弃的行；`topK` 默认 10 / 上限 50 收敛不报错；`minScore` null → `AI_IMAGE_MIN_SCORE`（默认 0.3）。
- **注解 SQL 的 XML 转义（踩坑）**：`@Select("<script>…")` 内容是 XML，pgvector 距离运算符 `<=>` 与比较符 `>=`、`<if test='ids.size() > 0'>` 的 `>` **必须写成 `&lt;=&gt;` / `&gt;=` / `&gt;`**，否则 SAXParser 报「元素内容必须由格式正确的字符数据或标记组成」→ **启动期 mapper 注册失败**（编译期不报，只有跑起来才炸）。
- **向量写入必须与调用方事务隔离（`REQUIRES_NEW`）**：入库链路（新闻/车型同步）可能是事务性的，向量 SQL 若在其中失败（维度不符 / 唯一索引并发冲突），PostgreSQL 会把**整个调用方事务**置为 aborted，此后调用方任何 SQL 都抛 `current transaction is aborted`——Java 侧 `catch` 无法挽回，「嵌入失败不阻断入库」契约即失效（父表 INSERT 也随事务回滚）。故 `persistVector` 经**自注入代理**（`@Autowired @Lazy`，`this.` 调用不走代理）走 `@Transactional(propagation = REQUIRES_NEW)`；embedding 网络调用放在事务之外。独立事务同时让「先删后插」原子化（失败回滚保留旧向量，不留空洞）。
- **启动补齐 runner 的 `@Order` 硬约束**：补标签 runner（`@Order(10)`）必须早于补向量 runner（`@Order(20)`）——嵌入文本依赖标签信号，顺序反了会让存量图拿到「无标签」低质向量，且因「已有向量」`rebuildMissing()` 不再修（静默、需人工全量重建）。同类「B 依赖 A 产物」的启动补齐任务都要显式排序。
- **启动期网络任务异步化**：逐图/逐块 embedding 是串行网络调用（图库 ~170 图实测约 173s），不能占启动主线程（`ApplicationRunner.run` 返回前应用未就绪）——放独立守护线程，日志输出进度与耗时；阈值参考：超过 ~30s 即应异步。

### 4. Validation & Error Matrix
- `query` 空/空白 → `IllegalArgumentException("检索内容不能为空")` → 400（DTO `@NotBlank` 同文案）。
- 标签交集空 → 200 `data:[]`（不调 embedding）；`AI_EMBEDDING_MODEL` 空 / 调用失败 → 500。
- 增量嵌入失败 / 唯一索引并发冲突 → 仅 warn（不阻断入库），事后用 rebuild 接口补齐。

### 5. Good/Base/Bad Cases
- Good: 入库钩子挂在 `persistOrReuse` **insert 成功之后**（标签/来源已落库，嵌入文本才拿得到完整信号），`embedQuietly` 自吞异常。
- Base: 启动 runner `rebuildMissing()` 只补差集，异常仅 warn 不阻断启动（重跑 `total=0` 跳过）。
- Bad: 把 embedding 调用放在入库事务内并让失败回滚图片入库；或在事务外先读后判「是否有向量」。

### 6. Tests Required
- 四来源嵌入文本分派 + 标题缺失退化 + 全空兜底 + 2000 截断 + null 安全（`ImageEmbeddingTextBuilderTest`）。
- query 空校验、标签交集空早返回（断言 `embed` **未被调用**）、topK 收敛、minScore 默认与门槛透传、命中回填 url/thumbUrl/tags、向量残留但图已删跳过、`embedQuietly` 吞异常、rebuild 计数（`ImageEmbeddingServiceTest`）。
- **事务隔离回归**：断言 `embedOne` 经自注入代理（`persistVector` 被调用）而非在当前 SqlSession 直写（`ImageEmbeddingServiceTest`，反射注入 `self`）。

### 7. Wrong vs Correct
#### Wrong
```java
public void embedQuietly(Long imageId) { embeddingClient.embed(text); }  // 抛出去 → 图片入库失败
```
#### Correct
```java
public void embedQuietly(Long imageId) {
    try { ImageAssetEntity img = imageMapper.selectById(imageId); if (img != null) embedOne(img); }
    catch (Exception e) { log.warn("图片向量化失败(不影响入库,可用重建接口补齐) id={}: {}", imageId, e.getMessage()); }
}
```

---

## Scenario: 配图建议（派生建议，零副作用；09-15 article-auto-illustrate 子C）

### 1. Scope / Trigger
- Trigger: 新增/修改「基于正文自动产出候选」类能力（文章配图建议、问答配图建议…），或改动 `IllustrationSuggestionService` / `AnchorExtractor`。

### 2. Signatures
```java
// AnchorExtractor（纯静态，无 Spring/AI 依赖，可单测）
record Anchor(int anchorIndex, String headingPath, String text, String key)
static List<Anchor> extract(String contentMd, int maxAnchors)      // 上限保序截断
static String fingerprint(String headingPath, String text)         // 归一化文本前 80 字符 sha256 前 12 位

// IllustrationSuggestionService（只读；刻意不注入 ImageService）
record AnchorSuggestion(String anchorKey, int anchorIndex, String headingPath,
                        String anchorText, List<ImageSearchHit> candidates)
List<AnchorSuggestion> suggest(Long projectId, List<String> tags, Double minScore)
void dismiss(Long projectId, String anchorKey, String operator)
```

### 3. Contracts
- **派生建议零副作用**：`suggest` 只读正文 + 图库（`searchImages`），**不写 `content_md` / `sparkora_article_version_image`**；服务**不得注入 `ImageService`**（否则 `modifyBodyImage`/`setCover` 写路径可达）。生成建议前后 DB 的 `content_md` 与版本插图关联行必须完全一致（硬验证项）。
- **无自动插入能力**：不存在自动写入开关/配置；写入仅由用户显式「采用」触发（前端 markdown 插入 + `POST /images/{id}/body`）。
- **建议不落库**：建议是「当前正文 + 当前图库」的派生视图（可重算、结果稳定）；只有用户的「忽略」决策持久化。落库会引入「建议陈旧」。
- **锚点用指纹而非序号**：正文编辑后序号漂移会让「忽略」记录错位；指纹（标题路径 + 归一化文本 sha256 前 12 位）未编辑时稳定，纯格式调整（加粗/换行）不改指纹。
- **单锚点失败降级**：任一锚点检索失败仅 warn 跳过，其余锚点照常返回（不整体 500）；门槛以下无候选 → 该锚点不出现（不报错）。
- **`dismiss` 幂等**：先查 + `UNIQUE(version_id, anchor_key)` 兜底，捕 `DuplicateKeyException` 静默吞（无外层事务，不会污染调用方）。

### 4. Validation & Error Matrix
- 项目不存在 → 400「项目不存在」；无当前版本 → 400「尚未生成正文版本，无法生成配图建议」；正文空 → 400「正文为空，无法生成配图建议」。
- `minScore` <0 或 >1 → 400；`anchorKey` 空/超 200 字符 → 400。
- VIEWER：suggest 200（读）；dismiss 403。

### 5. Good/Base/Bad Cases
- Good: 建议生成走只读依赖（project/version/embedding/dismiss mapper 的读方法），前端「采用」才双写。
- Base: 图库规模小（~170 张）时多数锚点无候选 → 空态给可操作指引（调低门槛/换标签/补图）。
- Bad: 因候选分数高就自动插入；或在建议服务里注入 `ImageService` 顺手写版本插图关联行。

### 6. Tests Required
- `AnchorExtractorTest`：分段/前言/H1 不计入、纯列表/引用/代码块/图片/过短跳过、上限截断、markdown 清洗、指纹稳定性与超长窗口、null/空/非法上限边界。
- `IllustrationSuggestionServiceTest`：前置校验 4 例、按锚点组装与 topN/门槛透传、无候选不出现、单锚点失败跳过、已忽略过滤、`dismiss` 幂等与唯一冲突、**零副作用断言**（写方法从未被调用 + 反射断言未注入 `ImageService`）。

### 7. Wrong vs Correct
#### Wrong
```java
// suggest 里顺手写版本插图关联行：违反「建议零副作用」硬约束
if (hit.score() > 0.9) imageService.modifyBodyImage(projectId, hit.imageId(), "add");
```
#### Correct
```java
// 只返回建议；写入由前端在用户点「采用」后触发（markdown + addBodyImage 双写）
out.add(new AnchorSuggestion(a.key(), idx, a.headingPath(), a.text(), hits));
```

---

## Scenario: 问答答案配图（只读派生展示；09-15 qa-auto-illustrate 子D）

### 1. Scope / Trigger
- Trigger: 新增/修改「随答案附带的展示性派生内容」（问答配图、答案附注…），或改动 `QaImageRefService` / `QaImageIntent` / `Citation.docId`。

### 2. Signatures
```java
// CarRagService（纯增量，向后兼容）
record UnifiedHit(String chunkText, String chunkType, double score,
                  String source, Long modelId, String modelName, Long docId) {}
record Citation(String source, String modelName, String chunkType, double score,
                String chunkText, Long docId) {
    Citation(String source, ..., String chunkText) { this(..., null); }   // 5 参兼容构造器
}

// QaImageIntent（纯静态，可单测）
static boolean isImageIntent(String question)    // 关键词命中
static String cleanQuery(String question)        // 剥离意图短语；剥空回退原问题

// QaImageRefService（只读；**所有路径内部 try/catch，永不抛出**）
record QaImageRef(Long imageId, String url, String thumbUrl, String title, String newsId, String source)
List<QaImageRef> forAnswer(List<Citation> citations, String question, int limit)   // 两路合并去重
List<QaImageRef> byNewsCitations(List<Citation> citations, int limit)              // 便宜路径
List<QaImageRef> bySemanticQuery(String question, int limit)                       // 语义路径

// ImageService（只读批量：selectBatchIds + 复用 fillDerived）
List<ImageAssetEntity> loadDerived(List<Long> imageIds)
```

### 3. Contracts
- **record 加字段必须保留旧参构造器**：`Citation` 加可空 `docId` 时保留 5 参构造器（委托 6 参传 null），否则既有调用方（`BriefService.citationsJson`/`KnowledgeSearchTool`/`QaServiceTest`）编译失败。纯增量字段对 JSON 消费者无害（前端不读即无影响）。
- **检索结果已携带的字段要在读行处补读**：统一检索早已携带 `docId`（10-03 E6 后为 store `metadata.refId`），但 `retrieveUnified` 读行时丢弃 → 消费方拿不到定位 id。加字段是「读侧一行」的事，**不动检索与配额、不动排序**（三域候选窗口隔离是关键资产）。
- **重排/重建 record 处必须透传全部字段**：锚点 boost 分支 `new UnifiedHit(...)` 少传一个字段就静默丢数据（本任务最高风险点）。加字段时 grep 所有 `new XxxRecord(` 构造点逐一核对。
- **展示性派生内容 = 只读 + 降级**：配图不写任何用户内容、无批准流程（与子C「写入正文须批准」不同，因为子C 改的是用户内容）。解析失败 → 落 null + warn，**主产物（答案）优先**；`QaService.ask` 调用处再包一层 try/catch 双保险。
- **派生展示不落库（除本次的答案配图例外说明）**：本任务的 `image_refs` 是**消息的组成部分**（与答案同时产生、随消息生命周期），故落列；而「可随时重算的建议」（子C 配图建议）仍不落库（避免建议陈旧）。判定：内容是否为「该次生成的结果快照」——是则落，否则按需重算。
- **意图判定用关键词而非 LLM**：零成本、可单测、可解释；误判代价仅是「多显示几张图」时不值得引入 LLM。**非命中不得触发 embedding**（性能硬约束，用 `never()` 断言）。
- **预览/引用必须用原图 URL**：配图 `preview-src-list` 用 `url` 而非 `thumbUrl`（webp 派生，既有教训）；缩略展示才用 `thumbUrl || url`。

### 4. Validation & Error Matrix
| 条件 | 行为 |
|---|---|
| 无 NEWS 引用 / `docId` 为空 | 新闻关联图为空（不查库、不报错） |
| NEWS 引用无 `cover_image_id` | 跳过该条 |
| 图库资产已删/无 `url` | 跳过该条（`loadDerived` 后按 `url` 过滤） |
| 语义检索失败 / 非图片意图 | 该路径空（后者不调 `searchImages`）；答案正常 |
| 配图整体异常 | `image_refs=null` + warn；答案照常落库 |
| 历史消息 `image_refs` NULL | 前端不展示图片区（零回归） |

### 5. Good/Base/Bad Cases
- Good: `forAnswer` 两路各自 try/catch + 外层再兜一层；合并按 `imageId` 去重且新闻图优先（与引用强相关）。
- Base: 无 NEWS 引用且非图片意图 → 空列表 → 落 null（前端不渲染）。
- Bad: 意图判定接 LLM（每条问答多一次调用）；或让配图异常冒泡打断 `ask`（答案丢失）。

### 6. Tests Required
- record 兼容构造器可用（5 参 → docId=null）；`retrieveUnified` 读入 docId 且缺列不 NPE。
- **boost 重排后 docId 保留**（回归断言，防静默失效）。
- 意图：正例/负例/null/空；**非图片意图 `verify(searchImages, never())`**。
- 合并去重（同 imageId 两路命中 → 1 条，新闻图优先）、上限截断、新闻占满上限不再调语义检索。
- 降级：任一依赖抛异常 → 返回空列表**不抛出**（`answer` 仍落库、`imageRefs` 为 null）。
- 前端字段名与 record 一致（`imageId` 而非实体 `id`）——编译/单测发现不了，需真机或 curl 打通 API 层。

### 7. Wrong vs Correct
#### Wrong
```java
// boost 分支重建 record 时漏传 docId → NEWS 配图静默失效（且无任何报错）
boosted.add(new UnifiedHit(h.chunkText(), h.chunkType(), h.score() * boost,
        h.source(), h.modelId(), h.modelName()));
```
#### Correct
```java
boosted.add(new UnifiedHit(h.chunkText(), h.chunkType(), h.score() * boost,
        h.source(), h.modelId(), h.modelName(), h.docId()));   // 全字段透传
```

---

## Scenario: 知识域写入侧统一 + 向量模型防护（09-27 P1-⑧）

### 1. Scope / Trigger
- Trigger: 新增/修改 CAR/KB/NEWS/IMAGE 任一域的切块、并发嵌入、向量持久化、向量模型配置，或改动检索的模型过滤。

### 2. Signatures
```java
// com.sparkora.ai（新共享层）
TextChunker.chunk(String header, String content, boolean titlePresent, boolean keepTitleWhenEmpty, String sentenceSeparators)
TextChunker.chunk(..., String sentenceSeparators, int overlapChars)   // E2 6 参重载；旧 5 参委托 overlapChars=0（逐块等价旧行为）
TextChunker.MAX_BODY_LEN = 500;  TextChunker.DEFAULT_OVERLAP_CHARS = 60
TextChunker.KB_SEPARATORS = "。；!?";  TextChunker.NEWS_SEPARATORS = "。；;！!？?"
TextChunker.splitSentences(p, separators)                        // 全库唯一定义；句读集合按域参数化
TextChunker.overlapTail(prev, separators, overlapChars)          // 包级；取前块尾部 ≤overlapChars 片段（不整块重复）
record EmbedStats(int total, int success, int failed)            // 全库唯一定义
EmbeddingBatchRunner.run(List<T> items, Function<T,String> textFn, BiConsumer<T,String> persistFn,
                         String label, int maxParallel, int maxRetries) → EmbedStats
// EmbeddingClient（C5：后端 = Spring AI EmbeddingModel，公共 API 不变）
@Autowired EmbeddingClient(AiProperties, org.springframework.ai.embedding.EmbeddingModel)  // 主构造：注入自动配置 EmbeddingModel
EmbeddingClient(AiProperties)         // 兼容构造（单测；embeddingModel=null，调 embedList 抛 AiException）
List<Double> embedList(String text)   // 内部 embeddingModel.embed(text)→float[]→List<Double>；校验 size == AiProperties.embeddingDim,不符抛 AiException
String modelName()                    // 写入/检索共用的当前模型名
// AiProperties
int embeddingDim = 1024               // env AI_EMBEDDING_DIM
```

### 3. Contracts
- **切块唯一实现**：KB/NEWS `chunkContent` 改为薄委托 `TextChunker`；header 由调用方构造（KB「知识：t（d）」/ NEWS「新闻：t（date）」），空正文语义参数化（KB `keepTitleWhenEmpty=true` 恒保留；NEWS `titlePresent && keepTitleWhenEmpty=false`）。**句读集合也按域参数化（KB `。；!?` / NEWS `。；;！!？?`），不得取超集**——两域原集合不同，取超集会改变 KB 切块边界（违反「产出逐块不变」）；`splitSentences` 参数化后仍是全库唯一实现。产出逐块不变由 `KbDocServiceTest`/`NewsDocServiceTest` + `TextChunkerTest`（含 `legacyChunk` 等价性锁）证明。
- **切块滑动重叠（E2，2026-10-03）**：新增 6 参 `chunk(..., int overlapChars)`；**旧 5 参委托 `overlapChars=0`，逐块等价旧行为（向后兼容）**。仅 **KB/NEWS 服务层显式传 `DEFAULT_OVERLAP_CHARS=60` 启用**；**CAR（参数分组块）与 IMAGE（`ImageEmbeddingTextBuilder`）不经 `TextChunker`，切块形态不变**。策略：相邻产出块中前块**严格 >overlapChars** 时取尾部片段（≤overlapChars，优先从片段内首个句读符之后对齐）作后块前缀；前缀+本块仍须 ≤`MAX_BODY_LEN`（放不下则不重叠）；**不整块重复、不增块数**。回退：revert E2 + 按旧切块重嵌（**10-03 E6 起旧 4 表已删**，重嵌写入单表 store）。实测见 `docs/spec/retrieval.md §9` 与 `.trellis/tasks/10-03-e2-chunk-overlap/research/parity-B.md`。
- **并发执行器唯一实现**：`EmbeddingBatchRunner` 泛型化「固定线程池 + 单块重试 + 失败收集 + 计数日志」；**各域失败策略用参数保留**（CAR/NEWS `maxParallel=4,maxRetries=1`；KB `maxParallel=1,maxRetries=0` 串行无重试）。
- **事务边界统一（关键）**：embed 网络调用在事务外，随后经**自注入 `@Autowired @Lazy self`** 调 `@Transactional(REQUIRES_NEW)` 的持久化方法（`persistCarChunk`/`persistChunk`/`persistNewsDoc`）完成「插块行（拿 id）+ 插向量」原子写入。单测直 new 时 `(self==null?this:self)` 退化直调。
  - **反例（被本任务修复）**：`@Transactional protected insertDocWithEmbedding` 由同类线程池 lambda 内 `this` 调用 → 代理不生效、注解被忽略 → 向量插入失败时块行可能已落成孤儿、事务边界不明。
  - 收益同 IMAGE 范式：失败回滚不留孤儿块；`REQUIRES_NEW` 不污染调用方（`NewsService.upsertOne` 为 `@Transactional`）事务。
- **向量模型名防护**：写入盖 `modelName()`、检索/对账/补齐口径同模型过滤。~~4 张向量表加 `embedding_model` 列~~（**10-03 E6 旧表退役**：等价为单表 store `metadata.embeddingModel`，写入 `VectorStoreService.upsert` 盖名、检索 filter 带 `embeddingModel`、统计/差集 SQL 带 `metadata->>'embeddingModel'`）。详见 database-guidelines.md「向量模型名防护」。
- **维度 fail-fast**：`embedList` 返回长度 ≠ `embeddingDim` 抛 `AiException`（含实际/期望与模型名）。
- **embedding 后端 = Spring AI `EmbeddingModel`（C5）**：`EmbeddingClient` 内部改调 `EmbeddingModel.embed(text)`（OpenAI 兼容，指向 axonhub），**删除**原自研 RestClient/Jackson 调用；公共签名（`embed`/`embedList`/`modelName`/`toPgVector`）不变，8 个生产调用点与既有测试零改动。精度路径由 JSON→`List<Double>` 改为 SDK `float[]`→`double`，但 pgvector `vector` 本就是 float4，**检索结果 parity 不受影响**。
- **检索存储 = Spring AI `PgVectorStore` 单表（E1，2026-10-03；**推翻 C5 的 Scope B 推迟**）**：C5 曾以「`PgVectorStore` 无法表达 JOIN 活表语义」推迟全量迁移；后续用户拍板**先迁 PgVectorStore**，E1 已落地：
  - 单表 `vector_store(id uuid, content text, metadata json, embedding vector(1024))`（Flyway `V5__pgvector_store.sql`，HNSW `vector_cosine_ops` + metadata GIN；`initializeSchema=false`，**建表由 Flyway 管理，不依赖 Spring 自动建表**）。
  - `content` = `chunk_text`；`metadata` = `{domain(CAR/KB/NEWS/IMAGE), refId(域内 id：CAR=car_chunk.id/KB=kb_chunk.id/NEWS=news_doc.id/IMAGE=image_asset.id), modelId(仅 CAR), chunkType, name, active, embeddingModel}`。**行内 id 经 refId 回填 `Citation.docId`，语义不变**。
  - **KB 域扩展键（10-03 E3）**：`metadata` 另可含 `source`(可省)、`effectiveFrom`/`effectiveTo`(ISO `yyyy-MM-dd` 串，可省)、`tags`(List<String>，空不写)。经 `VectorStoreService.upsert(..., Map<String,Object> extraMeta)` **可选重载**写入（旧 9 参重载原样保留，CAR/NEWS/IMAGE 零影响；null 值键跳过）。KB 的 `active` = `enabled && 今天∈[from,to]`（`KbDocService.isActive`），由启动/每日 `KbEffectiveWindowReconciler` 按日重算覆盖。检索过滤不变（仍只 `active + embeddingModel`）。
  - **活表语义改由 metadata `active` 承载**（原 JOIN `deleted`/`enabled` 的等价）：软删/停用/重建三路径均须同步 `active`（`VectorStoreService.setActive` 经 native `jsonb_set` 直更，因 Spring AI 无「按 metadata 更新」API）。**漏同步 = 失效块仍被检索**，是最高风险点。
  - **域隔离候选窗口复现**：`searchDomains([CAR,KB])` 一次 + `searchDomains([NEWS])` 一次（对齐旧 UNION 的按域窗口）——`domain in [...]` 的 Spring AI filter 与旧语义等价。
  - **读取用 `similaritySearch`**（内部嵌入 query）；**写入/回填复用已算向量**（`VectorStoreService.upsert` 经 JdbcTemplate 直写 `embedding::text`，**不再嵌入**）；确定性 id `UUID.nameUUIDFromBytes(domain+":"+refId)`。
  - **`embedding_model` 防护等价为 metadata `embeddingModel` 过滤**。
  - **旧 4 张 embedding 表已于 10-03 E6 物理删除**（`V9__drop_legacy_embedding_tables.sql`）：写路径只写 store，`vectorStats`/`EmbeddingModelReconcileRunner`/`rebuildMissing` 改查 store（`metadata.domain`+`embeddingModel` 聚合/差集），旧 mapper 与 `VectorStoreBackfillRunner` 删除。`CarRagService` 业务规则（锚点/配额/门槛/四态/子查询/覆盖度）语义不变。详见 `docs/spec/retrieval.md §11`。
  - 详见 `docs/spec/retrieval.md §4.2` 与 `.trellis/tasks/10-03-e1-pgstore-migrate/research/parity-A.md`（阶段 A 逐条对拍）。
  - **注意**：`spring.ai.vectorstore.pgvector.initialize-schema` 保持 **false**——**不得**开启（会在 Flyway 之外重复建表）；建表/变更一律走 `db/migration/V<n>__*.sql`。
- **NEWS 手动重建端点**：`POST /api/news/{id}/rebuild`（ADMIN/EDITOR）→ `NewsDocService.rebuildForNews` 返回 `EmbedStats`。不做跨域一键重嵌编排。

### 4. Validation & Error Matrix
- 切块空正文：KB 恒 `[header]`；NEWS 无标题 `[]`、有标题 `[header]`。
- 嵌入单条失败：按 `maxRetries` 重试；仍失败计 `failed` 且**不持久化该条**（`EmbedStats` 可判定）。
- 维度不符 → `AiException`，块/图不落库。
- 换模型后旧行：检索不命中（store `metadata.embeddingModel` 过滤），启动 `EmbeddingModelReconcileRunner` 查 store `GROUP BY metadata.domain/embeddingModel` WARN（非当前模型名 + 条数），不阻断启动。

### 5. Good/Base/Bad Cases
- Good: KB 串行无重试、CAR/NEWS 并发重试 1 次，全部复用同一 `EmbeddingBatchRunner`，行为与改造前一致。
- Base: 单测直 new 服务（无 Spring 代理）→ `(self==null?this:self)` 退化为直写不 NPE。
- Bad: 在 rebuild 里直接 `this.insertDocWithEmbedding()`（`@Transactional` 失效）；或 store 检索 filter 漏加 `embeddingModel`（换模型后静默混空间）；或写路径仍双写旧表（E6 已删旧表，会直接报错）。

### 6. Tests Required
- `TextChunkerTest`（KB/NEWS 两语义；E2 增：默认无重叠等价旧实现回归锁、overlapTail 边界、句读对齐、不破 500 上限、不整块重复）；`EmbeddingBatchRunnerTest`（重试成功/两次失败/maxRetries=0/串行保序/空列表）；`EmbeddingClientTest`（维度不符抛 AiException + modelName）；`{Car,Kb,News}DocTransactionTest`（自注入代理持久化 + 无代理退化，写单表 store）；`EmbeddingMapperModelFilterTest`（10-03 E6：检索 filter 含 `embeddingModel`/`active`/`domain`；store 统计/差集 SQL 含 `metadata->>'domain'`/`'embeddingModel'`/`'refId'`/`'modelId'`）；`EmbeddingModelReconcileRunnerTest`（store 聚合、非当前模型仅告警、异常不阻断）；`ImageEmbeddingServiceTest`（rebuildMissing store 差集、删图清 store）。

### 7. Wrong vs Correct
#### Wrong
```java
// 线程池 lambda 内 this 调用:@Transactional 代理不生效,失败留孤儿块
tasks.add(() -> { try { insertDocWithEmbedding(doc); } catch (Exception e) { insertDocWithEmbedding(doc); } ... });

@Transactional protected void insertDocWithEmbedding(CarChunkEntity doc) {
    docMapper.insert(doc);
    embMapper.insert(doc.getId(), doc.getModelId(), embeddingClient.embed(doc.getChunkText()));
}
```
#### Correct
```java
// embed 在事务外;持久化经 self 代理走 REQUIRES_NEW
batchRunner.run(docs, CarChunkEntity::getChunkText,
        (doc, vec) -> (self == null ? this : self).persistCarChunk(doc, vec), "model=" + modelId, 4, 1);

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void persistCarChunk(CarChunkEntity doc, String vec) {
    docMapper.insert(doc);                                   // 拿 id
    // E6:只写单表 store(旧表已退役),不再 embMapper.insert
    vectorStoreService.upsert("CAR", doc.getId(), doc.getModelId(), doc.getChunkType(),
            doc.getModelName(), true, embeddingClient.modelName(), doc.getChunkText(), vec);
}
```

---

## Naming / Conventions

- 会话仅创建者可见：按 `created_by` 过滤，越权与不存在**统一** `IllegalArgumentException("会话不存在")` → 控制器 404（不泄露存在性）。
- 多轮历史窗口常量集中在服务（C4 先例 `QaService`）：最近 6 轮/12 条、单条 ≤2000 字、总 ≤12000 字、检索 query ≤300 字、短问题阈值 12 字。
- citations 落库为 JSON 字符串（TEXT 列），序列化失败仅 warn 并把列置 null，不阻断消息落库。

---

## Common Mistakes

### Common Mistake: 问答/浏览链路误读 kb_enabled

**Symptom**: 用户在设置里关闭「内部知识库」后，知识问答/浏览页不可用（违反「浏览/问答不设限」）。

**Cause**: 服务层顺手复用了生成链路的 `SettingService.kbEnabled` 门控。

**Fix**: 问答与浏览链路完全不注入 `SettingService`；KB 是否参与检索由 `AiProperties.ragKbEnabled` 决定。

**Prevention**: grep 新增服务的构造器依赖，确认无 `SettingService`/`sparkora_setting`。

### Common Mistake: 多轮追问丢失指代

**Symptom**: 首问「海狮08续航」→ 追问「那价格呢？」检索不到，答案泛泛。

**Cause**: 检索 query 只用当前问题（含指代词，向量检索无上下文）。

**Fix**: 检索 query 在短问题/有历史时拼接最近 2 轮 user 问题（`QaService.buildSearchQuery`，≤300 字）。

**Prevention**: 多轮链路的检索 query 与送 LLM 的 messages 分别构造——送 LLM 保留结构化历史，检索用拼接补指代。

### Common Mistake: 追加可选段落时改写公共尾部/双路径追加块头

**Symptom**: 向现有 prompt 追加可选字段（如简报四字段注入写作 prompt）时，把原有固定的尾部指引句一并改写（如「…已如上列出」），对**字段为空**的历史输入并不成立——prompt 与旧行为不再等价，模型被误导。另一形态：解析前先追加段头 `标题候选:\n`，`catch` 的原文兜底路径又追加一次 → 畸形 JSON 时出现重复块头。

**Cause**: 追加逻辑与「解析成功才拼接」两件事混在一条路径上；把「字段存在」当成默认前提，忽略了空/`null`/`[]`/`{}` 的历史退化分支。

**Fix**: ① 公共尾部指引句保持逐字不变，可选内容只作为**其前方的追加块**；② 段头只在解析成功后追加，兜底路径为**单一路径**、不重复追加；③ 全程 `try/catch` 仅 warn、绝不因单个字段解析失败中断主流程。

**Prevention**: 任何「可选内容注入既有 prompt」的改动都要有**空字段等价性回归测试**（断言空/`[]`/`{}` 时 prompt 与旧行为逐字一致）与**畸形 JSON 不重复块头**测试；先例 `DeepWriterServicePromptTest`（09-27-brief-writing-linkage-fix）。

### Common Mistake: @ConfigurationProperties 前缀漂移导致整块配置静默失效

**Symptom**: 按文档配置了 `SEARCH_WEB_ENABLED=false` 或 `sparkora.deep.tavily-api-key`，但行为毫无变化（开关关不掉、key 字段恒空）；应用不报错、启动正常，极难发现。

**Cause**: `DeepProperties` 声明 `prefix = "sparkora.ai.deep"`，而 `application.yml` 把 `deep:` 写在 `sparkora:` 下（`sparkora.deep`，`ai` 的**兄弟**节点）→ Spring relaxed binding 找不到对应前缀，整块字段保持 Java 默认值，**不抛异常**。此前仅因 `TavilySearchTool` 构造时绕过 Spring 直读环境变量 `TAVILY_API_KEY` 才碰巧可用，掩盖了缺陷。

**Fix**: 令注解前缀与实际 YAML 路径一致。本仓库域配置类惯例为 `sparkora.<domain>`（`NewsProperties`/`CarProperties`/`WenyanProperties`/`QiniuProperties`/`ImageProperties`），故 `DeepProperties` 取 `sparkora.deep`。

**Prevention**: 新增/改动 `@ConfigurationProperties` 时，用 `grep -rn "prefix = \"sparkora" src/main/java` 对照 `application.yml` 的缩进层级逐一核对；字段绑定不能只靠"环境变量兜底"证伪，需构造 `ApplicationContextRunner` 或启动后读取 `getXxx()` 实测非默认值。

### Common Mistake: 多链路共享文案各自复制导致漂移（分节档位）

**Symptom**: 同一产品规则（如「正文用几个 `## 小标题` 分节」）在两个正文生成链路各写一份常量/`if` 分档，一处升级（深度写作改为按目标字数自适应）后另一处（多版本/仿写）保持旧值「2~4 个」——同一项目因走不同链路得到不一致的排版约束，且无测试会发现。

**Cause**: 派生规则被当成两段「文案」分别硬编码，而非从**同一分类函数**取值；升级时自然只改被点名的链路。

**Fix**: 把分类逻辑抽成无 Spring 依赖的共享纯函数类（先例 `com.sparkora.service.LayoutRules`：`normalizeTarget`/`sectionSpec`，两链路同档），各链路只负责用自己的文案格式拼装（深度=单行分号串、VersionService=三段 bullet）；**只共享分类结果、不强行统一文案格式**，避免无关 diff 与既有断言回归。

**Prevention**: 出现「同一规则被多处 prompt 引用」时，优先提取纯函数类并让所有消费方委托；迁移时用 `git show HEAD:<file>` 逐字比对被迁移方法的输出，确保**行为零回归**；新增边界单测（`null/≤0/档位边界/Integer.MAX_VALUE`）锁定分档表。先例 `LayoutRulesTest` + `VersionServiceAsyncTest`（09-27-shared-layout-rules）。

---

## Scenario: AI 生成的读者可见内容不得含内部元话语（10-02-fix-meta-leak-in-article-body）

### 1. Scope / Trigger
- Trigger: 新增/修改任何**面向读者**的 AI 产出链路（文章正文、问答答案、推送文案…），或改动写作 prompt 的素材注入块。

### 2. Signatures
```java
// com.sparkora.service.ReaderViewRules(两条正文生成链路共用的 prompt 侧契约,纯静态)
public static final String READER_RULES;                       // 读者视角铁律(黑名单 + 行为指令)
public static String forbiddenClaimsBlock(String factRisksJson); // 「禁止写入正文的断言」块;不可用 → null
static List<String> factRiskClaims(String factRisksJson);       // 只抽 claim;不可用 → null

// com.sparkora.service.MetaLeakCleaner(落库前确定性清洗,纯静态)
public record CleanResult(String content, List<String> removed) {}
public static CleanResult clean(String markdown);             // 句级删除;零命中逐字原样返回
public static CleanResult cleanForPersist(String markdown);    // 清洗后无正文(空白/只剩标题)时回退原文
```

### 3. Contracts
- **写给作者的祈使指令 ≠ 写给读者的素材**：`fact_risks[].suggestion`（「此表述必须删除或改为 X」「建议正文以 Y 为主」）是作者向的第二人称指令；一旦进入正文素材区，模型必然复述/改写为第三人称陈述 → 元话语泄漏给读者。**唯一安全做法是不投 suggestion**，只投陈述性的 `claim` 作「禁止断言」清单（治本）。
- **注入失败一律不兜底原文**：与普通 prompt 块的「解析失败按原文追加」**刻意相反**——原文含祈使句，兜底等于把泄漏源送回素材区。null/空白/`[]`/`{}`/非数组/解析失败/全部 claim 空 → `null` 不注入；claim 归一（空白压单空格 + 200 字上限，防多行 claim 破坏「- 」清单结构）。
- **读者视角铁律放 system，黑名单 + 行为指令双写**：只禁词不禁行为，模型会换词绕开；「正文里不存在『资料/手册/简报』这些概念」先行切断把内部工件当叙述对象的动机；「禁止在正文中解释为什么没有这个数据」。**刻意不改**既有铁律 1~3 与「事实手册(数值唯一来源):」块头（约束语而非素材，既有测试逐字锁定）。
- **prompt 不是硬保证 → 落库前必须有确定性清洗**：`MetaLeakCleaner` 句级删除、**整句删不改写**（改写等于二次创作）；段按空行切、段内按句读 `。！？!?；;` + 闭合引号/括号切句；Markdown 块行（列表/有序项/标题/引用/表格行）行前换行亦为句界（否则一行泄漏连带删掉表头或相邻列表项）；**软换行多行块命中时收窄到行级删除**；段内全删则删段；**零命中逐字原样返回**（不归一空白），保证「未命中则逐字不变」与幂等。清洗位置必须在**数值回查之前**（已删句不再参与数值比对）与 `isBlank()` 校验之后；**仿写/用户原文派生内容不接**清洗器（误删作者原意风险）。
- **清洗不得毁掉整次生成**：`cleanForPersist` 在清洗后无正文（空白或只剩标题）且原文有正文时回退原文并保留 `removed`，绝不落空壳正文。删除审计走 `log.warn`，**不落库、不给前端**（零契约变更）。
- **模式表宁漏不误伤**：每条模式都是「内部词 + 缺失/否定」的组合特征（「详细参数以官方发布为准」「据媒体报道」「按 61,379÷463,561 计算」不得命中）；`待核实` 加否定前瞻（`官网/官方`）。改模式表时必须同时补「必须原样保留」的反例用例（AC4）。
- **落点与依赖方向**：prompt 侧共享契约与清洗器放**基础层** `com.sparkora.service`（与 `LayoutRules` 同范式），`com.sparkora.deep.service` 单向依赖之；**禁止基础层用 FQN 引用 deep 的 Spring bean 静态成员**（形成 `service` ↔ `deep.service` 包级环，并让基础层单测被迫加载 bean 类）。

### 4. Validation & Error Matrix
- `fact_risks` 非数组 / 畸形 JSON → `log.warn` + `null`（不注入，不阻断生成）。
- claim 非 textual / 缺失 / 空白 → 跳过该条；全部跳过 → `null`。
- 清洗后正文为空或只剩标题 → 回退原文 + `log.warn`（不落空正文）。
- 清洗命中数长期 > 0 → 说明读者视角铁律文案需加强措辞（迭代信号，非误伤信号）。

### 5. Good/Base/Bad Cases
- Good: brief 76 第 3 条 `suggestion`「此表述必须删除或改为「手册未披露年度目标，完成率无法计算」」永不进 prompt；`claim` 进禁写清单；正文泄漏句即使被模型产出也被清洗器删掉。
- Base: 无 `fact_risks` 的历史 brief → prompt 与旧行为逐字等价（不注入任何新块）。
- Bad: 把 `suggestion` 换个说法继续投（「建议改为……」）；解析失败时 `append(label + ":" + 原文)` 兜底；清洗按行/按整段删而不看句界；清洗后直接落空 `content_md`。

### 6. Tests Required
- `ReaderViewRulesTest`：claim 进块 / suggestion·riskLevel 不进块；畸形·非数组·全无 claim → null；多行 claim 归一后不破坏清单结构；超长截断；`READER_RULES` 黑名单与「禁止在正文中解释」存在。
- `MetaLeakCleanerTest`：真实泄漏样本零残留 + 同段正常句逐字保留；表格/有序列表/引用块中泄漏只删该行；软换行块只删命中行；正常正文（含数值句、官方归因句、Markdown 标题/列表/表格/图片行/html、多级缩进、引号书名号内句读）零误删；幂等；`cleanForPersist` 致空/只剩标题回退原文。
- 链路级：`DeepWriterServicePromptTest`（suggestion 不进 prompt、claim 进块、读者视角铁律、铁律 1~3 与块头逐字不变、落库 = 清洗后正文、`verifyNumbers` 收到清洗后文本）、`VersionServiceAsyncTest`（主题分支无「事实风险点」、仿写分支跳过清洗器）。

### 7. Wrong vs Correct
#### Wrong
```java
// 祈使句当素材注入 → 模型改写成读者话术泄漏
appendBriefSection(user, "事实风险", b.getFactRisks(), true);   // 兜底还会按原文再投一次

// 基础层反向依赖 deep 的 bean 静态成员(包级环)
String forbidden = com.sparkora.deep.service.DeepWriterService.forbiddenClaimsBlock(b.getFactRisks());
```
#### Correct
```java
// 只投陈述性 claim;不可用就不注入,绝不按原文兜底
String forbidden = ReaderViewRules.forbiddenClaimsBlock(b.getFactRisks());
if (forbidden != null) user.append(forbidden);

// 落库前句级清洗(数值回查之前),清洗致空回退原文
MetaLeakCleaner.CleanResult cleaned = MetaLeakCleaner.cleanForPersist(content);
content = cleaned.content();
List<String> unknown = verifyNumbers(content, b.getFactSheet());
```

---

## Scenario: axonhub 能力边界契约（C0 探针，Spring AI 迁移前置）

### 1. Scope / Trigger
- Trigger: 引入/迁移 Spring AI（C1–C7）、改动结构化输出、Tool Calling、reasoning 解析，或配置 `spring.ai.*`。
- 依据：`.trellis/tasks/10-02-c0-upgrade-probe/research/axonhub-capability-probe.md`（2026-10-03 实测）。

### 2. Signatures
```yaml
spring:
  ai:
    openai:
      base-url: ${AI_BASE_URL:https://axo.caiqz.cn}   # OpenAI 兼容聚合代理
      api-key: ${AI_API_KEY:}
      chat.options.model: ${AI_MODEL:}                # 请求体标识;axonhub 路由到实际 serving 模型
      embedding.options.model: ${AI_EMBEDDING_MODEL:} # Qwen3-Embedding-8B,维度 1024
```
- 复用既有 `AI_*` 环境变量；自定义业务配置仍是 `sparkora.ai.*`（`AiProperties`），**与 `spring.ai.*` 前缀不冲突**。

### 3. Contracts（实测能力边界）
| 能力 | 结论 | 约束 |
|---|---|---|
| `response_format.json_schema`（strict） | **退化**：2xx 但静默忽略 schema（中文字段名/缺 required/多余字段） | **不得**把正确性寄托于 `useProviderStructuredOutput()` |
| `tools` / Tool Calling | **支持**：`finish_reason=tool_calls` + 标准 `tool_calls[]` | C3 可走标准 Tool Calling |
| reasoning 字段 | **支持**，字段名 `reasoning`（非 `reasoning_content`） | `parseChat` 先读 `reasoning` 再回退 `reasoning_content` |
| `response_format.json_object` | 支持 | 现有 `chatJson` 零回归 |
| `/v1/embeddings` | 支持（Qwen3-Embedding-8B，**1024**） | `PgVectorStore.dimensions=1024`（与现向量表 DDL 一致） |

- **结构化输出的正确性必须靠响应侧自纠错**：因 provider 原生 `json_schema` 退化，C2 走「prompt 内 schema + Spring AI `validateSchema()` 自纠错（校验错误回填重试）」。
- **serving 模型名以响应 `model` 字段为准**，不是请求体 `AI_MODEL`（axonhub 会路由/别名，如 `deepseek-v4-pro-cus` → `deepseek-v4.1-flash`）。

### 4. Validation & Error Matrix
- provider 忽略 `json_schema` → 返回结构不合规 JSON → 由 `validateSchema()` 捕获并重试，**不能视为调用失败**。
- 缺 `json_schema` 原生支持 → `useProviderStructuredOutput()` 自动退化，不影响 `validateSchema()` 生效。

### 5. Good/Base/Bad Cases
- Good: C2 用 `entity(X.class, spec -> spec.validateSchema())`；C3 用标准 Tool Calling。
- Base: `useProviderStructuredOutput()` 保留为「provider 未来升级后的免费增强」开关，非正确性依赖。
- Bad: 因 axonhub 返回 2xx 就认为它遵守了 `json_schema`；或把 `AI_MODEL` 当作 serving 模型名做选型判断。

### 6. Tests Required
- C2：构造「缺字段/类型错/多余字段」响应，断言模型收到具体校验错误后重试成功。
- C3：断言工具调用走标准 `tool_calls`；`toolHealth`/`available()` 契约不变。
- 解析：`reasoning` 优先、`reasoning_content` 回退。

### 7. Wrong vs Correct
#### Wrong
```java
// provider 原生结构化退化,却把正确性押在它上面 → 字段漂移静默入库
BriefDto dto = chatClient.prompt().user(p).call()
        .entity(BriefDto.class, spec -> spec.useProviderStructuredOutput());
```
#### Correct
```java
// 响应侧 schema 校验 + 错误回填自纠错,对 provider 是否原生支持不敏感
BriefDto dto = chatClient.prompt().user(p).call()
        .entity(BriefDto.class, spec -> spec
                .useProviderStructuredOutput()   // 退化时自动忽略
                .validateSchema());              // 正确性依赖此步
```

---

## Scenario: 结构化输出契约（C2，schema 单一来源 + 响应侧自纠错）

### 1. Scope / Trigger
- Trigger: 新增/修改「AI 返回结构化 JSON」链路（简报、澄清计划、子代理事实…），或改动 `AiClient.structured` / DTO schema / `{{schema}}` 注入。
- 前置：axonhub 忽略 provider 原生 `json_schema` strict（C0 探针），故正确性依赖响应侧校验。

### 2. Signatures
```java
// AiClient（C2 新增；旧 chat/chatJson/chatMessages/ChatResult/sanitizeAiJson 不变）
record TypedResult<T>(T entity, ChatResult chat) {}
<T> TypedResult<T> structured(String system, String user, int maxTokens, Class<T> type)
static <T> String jsonSchema(Class<T> type)   // = new BeanOutputConverter<>(type).getJsonSchema()

// DTO = schema 单一来源（字段说明用 @JsonPropertyDescription 内聚）
BriefDto / ClarifyPlanDto / SubAgentFactsDto
```

### 3. Contracts
- **schema 单一来源 = DTO 类型**：`structured` 用 `StructuredOutputValidationAdvisor.builder().outputType(type)`
  派生 schema 并校验；prompt 通过 `{{schema}}` 占位注入 `jsonSchema(type)`，**prompt 内不再内联 schema 字面量**。
- **响应侧自纠错**：校验失败时 advisor 把**具体校验错误**（`JSON validation failed: …`）回填 user prompt 重试；
  `maxRepeatAttempts=1`（最多 2 次净调用）。`useProviderStructuredOutput()` **不启用**（axonhub 下无效）。
- **截断与 schema 违规分离（语义）**：`finish_reason=length` 最终由 `parseChat` 抛截断 `AiException` → 服务层提额重试；
  字段/类型/多余字段由 advisor 同额度自纠错。**实际代价**：advisor 在 `parseChat` 前执行，半截 JSON 也会触发一次
  同额度自纠错，故截断路径 = 2 次同额度 + 服务层提额，最坏 ≤4 次（正常 1 次，纯 schema 违规 2 次）。
- **DTO 字段约定**：`json_object` 模式 + `BeanOutputConverter` 用 `tools.jackson`（Jackson 3），
  业务侧仍 `com.fasterxml`（Jackson 2），未知字段被忽略（`FAIL_ON_UNKNOWN_PROPERTIES` 关闭）。
  **⚠️ 两个 Jackson 版本在 HTTP 响应序列化边界会互踩**：Boot 4 MVC 用 Jackson 3 序列化 controller 返回值；
  若业务层把 Jackson 2 的 `JsonNode`/`ObjectNode`/`ArrayNode` **直接 put 进返回的 `Map`/DTO**，Jackson 3 不认识该类型，
  会退化为反射式 bean 序列化，输出 `{"array":false,"object":true,"nodeType":"OBJECT",...}` 之类的元数据壳，
  **而非真实 JSON**（AI 产出的 `session`/`taskBrief`/`question` 全部变味）。返回 JSON 树前必须转成纯值：
  `Object v = objectMapper.convertValue(node, Object.class)`（null/missing → null）后再放入响应；
  只返回**已序列化的字符串**（如 `/deep/status` 落库列）天然免疫。C1 `ClarifyConversationService` 6 处响应插入
  即因此加了 `nodeToValue(JsonNode)` 包装（`start/answer/converge` 的 `question`/`session`/`taskBrief`），
  并有回归用例 `start响应_question与session为纯值_非JsonNode` 锁定。
- **`@JsonPropertyDescription` 会进 schema 的 description**；**jakarta.validation（`@Size` 等）不影响 schema**
  （victools SchemaGenerator 不读它），`required` 来自「所有声明属性默认必填」——勿以为注解约束了 AI 输出。
- **元数据透传**：`TypedResult.chat()` 提供 model/totalTokens/finishReason/reasoning；自纠错两轮时
  `totalTokens` 为 `UsageAccumulator` 累加值（略高于单轮）。

### 4. Validation & Error Matrix
- 缺字段/类型错/多余字段 → advisor 回填错误重试；仍不合规则返回部分实体（**不抛异常**，与旧 `readValue` 一致，不回归）。
- `finish_reason=length` → `parseChat` 抛截断 `AiException`（服务层提额重试）。
- 空 content / 无 result → `parseChat` 抛 `AiException`。
- provider 忽略 `json_schema`（2xx 但结构不符）→ 正常走 advisor，**不视为调用失败**。

### 5. Good/Base/Bad Cases
- Good: `structured(system, user, 8192, BriefDto.class)`，schema/校验/反序列化同源。
- Base: 首次合规 → 1 次调用；字段违规 → 2 次（第二次带具体错误）。
- Bad: 在 prompt 里再手写一份 schema 字面量（双源漂移）；或把「provider 返回 2xx」当作 schema 已遵守。

### 6. Tests Required
- `AiClientStructuredTest`：缺字段/类型错/多余字段 → 第二次请求含 `JSON validation failed` 并成功；截断 → 抛截断异常且断言
  **恰好 2 次**同额度调用；`sanitizeAiJson` 围栏/裸控制字符回归；三 DTO schema 可派生。
- 服务层：`BriefServiceTest`/`ClarifyServicePlanTest`/`SubAgentRunnerTest` 桩改 `structured(...,eq(Dto.class))`，
  断言自纠错后字段落库、截断提额路径、后处理（背景题兜底等）不变。

### 7. Wrong vs Correct
#### Wrong
```java
// prompt 内联 schema 字面量 + 只靠 response_format=json_object:字段漂移无校验,静默入库
String system = "输出 JSON:{\"titleCandidates\":[...],...}";
aiClient.chatJson(system, user, 8192);
```
#### Correct
```java
// schema 由 DTO 派生注入,响应侧校验+自纠错:字段违规被具体错误纠正
String system = PromptTemplateLoader.render("brief/deep-brief-system.st",
        Map.of("schema", AiClient.jsonSchema(BriefDto.class), ...));
BriefDto dto = aiClient.structured(system, user, 8192, BriefDto.class).entity();
```

---

## Scenario: 检索工具暴露为 Spring AI ToolCallback（C3 能力层）

### 1. Scope / Trigger
- Trigger: 把检索工具暴露为 Spring AI `ToolCallback`、新增模型驱动检索链路，或改动 `SearchToolCallbacks`。

### 2. Signatures
```java
// 工厂（非 bean）
SearchToolCallbacks(KnowledgeSearchTool kbTool, WebSearchRouter webRouter, List<Long> anchors, WebSearchSnapshot snapshot)
ToolCallback[] forTools(List<String> names)   // names ∈ {KB, TAVILY, SEARXNG, SERPER, WEB}（大小写不敏感）
// 工具名（对模型暴露）: kb_search / web_search
```

### 3. Contracts
- **确定性编排不变**：`SubAgentRunner.research()`（KB → 策略路由 WEB → LLM 单次汇总）**不**改为模型驱动 agent loop；
  检索仍由 Java 策略决定（provider 顺序 / gap 驱动跳过 / 背景题放行 / sourceId 治理）。本能力层是**增量**，
  供未来显式选择「模型驱动检索」的链路复用。
- **绝不注册为全局 `ToolCallback` bean（关键踩坑）**：Spring AI 自动配置会把容器内所有 `ToolCallback` bean
  作为**所有** `ChatClient` 的默认工具 → 普通对话/结构化调用会被意外注入工具、模型可能吐 `tool_calls` → 行为漂移。
  故工厂**无 stereotype 注解**、不产出 `ToolCallback` bean；由调用方按需 `new` + `forTools(...)` 后传给
  `ChatClient...toolCallbacks(...)`。
- **WEB 必须经 `WebSearchRouter`**：保留 provider 顺序、治理（协议校验/规范化/去重/截断）与稳定 `sourceId`；
  **不得**直连 `TavilySearchTool`/`SearxngSearchTool`。
- **可用性门控**：`available()==false` 的 KB 不暴露；WEB 仅当 `snapshot.webAllowed()` 且至少一个 provider
  `configured()` 时暴露（与 `DeepResearchService.applySettingGates` 的 WEB 剔除语义一致）。
- **工具名兼容 `WEB` 别名**：流水线词汇（`applySettingGates`/`parseTools`/`ClarifyService`）用 `KB`/`WEB`，
  适配器同时认 `TAVILY`/`SEARXNG`/`SERPER`/`WEB`（同名去重为一个 `web_search`）。
- **异常绝不抛出**：工具方法内部捕获全部异常，返回中性提示串（不含异常原文/密钥，可能含 URL），仅类型化日志
  ——与 `SearchTool`「绝不抛出」约定一致。

### 4. Validation & Error Matrix
- `names` 空/null/未知值 → 忽略，返回空数组（不返回 null）。
- 工具 `available()==false` → 不暴露该工具。
- WEB 开关关闭（`webAllowed=false`）→ 不暴露 WEB 且**不探测** provider 配置。
- 工具调用内部异常 → 返回「暂不可用…」提示串，不抛出、不泄漏异常文本。

### 5. Good/Base/Bad Cases
- Good: 显式 `new SearchToolCallbacks(kb, router, anchors, snapshot).forTools(List.of("KB","WEB"))` →
  传给特定 `ChatClient` 的一次请求。
- Base: 无任何可用工具 → 空数组（模型无工具可调）。
- Bad: 把工厂或任一 `ToolCallback` 注册为 `@Bean`/`@Component`（污染所有 ChatClient）。

### 6. Tests Required
- `SearchToolCallbacksTest`：名称稳定（`kb_search`/`web_search`）、`WEB` 别名、可用性门控（KB 不可用不暴露、
  WEB 开关关闭不暴露且不探测 provider）、KB 委托（query/maxResults/锚点透传）、WEB 经 `WebSearchRouter`
  （`verify`，结果保留 sourceId/url/provider）、异常降级不抛出且不泄漏异常文本。

### 7. Wrong vs Correct
#### Wrong
```java
// 注册为全局 bean → Spring AI 注入所有 ChatClient,普通对话被塞入工具
@Bean ToolCallback kbToolCallback() { return ToolCallbacks.from(new KbAdapter(...))[0]; }
```
#### Correct
```java
// 按需工厂,仅传给需要模型驱动检索的那一次请求;普通 ChatClient 零注入
ToolCallback[] tools = new SearchToolCallbacks(kb, router, anchors, snapshot).forTools(List.of("KB", "WEB"));
chatClient.prompt().user(q).toolCallbacks(tools).call().content();
```

---

## Scenario: 文生图接 Spring AI ImageModel，图生图保留自研（C6）

### 1. Scope / Trigger
- Trigger: 改动图片生成链路（`AiImageClient`）、文生图/图生图端点、图片模型配置，或 Spring AI 图模型相关装配。

### 2. Signatures
```java
// AiImageClient（双构造：注入优先，缺失回退自建）
@Autowired AiImageClient(AiProperties props, ObjectProvider<ImageModel> imageModel);  // 生产路径
AiImageClient(AiProperties props);                                                      // 单测/兼容路径（imageModel=null）

GenResult generateText2Image(String prompt, String size);   // 文生图 → Spring AI ImageModel（/v1/images/generations）
GenResult generateImage2Image(String prompt, String size, List<byte[]> refs, List<String> filenames);
                                                            // 图生图 → 自研 RestClient（/v1/images/edits，multipart 多参考图）
record GenResult(String url, String model) {}
```
- 配置：`spring.ai.openai.image.options.model: ${AI_IMAGE_MODEL:}`（兜底）；实际模型仍由 per-call
  `OpenAiImageOptions.builder().model(m).n(1).size(sz)` 按 `props.imageModelList()` 覆盖。

### 3. Contracts
- **文生图 = Spring AI `ImageModel`**：`imageModel.call(new ImagePrompt(prompt, OpenAiImageOptions...))` →
  `getResult().getOutput()` 取 `getUrl()`，否则 `getB64Json()`（转 `data:image/png;base64,…`），
  均无则视该模型失败并轮询下一个。**不再需要旧 `byte[]` hack**——Spring AI 的官方 OpenAI SDK
  对 `application/octet-stream` 包裹的 JSON 响应可正常解析（旧 `RestClient` String 转换器不能）。
- **图生图必须保留自研**：Spring AI `OpenAiImageModel` 内部**只调 `/v1/images/generations`**，**不支持**
  `/v1/images/edits` multipart。故 `generateImage2Image` 走自研 `RestClient` + `postMultipartForJsonText`
  + `parseFirstUrl`，**逐字不动**。
- **保留的方法**：`postMultipartForJsonText`、`parseFirstUrl` 因 edits 复用**必须保留**；
  仅文生图专用的 `postForJsonText` 可删。
- **回退路径**：`imageModel==null`（单测构造）时惰性自建 `OpenAiImageModel`，baseUrl 经
  `AiClient.normalizeBaseUrl` 补 `/v1`（与 C1/C5 先例一致）。
- **契约不变**：多参考图上限 1~4、**顺序契约**（先上传后图库 id，不重排）仅涉及 edits，不动；
  错误文案逐字不变；`ImageService`/`ImageController` 零改动。

### 4. Validation & Error Matrix
- 文生图某模型 `ImageResponse` 无 url 且无 b64 → 计为该模型失败原因，轮询下一个；全失败 → `所有图片模型均失败: …`。
- 图生图参考图空 → `图生图参考图为空`；参考图与文件名数量不匹配 → `图生图参考图与文件名数量不匹配`（均不改）。
- `AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置` → `AiException`（不改）。

### 5. Good/Base/Bad Cases
- Good: 文生图经 `ImageModel`（url/b64/octet-stream 三路均可），edits 仍走自研 multipart 保序。
- Base: `imageModel` 未注入（单测 `new AiImageClient(props)`）→ 回退自建仍可生图。
- Bad: 把 edits 也改走 `ImageModel`（框架不支持该端点）；或删除 edits 复用的 `parseFirstUrl`。

### 6. Tests Required
- `AiImageClientText2ImageTest`：url 响应、b64_json 响应、`application/octet-stream` 包裹仍成功、
  多模型轮询（首败次成）、全失败文案含「所有图片模型均失败」、`imageModel` 未注入回退自建、wire 请求 `n`/`size` 透传。
- `AiImageClientMultiRefTest`（edits 保序 4 用例）必须保持绿、断言不削弱。
- `ImageServiceMultiRefTest`/`ImageServiceBodyImageTest` 零回归。

### 7. Wrong vs Correct
#### Wrong
```java
// 把图生图也改走 ImageModel：框架只支持 /v1/images/generations,edits 端点会失败
imageModel.call(new ImagePrompt(prompt, opts));   // 图生图路径
```
#### Correct
```java
// 文生图走框架 ImageModel;图生图保留自研 multipart(保序多参考图)
GenResult t2i = imageModel().call(new ImagePrompt(prompt, OpenAiImageOptions.builder().model(m).n(1).size(sz).build()));
GenResult i2i = postMultipartForJsonText("/v1/images/edits", body, model);   // 不变
```

---

## Scenario: 正文数值回查归一化（C7，10-02-c7-metaleak-numbers）

### 1. Scope / Trigger
- Trigger: 新增/修改 `DeepWriterService.verifyNumbers`（正文数值是否收录于事实手册的回查）、
  或改动正文数值抽取正则、手册「已收录」判定口径。

### 2. Signatures
```java
// DeepWriterService（签名不变；判定实现 C7 变更）
List<String> verifyNumbers(String content, String factSheetJson) throws Exception
static String canonicalNumber(String raw)      // 包级纯函数：复用 ClaimSimilarity 归一口径，返回首个数值签名；无数字→null
```

### 3. Contracts
- **收录判定 = 数值签名集合比对，不是子串匹配**：`known = ClaimSimilarity.numberValues(sheet.toString())`；
  正文每个数值 token 求 `canonicalNumber(raw)`（去千分位/空白、`万×10000`/`亿×1e8`、
  `BigDecimal.stripTrailingZeros`）后 `known.contains(...)`。**复用 `ClaimSimilarity.numberValues`
  的同一套口径**（单一事实来源，勿新造第二套归一）。
  - 修复漏报：内容 `1200`、手册仅 `12000` → `1200 ∉ {12000}` → 报 high（旧子串 `contains("1200")` 会命中 `12000` 而漏报）。
  - 修复误报：`200000` vs `20万`、`33.21%` vs `33.21%`、`239,900` vs `239900` 归一后同签名 → 不报。
- **抽取正则（C7 对齐 `numberValues` 口径）**：`\d[\d,\.]*\s*(?:万|亿)`（数量级，含**亿**）|
  `\d{1,3}(?:,\d{3})+(?:\.\d+)?`（千分位整体，**须置于纯数字前**，否则 `239,900` 被拆成 `239`/`900`）|
  `\d{4,}(?:\.\d+)?`（≥4 位纯数字，上限**放开**防截断）| `\d+\.(?:\d+)?%?` | 带长度单位数值。
  粗筛 `num.length()<2 || "0".equals(num)` 保留；`num = raw.replaceAll("[ ,万]","")` 仅用于该粗筛。
- **`unknown` 返回原始 token 形态**（供 `factRisks.claim`「正文数值「X」未收录于事实手册」文案原样引用），按 token 去重。
- **手册 `null`/`{}`（无手册）→ `known` 空 → 所有数值报 high**（既有语义保持）。
- **归一解析失败不抛**：`ClaimSimilarity.normalizeNumber` 内部回退原 token。

### 4. Validation & Error Matrix
- 手册 `null`/`{}` → 正文数值全部 high。
- 数值解析失败 → 回退原 token，仍可比，不抛。
- `known` 来自整册 JSON 字符串（含 key/来源 id/confidence 等数字）——**属既有口径**（旧实现同样以
  `sheet.toString()` 为 haystack）；如需只认 `entries[].claim/value` 须另立任务。

### 5. Good/Base/Bad Cases
- Good: 内容 `9月销量46.36万辆`、手册含 `46.36万` → 归一 `463600` 同签名 → 不报。
- Base: 内容含手册已收录的 `第2000座` → `2000` 命中 → 不报。
- Bad: 用子串 `contains` 判定 → `1200` 被 `12000` 掩盖（漏报）；或抽取漏「亿」/截断 ≥8 位数字 → 同值误报。

### 6. Tests Required
- `DeepWriterServicePromptTest`：`1200` vs `12000` 不漏报；`万`/`亿`/千分位/小数同值不误报；≥8 位（`12000000`）不误报；
  `{}`/`null` 手册仍报 high；`canonicalNumber` 与 `ClaimSimilarity.numberValues` 口径一致。
- `FactSheetServiceTest`：归并后数值回查不回归。

### 7. Wrong vs Correct
#### Wrong
```java
// 子串匹配:1200 命中 12000 → 漏报;20万 vs 200000 失配 → 误报
if (!haystack.contains(raw) && !haystack.contains(num)) unknown.add(raw);
```
#### Correct
```java
// 同一套数值签名归一化集合比对(与 claim 归并口径一致)
Set<String> known = new HashSet<>(ClaimSimilarity.numberValues(sheet.toString()));
String canon = canonicalNumber(raw);
if (canon == null || !known.contains(canon)) unknown.add(raw);
```

---

## Scenario: 覆盖度三域统一 + 嵌入缓存（E5，10-03-e5-coverage-dedup）

### 1. Scope / Trigger
- Trigger: 改动 `CarRagService` 的 `coveredText` 组装、`NumericSignature` 数值签名、`EmbeddingClient.embedForIndex` 写入路径缓存，或新增内容去重缓存表。

### 2. Signatures
```java
// NumericSignature（com.sparkora.ai，纯静态；ClaimSimilarity.numberValues 委托之，行为逐字不变）
public static List<String> numberValues(String... texts)   // 去千分位/万/亿 → BigDecimal 归一，去重稳定排序

// CarRagService（RagResult.coveredText 语义扩展；字段类型/位置不变）
static String coverageSegment(String label, String title, String chunkText)  // 〔label：title〕v1,v2（无数值→""）
static String buildExtraCoverage(List<String> segments)                      // 去重保序 + COVERED_MAX=400 截断

// EmbeddingClient
public String embed(String text)          // 查询用：**无缓存**（每次网络调用）
public String embedForIndex(String text)  // 写入用：sha256(text)+(model) 命中→复用；未命中→embed+best-effort 写缓存

// EmbeddingCacheService（@Service；put 为 REQUIRES_NEW 独立事务）
String get(String contentHash, String embeddingModel)   // 读异常→null（按未命中）
void put(String contentHash, String embeddingModel, String embedding)  // best-effort，冲突忽略
```

### 3. Contracts
- **coveredText 三域**：CAR `PARAM_GROUP` 仍走 `extractParamSummary`（`key→value`）；KB/NEWS 新增数值事实段 `〔通用知识：<标题>〕<v1,v2>` / `〔官方新闻：<标题>〕<...>`，段间 `；`，整体去重保序 + `COVERED_MAX=400` + 单段数值上限 12。
- **CAR-only 逐字等价（硬回归）**：仅 CAR 命中时 `extra` 为空 → 不追加任何内容，`coveredText` 与改造前**逐字一致**。
- **数值口径单一来源**：`NumericSignature`（`com.sparkora.ai`）为唯一实现；`ClaimSimilarity.numberValues` 委托；C7 正文数值回查、E5 覆盖度抽取**同源**——`1200` 与 `12000` 不误配。
- **缓存键 = `(content_hash, embedding_model)`**：换模型天然 miss，绝不复用旧模型向量。
- **写/查分离**：仅 `embedForIndex`（写路径，经 `EmbeddingBatchRunner` 的 CAR/KB/NEWS）走缓存；`embed()`（查询路径）+ IMAGE `embedOne` 无缓存。
- **best-effort 隔离**：缓存 `put` 走 `REQUIRES_NEW`（字段注入走代理）；失败仅 warn，绝不污染调用方事务/阻断主流程。缓存未注入或 sha256 失败 → 退化直调 `embed`。
- **表**：`sparkora_embedding_cache(content_hash CHAR(64), embedding_model VARCHAR(100), embedding TEXT, created_at)`，PK `(content_hash, embedding_model)`；`embedding` 存 pgvector 字面量（不做 ANN）。

### 4. Validation & Error Matrix
- KB/NEWS 块无数值 → 该块不产出覆盖段（不报错）。
- 无标题 → `〔通用知识：〕`（保留冒号）。
- 缓存读异常 → 视为未命中（warn）。
- 缓存写冲突/异常 → 忽略（warn），主流程照常返回向量。
- 换模型 → 缓存 miss 重算。

### 5. Good/Base/Bad Cases
- Good: CAR-only 命中 → `coveredText` 与旧实现逐字相同；KB/NEWS 命中 → 追加带标题的数值段。
- Base: 缓存未注入（单测直 new）→ `embedForIndex` 等价 `embed`。
- Bad: 覆盖度抽取另造一套数值正则（与 C7/claim 归并漂移）；或查询路径也套缓存（污染 + 膨胀）；或把缓存写放在调用方事务内（失败 aborted 污染）。

### 6. Tests Required
- `CarRagServiceTest`：CAR-only 逐字等价（回归锁）；KB/NEWS 段格式；三域并存 CAR 在前；无标题保留冒号；无数值不产出段。
- `EmbeddingClientCacheTest`：同文本第二次命中不网络调用（计数 stub）；不同文本各自调用并写缓存；换模型 miss；`embed` 无缓存；`sha256` 稳定 64 位十六进制。
- `ClaimSimilarityTest`：委托后行为逐字不变（既有 14 用例）。

### 7. Wrong vs Correct
#### Wrong
```java
// KB/NEWS 也直接 extractParamSummary（只认「key：value」参数行）→ 知识/新闻块覆盖度永远为空
if ("KB".equals(h.source())) covered.append(extractParamSummary(h.chunkText()));
```
#### Correct
```java
// KB/NEWS 抽数值事实（与 C7 同源签名），CAR 保持参数摘要；仅 extra 非空时才追加分隔符
extraCoverage.add(coverageSegment("通用知识", h.modelName(), h.chunkText()));
String extra = buildExtraCoverage(extraCoverage);
if (!extra.isEmpty()) { if (covered.length() > 0) covered.append("；"); covered.append(extra); }
```
