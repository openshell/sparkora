# AI / RAG Guidelines

> AI 调用与知识检索（三域 RAG）的跨层契约。适用于新增「AI 合成」或「知识检索」链路。

---

## Overview

- AI 文本统一走 `com.sparkora.ai.AiClient`（RestClient 直调 axonhub OpenAI 兼容 `/v1/chat/completions`）。
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
// C4 新增（非破坏）
AiClient.ChatResult chatMessages(List<Map<String,String>> messages, int maxTokens)   // 多轮，不强制 JSON
```
- `messages` 每项 `{role, content}`，`role∈{system,user,assistant}`；顺序即对话顺序。
- `ChatResult{content, model, totalTokens}`。

### 3. Contracts
- 三方法共用同一 `rest` 实例（`AiProperties.baseUrl/apiKey/timeoutMs`）、`resolveTextModel()`、`parseChat()`。
- `chatMessages` 不设 `response_format`；`temperature` 同 `chat`。
- `content` 为空（reasoning 截断）→ `parseChat` 抛 `AiException`，**不要返回半截内容**。

### 4. Validation & Error Matrix
- messages 为空/null → 上层保证非空（当前未做显式校验；调用方必传 system+user）。
- AI 超时/非 2xx/无 choices/空 content → `AiException`。
- JSON 场景模型偶发裸控制字符 → 统一 `AiClient.sanitizeAiJson(raw)` 后再 `readTree`。

### 5. Good/Base/Bad Cases
- Good: 多轮问答 `system`（含知识上下文）+ 历史 + 本轮 user，`chatMessages` 一次合成。
- Base: 单轮文本 `chat`。
- Bad: 直接把历史逐条调 `chat` 再拼接（丢失对话结构、成本高）。

### 6. Tests Required
- 断言 `chatMessages` 组装的 messages 顺序/角色；AI 返回空 content 抛 `AiException`。

### 7. Wrong vs Correct
#### Wrong
```java
// 追问把历史压成一段字符串塞进 userPrompt，模型无法区分轮次
aiClient.chat(sys, history + "\n" + question, 2048);
```
#### Correct
```java
List<Map<String,String>> messages = new ArrayList<>();
messages.add(Map.of("role","system","content", systemPrompt));
messages.addAll(historyMessages);           // {role,content} 交替
messages.add(Map.of("role","user","content", question));
aiClient.chatMessages(messages, 2048);
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
//   docId 可空（09-15 qa-auto-illustrate 补读；CAR=car_doc.id/KB=kb_chunk.id/NEWS=news_doc.id）；
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
}
```

### 3. Contracts
- **`available()` 只表示「配置就绪」**：Tavily = 密钥非空；SEARXNG = 地址非空。**不得包含 `lastCallOk()`**——否则调用失败后 `available()==false`，调用方（`SubAgentRunner`）跳过 `search()`，而失败标志只能在被跳过的 `search()` 里重置 → **永久禁用，直到重启**（自锁死）。
- `configured()` = 配置态（与 `available()` 同源，供 `toolHealth` 三态判定复用）。
- `lastCallOk()` = 最近一次调用健康态，仅用于**展示**；调用失败置 false 不再影响门控，故下次研究天然重试（自恢复）。
- `toolHealth`（`/deep/status` 响应）值为状态码字符串，非布尔：
  - `OK` | `DISABLED`（被设置门控关闭）| `UNCONFIGURED`（无 key/地址）| `FAILED`（最近一次调用失败）
  - `KB` = `SettingService.isKbEnabled() ? "OK" : "DISABLED"`（反映 DB 运行时门控，**不恒 true**）。
  - `SEARXNG`/`TAVILY`：`webAllowed = DeepProperties.isSearchWebEnabled() && SettingService.isWebSearchEnabled()`；优先级 `DISABLED > UNCONFIGURED > FAILED > OK`。
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
enum WebProvider { TAVILY, SEARXNG }
record WebProviderOrder(List<WebProvider> providers) {
    static WebProviderOrder parse(String csv);     // 空回退 TAVILY,SEARXNG;未知值抛 IllegalArgumentException
    static WebProviderOrder defaults();
    String raw();                                   // 规范化串(落库/日志/响应)
    String strategyLabel();                         // TAVILY_FIRST / SEARXNG_FIRST
}

// 启动时解析一次的快照（同批次共享）
record WebSearchSnapshot(WebProviderOrder order, boolean webAllowed, Long briefId, int maxResults)

// 路由（@Component,注入 TavilySearchTool + SearxngSearchTool）
WebSearchOutcome search(String query, int maxResults, WebSearchSnapshot snapshot)
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
- **sourceId 稳定且可回溯**：`W1,W2…` 按本次输入顺序；URL 规范化后去重（fragment 变体视为同条）。事实只能引用本次输入的 sourceId。
- **后验校验**：WEB 事实的 sourceId 未知 / URL 不匹配 / provider 不匹配 → 剔除并转 gap，**不整条 agent 失败**；KB 事实不受此校验。**凡携带 `url` 或 `sourceId` 的事实一律按 WEB 声明校验**（防模型漏标 `type` 而自造 URL 混入），通过后 `type` 归一为 `WEB`（否则 FactSheet 默认按 KB 0.9 采信）。
- **降级原因不含异常原文/密钥**：异常路径只记类型化 `ERROR`，不回传 `e.getMessage()`（可能含密钥/URL）。
- `webCount` = 实际接受的 WEB 结果数（不再用事实条数 `webCalls`）；`search.resultCount` 与之同口径，**LLM 汇总失败走 `rawFallback` 时不归零**（搜索发生的事实不变），此时 `search.fallbackReason=LLM_FALLBACK`，provider 层 `attempts` 的 `ok`/`fallbackReason` 保持原样，两类失败不混淆。
- `available()` 语义不变（仅配置就绪，无失败闩锁）；`toolHealth` 三键值域不变，`webStrategy`/`webProviderOrder` 为增量字段。

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
- **归并不得破坏数值回查**：代表 fact 的 `key/value/claim` 覆盖全部被并 claim 的数值（数值签名相等保证），`DeepWriterService.verifyNumbers` 以 `sheet.toString()` 为 haystack 仍命中。

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
- **不改口径/不改红线**：`webCount`/`search.resultCount` 降级不归零、`search.fallbackReason=LLM_FALLBACK`；`available()` 无闩锁、`toolHealth` 三键与优先级、`research_notes` 主字段集与状态值域均不变。

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
record EmbedStats(int total, int success, int failed)          // 对标 KbDocService.EmbedStats
void embedOne(ImageAssetEntity img)                            // 先物理删旧向量再插（幂等）
void embedQuietly(Long imageId)                                // 吞全部异常仅 warn，绝不影响图片入库
EmbedStats rebuildAll() / rebuildMissing()                     // 全量 / 仅补 LEFT JOIN 差集
void deleteByImageId(Long imageId)                             // 删图联动
List<ImageSearchHit> searchImages(String query, Integer topK, Double minScore, List<String> tags)
```

