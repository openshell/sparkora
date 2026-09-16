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
// Citation{source, modelName, chunkType, score, chunkText}; source∈{CAR,KB,NEWS}
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