### 3. Contracts
- **同空间硬约束**：`sparkora_image_embedding.embedding VECTOR(1024)` + HNSW `vector_cosine_ops`，复用 `EmbeddingClient`（Qwen3-Embedding-8B）。**不新增 embedding 客户端、不存模型名/维度列**——列存模型名只会制造「不同模型混检索」的错觉。
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
- **派生建议零副作用**：`suggest` 只读正文 + 图库（`searchImages`），**不写 `content_md` / `body_image_ids`**；服务**不得注入 `ImageService`**（否则 `modifyBodyImage`/`setCover` 写路径可达）。生成建议前后 DB 的 `content_md`/`body_image_ids` 必须完全一致（硬验证项）。
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
- Bad: 因候选分数高就自动插入；或在建议服务里注入 `ImageService` 顺手写 `body_image_ids`。

### 6. Tests Required
- `AnchorExtractorTest`：分段/前言/H1 不计入、纯列表/引用/代码块/图片/过短跳过、上限截断、markdown 清洗、指纹稳定性与超长窗口、null/空/非法上限边界。
- `IllustrationSuggestionServiceTest`：前置校验 4 例、按锚点组装与 topN/门槛透传、无候选不出现、单锚点失败跳过、已忽略过滤、`dismiss` 幂等与唯一冲突、**零副作用断言**（写方法从未被调用 + 反射断言未注入 `ImageService`）。

### 7. Wrong vs Correct
#### Wrong
```java
// suggest 里顺手写 body_image_ids：违反「建议零副作用」硬约束
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
- **检索 SQL 已 SELECT 的列要在读行处补读**：统一检索 SQL 早已 `SELECT docId`，但 `retrieveUnified` 读行时丢弃 → 消费方拿不到定位 id。加字段是「读侧一行」的事，**不动 SQL、不动配额与排序**（`searchTopKUnified` 是三域候选窗口隔离的关键资产）。
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

### Common Mistake: @ConfigurationProperties 前缀漂移导致整块配置静默失效

**Symptom**: 按文档配置了 `SEARCH_WEB_ENABLED=false` 或 `sparkora.deep.tavily-api-key`，但行为毫无变化（开关关不掉、key 字段恒空）；应用不报错、启动正常，极难发现。

**Cause**: `DeepProperties` 声明 `prefix = "sparkora.ai.deep"`，而 `application.yml` 把 `deep:` 写在 `sparkora:` 下（`sparkora.deep`，`ai` 的**兄弟**节点）→ Spring relaxed binding 找不到对应前缀，整块字段保持 Java 默认值，**不抛异常**。此前仅因 `TavilySearchTool` 构造时绕过 Spring 直读环境变量 `TAVILY_API_KEY` 才碰巧可用，掩盖了缺陷。

**Fix**: 令注解前缀与实际 YAML 路径一致。本仓库域配置类惯例为 `sparkora.<domain>`（`NewsProperties`/`CarProperties`/`WenyanProperties`/`QiniuProperties`/`ImageProperties`），故 `DeepProperties` 取 `sparkora.deep`。

**Prevention**: 新增/改动 `@ConfigurationProperties` 时，用 `grep -rn "prefix = \"sparkora" src/main/java` 对照 `application.yml` 的缩进层级逐一核对；字段绑定不能只靠"环境变量兜底"证伪，需构造 `ApplicationContextRunner` 或启动后读取 `getXxx()` 实测非默认值。
