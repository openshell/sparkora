# 知识库检索（RAG 必查 + 降级可见）

> 回链：[系统说明总览](../README.md)

职责：定义生成链路（简报/正文/深度研究）中知识检索的**必查语义、检索状态枚举、门槛配置、引用明细与诚实边界**。跨模块横切契约（检索状态写入简报与版本，展示在前端）。

> 三域数据基座与统一检索实现见 [knowledge/kb.md](knowledge/kb.md)（含车型域/通用域/新闻域的库表与切块）；生成注入开关见 [settings.md](settings.md)。

---

## 1. 必查 + 降级可见（S6.1，2026-09-03）

**语义**：项目**已关联车型**时，生成简报与生成正文**必须发起**一次车型知识库 RAG 检索；检索失败或整体置信度过低**不阻断生成**（硬阻断会把创作绑死在 embedding 服务可用性上），但必须降级可见——AI 被要求在 `factRisks` 标注数据缺失，检索状态随产物落库并展示于前端。未关联车型视为「已查、无知识对象」，不算失败。

> S8 起「项目关联车型」不再是检索门禁（未关联也全库检索），降为**写作锚点加权**（见 [knowledge/kb.md](knowledge/kb.md)）。四态语义不变。

---

## 2. 检索状态枚举

`brief.rag_status` / `version.rag_status`，VARCHAR(20)：

| 状态 | 含义 | prompt 注入 | 前端展示 |
|---|---|---|---|
| `OK` | 命中且最高相似度 ≥ 整体门槛 | 权威数据注入，严格依据不得编造 | 「知识库 · 已引用」(绿) |
| `LOW_CONFIDENCE` | 有命中但最高相似度 < 整体门槛，**全部抛弃** | 不注入；提示 AI 不得臆造参数、`factRisks` 标注(建议 high) | 「知识库 · 低置信已抛弃」(橙)；版本卡片加「参数未经知识库核实」 |
| `FAILED` | 检索异常（embedding 服务等），**降级继续** | 不注入；要求 `factRisks` 标注数据缺失(建议 high)，不得臆造参数 | 「知识库 · 检索失败·已降级」(红)；版本卡片同上 |
| `NO_KNOWLEDGE` | 无车型关联对象或逐块过滤后无命中 | 不注入、不提示（与 S6 现状一致） | 「知识库 · 未引用」(灰) |
| `DISABLED` | **系统设置停用知识库（09-09-brief-gen-redesign，2026-09-09 增补）**：设置页 `kbEnabled=false` 时本地检索不发起 | 不注入任何本地知识块；外部搜索按 `webSearchEnabled` 独立启用（优先外部资料）；双关时 prompt 明确要求标注「未检索任何外部资料,数据未核实」 | 「知识库 · 知识库已停用(全局设置)」(灰)；不得与 `NO_KNOWLEDGE` 混淆 |

- `FAILED` 优先级高于其余状态：多车型检索时任一车型异常即标 `FAILED`（其余车型照常尝试）。
- 抛弃/失败**不得与「无命中」混淆**：`LOW_CONFIDENCE`/`FAILED` 必须显式落库，前端据此提示。
- `DISABLED` 是**主动停用**语义（设置页可随时切回），与失败/低置信的被动降级不同；仅深度链路产生（快速模式已下线）。

**字段级**：`sparkora_article_brief.rag_status`、`sparkora_article_version.rag_status` — `VARCHAR(20)`，可空（历史行为数据为 NULL，前端不展示）；GET brief/versions 响应自然携带该字段，无独立接口。

---

## 3. 知识引用明细（R3，2026-09-05 增补）

`sparkora_article_brief.rag_citations`、`sparkora_article_version.rag_citations` — `TEXT`（JSON 数组）：

```
[{source:"CAR|KB|NEWS", modelName, chunkType, score, chunkText, docId, sourceType?, category?, url?, authorityTier?}]
```

- `docId` 为 09-15 qa-auto-illustrate 起的可空域内块 id：`CAR=car_chunk.id` / `KB=kb_chunk.id` / `NEWS=news_doc.id`。
- **10-09 M 增量**：`url`/`authorityTier` 为可空字段（本地自建信源 SOURCE 用）——`url`=来源内容原文绝对 URL（供 F-R3 跨源同 URL 去重）、`authorityTier`=信源权威档 `official|industry|media|ugc`（供 F-R4 分档）。同样贯通 `UnifiedHit`→`Citation`→`SearchHit.source(...)`；`Citation` 是送到 `KnowledgeSearchTool` 的实际载体。BYD/无 URL 来源/缺档为空（走 F 既有兜底：不去重、保守档 0.7）。
- **10-05 E 增量**：`sourceType`/`category` 为可空的 NEWS 域内来源细化字段（BYD 命中 `byd-news`/`官方新闻`；用户采集源 `user-source`/`销量数据`…）。**同时贯通 `UnifiedHit` 与 `Citation` 两级 record**——`Citation` 是送到 `KnowledgeSearchTool` 的实际载体，供下游 F 判 SOURCE。旧前端/调用方不读不报错（纯增量兼容）。
- **10-05 F 增量（F-R1）**：`KnowledgeSearchTool` 按 `Citation.sourceType` 分流——NEWS 域 `sourceType` 非空且非 `byd-news`（用户采集源）输出 `SearchHit.type=SOURCE`（携 `sourceType`/`crossCounted`/`url`/`authorityTier`，权威分档由 fact 层 `FactSheetService` 处理）；`byd-news`/缺省仍 `KB`（BYD 逐位等价）。事实手册融合（本地优先/同 URL 去重/独立交叉）见 [brief-generation.md §5](brief-generation.md)。
- 检索 `OK` 且有命中时随生成落库（与注入 prompt 的 context 同源，上限 24 条、单条文本截断 120 字符，序列化超 8000 字符整体置 null）；`LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE` 为 null。**版本链路由 `VersionService` 写入；`brief.rag_citations` 的写入方（FAST 简报 `BriefService.generate`）已于 2026-09-26（R6）删除，深度简报链路不写该列（引用面板改由 `fact_sheet` 派生，见下条）**。
- 前端简报页「知识库引用」区（`CitationList` 组件）与版本卡片「引用 N」标签（点击展开）展示；空态按 `ragStatus` 显示降级文案（**前端不读 `docId`，纯增量不影响展示**）。
- **WEB 搜索来源并入（2026-09-05 增补）**：深度模式简报页的引用面板另将 `brief.fact_sheet.entries` 中条目派生为引用条目并入展示——**全部类型（KB/WEB/MULTI/SOURCE，2026-09-06 增 MULTI/10-05 F 增 SOURCE）**：KB 条目（置信 0.9/0.6）与本地 `rag_citations` 同款「通用知识」标签展示（修复「深度模式内容引用了知识库、页面却显示未引用」的展示断链，项目 29 实测）；WEB 带域名、MULTI 标多源交叉、SOURCE 标「本地信源」；上限 24 条。快速模式无 `fact_sheet`，行为不变（**2026-09-09 注：快速模式已下线，本句仅存量语义**）。版本卡片保持「本版生成时的本地知识库检索」语义，不重复展示 WEB 引用。

---

## 3.1 检索重排（A rerank，10-03-a-rerank）

- **契约**：`com.sparkora.car.service.Reranker#rerank(query, candidates, keepTopN)` →
  `List<UnifiedHit>`；实现 `LlmReranker` 用 `AiClient.structured` + `prompts/rag/rerank-system.st`
  （`RerankOrderDto{order}`，序号 0 起）让模型回吐「相关性降序」。
- **插入点**：`CarRagService.retrieveForGeneration` 候选合并去重后、锚点加权/配额**之前**。
  **只改顺序、不改分数**——`maxScore`/`minScore`/`rejectScore` 与四态判定基于原分，重排不改变四态与候选集。
- **参与集**：按原始分数降序的前 `AI_RAG_RERANK_TOPN` 个送入模型（单条文本截断 300 字），其余原序追加。
- **后验校验**：越界/重复下标丢弃、缺项按原序补尾，保证返回集合与输入元素一一对应（仅顺序变）。
- **best-effort 降级**：开关关闭 / order 空或全非法 / 超时 / 调用异常 → **原序返回 + warn，绝不抛出、绝不阻断生成**
  （超时在独立虚拟线程按 `AI_RAG_RERANK_TIMEOUT_MS` 兜底）。
- **配额/排序衔接**：重排后配额选择与最终排序按重排名次（`rerankRank` 位置映射，boost 重建对象后按位置对应）；
  关闭时回退既有「按分数降序」，与改造前逐字等价（零回归）。
- **评估**：`.trellis/tasks/10-03-a-rerank/research/rerank-ab.md`（代表 query MRR 0.37→1.00、top-1 0%→100%，
  harness `rerank_ab_probe.py`）。

---

## 3.2 外部搜索 provider 策略与降级链（10-04-serper-provider A；10-04-web-fanout-merge B 增多源聚合）

深度研究的 WEB 检索由 `WebSearchRouter`（`com.sparkora.deep.search`）按 `WebSearchSnapshot` 的策略执行；
provider 未配置跳过（`UNCONFIGURED`）、异常/超时/空结果/结果全部无有效 URL 记降级原因后尝试后备源。
逐次尝试明细落 `research_notes[].search.attempts`（每项含 `provider`/`resultCount`/`latencyMs`/`fallbackReason`/`ok`/`witnessTotal`/`usedEndpoint`(可空，10-05 增量)）。

- **两策略**（`SearchStrategy`，部署级 `sparkora.deep.web-fanout` ← `.env DEEP_WEB_FANOUT`，默认 `first_hit`）：
  - **`first_hit`（默认）**：首个产出有效命中即采信并停止（不让付费 provider 无条件重复调用）——现有部署逐位等价。
  - **`primary_fanout`（B）**：`primary = order ∩ DEEP_WEB_PRIMARY_PROVIDERS`（默认 `TAVILY,SERPER,SEARXNG`：付费源互补交叉为主、SEARXNG 亦参与召回）；`primary` 为空 → 整体回落 `first_hit`；`primary` 组虚拟线程并行调用（各取满 `maxResults`）→ 跨源合并；`primary` 全空时才对 fallback 组（order 中不在 primary 集者）按短路兜底。
- **provider 值域**（`WebProvider`）：`TAVILY` / `SEARXNG` / `SERPER`（10-04 A 追加末尾）。
- **顺序配置**：部署级 `sparkora.deep.web-provider-order`（`.env DEEP_WEB_PROVIDER_ORDER`），默认 **`TAVILY,SEARXNG`**（`TAVILY_FIRST`，零回归前提）；运行时 ADMIN 设置页可覆盖（优先级更高）。
- **策略标签**（`WebProviderOrder.strategyLabel()`，经 `/deep/status` 的 `webStrategy` 透出）：`TAVILY_FIRST` / `SEARXNG_FIRST`；`web-fanout=primary_fanout` 或顺序含 `SERPER` 时回落 `PRIMARY_FANOUT`（避免首元素非 SEARXNG 被误标 `TAVILY_FIRST`）。
- **跨源合并**（`WebResultNormalizer.merge`）：按 `normalizeUrl` 去重（首次出现的 provider 胜出）；`witnessCount`/`witnessEndpoints` 仅观测，**不参与** `sourceCount`/confidence；order 位次稳定排序；截断到 `maxResults`（不放大）；**`sourceId` 合并后统一分配 `W1..Wn`**（保证 `validateFacts` 的 URL+provider 严格比对不误剔）。
- **SearXNG 质量门**（B-R2a，仅作用进 primary 组的 SearXNG）：`DEEP_WEB_DENY_DOMAINS` 黑名单 + `/video/`、`link?url=` 非正文页过滤 + 空白/非法 URL 丢弃；`DEEP_WEB_ALLOW_DOMAINS` 命中者放行。不提升独立交叉计数。
- **Serper 认证与端点**：Header `X-API-KEY`（**非** body `api_key`，与 Tavily 不同）；端点 `DEEP_SERPER_API_BASE_URL` 可配置（官方 `https://google.serper.dev` / 中转 `https://search.604020.xyz/serper`，路径前缀保留）。垂直 `web`→`/search`、`news`→`/news`（`news` 才有 `date`/`source`）见 [brief-generation.md §4](brief-generation.md)。
- **降级链位置**：`SERPER` 未配置时 `toolHealth.SERPER=UNCONFIGURED` 且被路由跳过，Tavily/SearxNG 行为不受影响；顺序含 SERPER 但不配置 key 时，等价于该 provider 不存在。
- **Tavily 双端点 failover（10-05-tavily-endpoint-priority）**：`TavilySearchTool` 内部持 `relay`(中转)与 `official`(官方)两个端点，**对外 `name()` 均为 `TAVILY`**——同一 URL 被两端点命中也只算 1 源（`FactSheetService` 按 `url+modelName` 去重），不抬升 `MULTI`。`search` 按 `relay → official` 顺序、每端点每轮一次；中转有效命中即采用、不调官方；失败/超时/空/低质则切官方；两都不可用返回空。**端点独立 `RestClient`**：relay read 默认 8s / official read 默认 30s / connect 统一 5s；`extract` 独立 read 默认 15s（只走官方，不被 search 超时牵连）。端点级质量门（非法 URL / 噪声域 `DEEP_TAVILY_DENY_DOMAINS` / 空 title+content）决定是否切端点；结果级 `DEEP_TAVILY_MIN_CONTENT_CHARS`（默认 0=off）过滤低质。`Attempt.usedEndpoint` 观测实际端点。
- **配置项**：`SERPER_API_KEY`/`DEEP_SERPER_API_KEY`、`DEEP_SERPER_API_BASE_URL`、`DEEP_SERPER_GL`/`DEEP_SERPER_HL`、`DEEP_WEB_VERTICAL_NEWS`、`TAVILY_API_BASE_URL`（官方）、`DEEP_TAVILY_API_BASE_URL`/`DEEP_TAVILY_API_KEY_HIKARI`（中转）、`DEEP_TAVILY_RELAY_READ_TIMEOUT_MS`/`DEEP_TAVILY_OFFICIAL_READ_TIMEOUT_MS`/`DEEP_TAVILY_EXTRACT_READ_TIMEOUT_MS`、`DEEP_TAVILY_DENY_DOMAINS`/`DEEP_TAVILY_MIN_CONTENT_CHARS`、`DEEP_WEB_FANOUT`/`DEEP_WEB_PRIMARY_PROVIDERS`/`DEEP_WEB_DENY_DOMAINS`/`DEEP_WEB_ALLOW_DOMAINS`、`DEEP_SOURCE_AUTHORITY_ENABLED`（10-05 F:本地信源权威分档,默认 false=保守档 0.7）（字段级见 [brief-generation.md §8](brief-generation.md)）。

---

## 4. 检索门槛（粗调值，**待按真实 query 分数分布校准**；`REJECT` 须 ≥ `MIN`）

| `.env` 变量 | 默认 | 代码用途 |
|---|---|---|
| `AI_RAG_MIN_SCORE` | `0.3` | 逐块相似度门槛，低于不注入（沿用 S6 原硬编码值） |
| `AI_RAG_REJECT_SCORE` | `0.5` | 整体置信度门槛：全部命中块的最高相似度低于该值 → `LOW_CONFIDENCE` 全部抛弃 |
| `AI_RAG_KB_TOPK` | `4` | 通用知识库生成检索注入块数上限（与车型域配额独立；见 [knowledge/kb.md](knowledge/kb.md)） |
| `AI_RAG_KB_ENABLED` | `true` | 通用知识库总开关，false 时统一检索排除 KB 块（见 [knowledge/kb.md](knowledge/kb.md)） |
| `AI_RAG_ANCHOR_BOOST` | `1.15` | 统一检索锚点车型块分数加权系数（见 [knowledge/kb.md](knowledge/kb.md)） |
| `AI_RAG_NEWS_TOPK` | `4` | 新闻域生成注入块数上限（`0` 关闭 NEWS 注入；不受 KB 开关控制；见 [knowledge/news.md](knowledge/news.md)） |
| `AI_RAG_SOURCE_TOPK` | `0` | **10-05 E**：NEWS 域内用户采集源（`sourceType=user-source`）独立注入块数上限（`0`=关闭，零回归；BYD 官方新闻走 `AI_RAG_NEWS_TOPK`） |
| `AI_RAG_RERANK_ENABLED` | `false` | **A rerank（10-03-a-rerank）**：LLM 重排总开关；关闭时检索行为与现状逐条一致（零回归） |
| `AI_RAG_RERANK_TOPN` | `20` | 参与 LLM 重排的候选数上限（按原始相似度取前 N，其余原序追加） |
| `AI_RAG_RERANK_TIMEOUT_MS` | `10000` | 单次重排超时预算（ms，超时回退原序；比 `AI_TIMEOUT_MS` 短） |
| `AI_IMAGE_MIN_SCORE` | `0.3` | 图片语义检索门槛（独立入口；见 [image.md](image.md)） |
| `AI_EMBEDDING_DIM` | `1024` | 向量维度校验：`EmbeddingClient.embedList` 返回长度不符即抛 `AiException`（09-27；见下节） |

---

## 4.1 向量模型防护（09-27 P1-⑧）

- **配置单一来源**：`AI_EMBEDDING_MODEL`（`.env` → `sparkora.ai.embedding-model` → `AiProperties.embeddingModel`）；维度 `AI_EMBEDDING_DIM`（默认 1024，与 DDL `VECTOR(1024)` 一致）。
- ~~**4 张向量表加 `embedding_model VARCHAR(100)`**（Flyway V3）~~（**10-03 E6 旧表退役**：等价为单表 store `metadata.embeddingModel`，写入 `upsert` 盖名、检索/统计/差集 filter 带 `embeddingModel`）。
- **写入盖名**：4 条写路径（CAR/KB/NEWS/IMAGE）统一盖 `EmbeddingClient.modelName()`（store metadata `embeddingModel`）；**检索过滤**按当前模型。换模型后旧模型行自动失效（可见降级而非静默混空间），重嵌后新向量自动生效。
- **维度校验 fail-fast**：`EmbeddingClient.embedList` 校验返回长度 == `AI_EMBEDDING_DIM`，不符抛 `AiException`（中文提示含实际/期望维度与模型名），首次写入或查询即暴露。
- **启动对账**：`EmbeddingModelReconcileRunner`（`@Order(60)`，Flyway 之后）查单表 store `GROUP BY metadata.domain/embeddingModel`，存在非当前模型的行时 WARN（列出域+模型名+条数，提示重嵌），异常仅 warn 不阻断启动。
- **重嵌入口**：CAR `POST /api/car/models/rebuild-all`、KB `POST /api/kb/docs/{id}/rebuild`、NEWS `POST /api/news/{id}/rebuild`（09-27 新增）、IMAGE `POST /api/images/embeddings/rebuild`；跨域一键重嵌编排 out of scope。

## 4.2 向量检索层（10-03 E1：Spring AI PgVectorStore 单表，阶段 A）

> 迁移子任务 E1（`.trellis/tasks/10-03-e1-pgstore-migrate`）。旧 4 表**已于 10-03 E6 删除**（见 §11）。

- **拓扑**：单张 `vector_store`（Flyway `V5__pgvector_store.sql`；`id uuid / content text / metadata json / embedding vector(1024)`；HNSW cosine + metadata GIN）。`initialize-schema=false`（由 Flyway 建表）；维度/距离/索引由 `spring.ai.vectorstore.pgvector.*` 约定（1024 / cosine / hnsw）。
- **metadata 契约**：`{domain(CAR|KB|NEWS|IMAGE), refId(域内 id), modelId(仅 CAR), chunkType, name, active, embeddingModel}`；`content` = `chunk_text`（IMAGE 域 = 旧 `source_text`）。**10-05 E 增量**：NEWS 域行另带 `sourceType`（`byd-news`/`user-source`/`gasgoo-*`）、`category`（官方新闻/销量数据/…）、`publishDate`（ISO，可空）；由 `NewsDocService`（BYD，写 `byd-news`）与 `SourceDocService`（通用信源）写入，V14 回填存量（仅补缺键、**不动 id**）。**10-09 M 增量**：NEWS 域行另带 `url`（来源原文绝对 URL，通用信源按栏目 `detail_base_url` 补全、BYD 按 `NewsProperties.detail_base_url` 补全；可空）与 `authorityTier`（`official|industry|media|ugc`，BYD 固定 `official`、通用信源取 `SourceEntity.authorityTier`，缺省不写）；V16 回填存量（仅补缺键、**不动 id/embedding**）。
- **id 确定性**：`UUID.nameUUIDFromBytes(domain+":"+refId)`，供 upsert/delete/setActive 按 id 定位（store 无按 metadata 更新 API）。
- **读路径**：`CarRagService` 经 `ai.vector.SearchStore`；候选窗按域隔离复现——CAR+KB 合并一次 `similaritySearch` + NEWS 独立一次（`domain in [...] && active && embeddingModel`）+ Java 合并；`similarityThreshold=0`，门槛仍在 Java 侧用 `ragMinScore`/`ragRejectScore` 判定。`ImageEmbeddingService.searchImages` 走 IMAGE 域（可选 `refId ∈ 白名单`）。
- **活表同步层**：`active` 由写路径 `VectorStoreService.upsert/setActive/deleteByRef` 维护（软删/停用/重建/删父联动）；`CarChunkService`/`KbDocService`/`NewsDocService`/`ImageEmbeddingService` 的 persist/delete 同步单表。
- **回填**：`VectorStoreBackfillRunner`（`@Order(50)`，守护线程，幂等差集）从旧表复用已算好的向量搬入 store；**回填完成后已随 E6 退役**（旧表 DROP）。
- **阶段 A 对拍**：`.trellis/tasks/10-03-e1-pgstore-migrate/research/parity-A.md`（7 query 集 × CAR+KB/NEWS/IMAGE，候选集/分数/排序逐条一致）。
- **契约不变**：`RagResult`/`Citation`/`rag_status`/`rag_citations` 与前端交互零变更。

---

## 5. 检索策略升级（S6.2，2026-09-03；修复海狮08 文章价格/续航错误暴露的检索精度缺陷）

| 缺陷（S6.1 现状） | S6.2 修复 |
|---|---|
| 「XX参数表及配置表」零信息表头块（仅标题行）得分最高挤占 topK | 切块层：有效参数 <2 的分组不入库（`CarChunkService`）；检索层兜底丢弃仅含标题行的参数块 |
| 权益块与主题措辞相似挤占配额 | 分层配额 `applyQuota`：PARAM_GROUP/MODEL_INFO 优先，RIGHTS/FEATURE 合计 ≤ 总配额 1/3 |
| 单查询整句 topic 与参数级子问题不对齐 | 参数级子查询 `deriveSubQueries`：query 含价格/续航/油耗等参数词时逐词派生子查询，主/子查询结果按 `chunkText` 去重合并 |
| AI 在知识块未覆盖的参数处编造数值 | 覆盖度声明：`RagResult.coveredText` 携带「参数名→值」清单注入 prompt；清单外参数禁止写具体数值，要求定性表述 + `factRisks` 标注 |

- 修复生效前提：**重新同步车型**（旧表头块仍在库中，检索层已兜底过滤，但建议重同步清理）。
- 生成时后端须运行 S6.2 代码（历史教训：S6.1 合入后进程未重启，生成仍走旧链路）。

---

## 6. 数据清洗链路治理（S6b，2026-09-04；kb-clean-audit 任务）

| 项 | 契约 |
|---|---|
| 清洗方式三态 | `car_param_clean.clean_method` ∈ `RULE`(规则引擎命中) / `AI`(LLM 兜底) / `FALLBACK`(双失败 STRING 原样,需人工关注)；**不再出现把兜底误标 RULE 的旧行为**，旧数据需重清洗刷新口径 |
| 清洗统计 | `CleanStats`(RULE/AI/FALLBACK 计数)：随 `cleanForModel` 日志汇总、同步任务聚合日志(`fallbackPct`)、`GET /api/car/models/{id}/clean-stats` 按 method/valueType 分组查询(三角色可读) |
| PARAM_GROUP 块首行 | 固定 `车型：<全名>`（消除 EV/DM-i 同系跨版本检索混淆，即 S6.2 P1 遗留项）；块行文本 `参数名：清洗值` |
| 清洗值展示 | 优先 `car_param_clean.param_value`，缺失回退 `raw_value`；NUMBER/LIST 类型且值不含单位时拼接单位（如 `2820mm`）；清洗与原始值均缺省跳过该行 |
| 向量重建 | `rebuildForModel`：embedding 并发（固定线程池 ≤4）+ 单块失败重试 1 次；完成日志输出「成功 X/失败 Z」，失败块记 `sortOrder`（消除静默丢块） |
| 批量重建/对账 | `POST /api/car/models/rebuild-all`（ADMIN/EDITOR）逐车型重建汇总；`GET /api/car/models/vector-stats`（三角色）返回 `{modelCount, chunkCount, embeddedCount, missingCount, missingTopN}`（仅统计 `deleted=0`；2026-09-04 实测全库 380/380 缺失 0；**10-03 E6 起 embeddedCount 改查单表 `vector_store`**——见 §11） |
| 入库去重 | `persistVersions`/`persistParams` 同名版本/同名分组去重（官网接口历史上曾按模块重复推送，防再发）；重同步车型39 复测 clean 与参数版本值 1:1 精确对齐 |
| AI 兜底空值防线 | `AiParamCleaner` 对 AI 返回 value 空白视为失败返回 null（走 FALLBACK 兜底），「无值清成空串」不再落库 |
| 摊平核查结论 | 6432 清洗行疑云 = 历史上游重复推送 + `@TableLogic` 逻辑删先清后插堆积（非清洗层摊平）；详见 `archive/2026-09/09-04-clean-followup/research/flatten-findings.md` |
| 生效前提 | 切块口径变更**仅对新重建的车型生效**；存量 56 车型需逐个重建向量（体检发现 408/1293 块历史向量缺失，重建一并补齐） |

- 体检报告（量化）见 `.trellis/tasks/09-04-kb-clean-audit/research/clean-audit-report.md`：规则引擎覆盖 98.8%+（口径可信度受旧误标影响，重清洗后复测）；AI 兜底 9 行中 4 行「无值清成空串」属错误输出（P2 建议：AI 返回空值视为失败不落库）；**31.6% 文档块无向量（历史静默丢失）**；单车型 39 清洗行 6432（占 60%）疑似多版本摊平，待核查。

---

## 7. 诚实边界

相似度衡量**相关性**而非事实正确性——知识库本身存错的数据会以高相似度被当作权威注入；防错依赖入库源头（比亚迪同步 + 人工清洗），检索门槛不承诺拦截知识库错误数据。

---

## 8. 关键实现路径

- 后端：`com.sparkora.car`（`CarRagService.retrieveForGeneration`、`CarChunkService` 切块/配额/子查询/覆盖度）、`ai.vector.VectorStoreService`（单表 store 读写/同步/统计/差集，10-03 E1/E6）、`ai.EmbeddingClient`、`ai.RagStatus`、`service.VersionService`（写入 `rag_status`/`rag_citations`；`service.BriefService` 的 FAST `generate` 曾写入 `brief.rag_status`/`rag_citations`，该路径已于 2026-09-26（R6）删除，`BriefService.citationsJson` 仍被 VersionService 复用）、`deep.service.FactSheetService`（WEB/KB 冲突裁决）。**旧表路径 `mapper.{CarDoc,KbChunk,NewsDoc,Image}EmbeddingMapper` 已于 10-03 E6 删除**（含 `searchTopKUnified`/`countByModel`/`findImageIdsWithoutEmbedding`），检索/对账/差集全部走单表 store。

---

- 前端：`views/project/deep/CitationList.vue`（引用面板，CAR/KB/NEWS/WEB/MULTI 分支）、`views/project/StepVersions.vue`（版本卡片「引用 N」/降级提示）。
- 表：`sparkora_article_brief.rag_status/rag_citations`、`sparkora_article_version.rag_status/rag_citations`。

---

## 9. 切块滑动重叠与阶段 B 重嵌（10-03 E2）

- **重叠能力**：`com.sparkora.ai.TextChunker.chunk(..., int overlapChars)`（6 参重载）；旧 5 参委托 `overlapChars=0`（逐块等价旧行为，向后兼容）。KB/NEWS 服务层显式传 `DEFAULT_OVERLAP_CHARS=60` 启用；**CAR（参数分组块）与 IMAGE（`ImageEmbeddingTextBuilder`）不走 TextChunker，切块形态不变**。
- **重叠策略**：相邻产出块（短段落分块 + 超长段句读合并两路）中，前块 >60 字则取「尾部片段」（≤60，优先从片段内首个句读分隔符之后开始对齐句读边界）作后块前缀；前缀+本块仍须 ≤500（放不下则不重叠）；不整块重复、不增块数。
- **全库重嵌（阶段 B）**：经既有 rebuild 入口重建 4 域（旧 4 表保留、不新增破坏性脚本）——CAR `POST /api/car/models/rebuild-all`、KB `POST /api/kb/docs/{id}/rebuild`、NEWS 逐篇 `POST /api/news/{id}/rebuild`、IMAGE `POST /api/images/embeddings/rebuild`。实测（2026-10-03）：CAR 56/56、KB 3/3、NEWS 168/168、IMAGE 180/180 成功；对账 `embeddedCount == chunkCount`（380/1341/3/180），store 合计 1904 行全部 `active=true` 且 `embeddingModel=Qwen3-Embedding-8B`。
- **阶段 B 验收**：8 个代表 query（KB/NEWS/CAR/锚点/混合）前后 `ragStatus` 均 `OK`（不恶化）、候选条数与来源分布逐 query 不变、分数分布 Δ≤0.02；NEWS 1173 相邻块对中 58.5% 建立 15–60 字重叠，跨块边界语义补齐、边界 query 分数微升。详见 `.trellis/tasks/10-03-e2-chunk-overlap/research/parity-B.md`。

---

## 10. 覆盖度三域统一 + 嵌入缓存（10-03 E5）

### 覆盖度（coveredText）

- **语义**：`RagResult.coveredText` 由「仅 CAR PARAM_GROUP 参数摘要」扩为 **CAR + KB + NEWS** 的「已覆盖事实」声明（IMAGE 不参与生成注入）。注入模板 `prompts/version/rag-covered.st` 文案**不变**（「仅可引用这些数值，清单外禁止具体数值」）。
- **CAR（保持）**：`PARAM_GROUP` 块仍走 `extractParamSummary`（`参数名→值`，跳过 有/无/可选装），逐字不变。
- **KB/NEWS（新增）**：抽取块内**数值事实**，格式 `〔通用知识：<标题>〕<数值,...>；〔官方新闻：<标题>〕<数值,...>`。数值口径复用 `com.sparkora.ai.NumericSignature`（`ClaimSimilarity.numberValues` 上移的中立实现，C7 正文数值回查/claim 归并**同源**）——`1200` 与 `12000` 不误配；无标题降级 `〔通用知识：〕`；无数值块不产出覆盖段。
- **去重 + 截断**：整体段去重（保序）、总长上限 `COVERED_MAX=400`（沿用既有口径），单段数值上限 12。
- **CAR-only 回归锁**：仅 CAR 命中时 `extra` 为空，`coveredText` 与改造前**逐字等价**（`CarRagServiceTest.E5_coveredText_CARonly_与改造前逐字等价`）。
- **实现**：`CarRagService.coverageSegment` / `buildExtraCoverage`（纯静态可单测）；`NumericSignature`（`com.sparkora.ai`）为数值签名单一实现，`com.sparkora.deep.service.ClaimSimilarity.numberValues` 委托之，行为逐字不变。

### 嵌入缓存（写入侧去重）

- **表**：`sparkora_embedding_cache`（Flyway `V8__embedding_cache.sql`），主键 `(content_hash CHAR(64), embedding_model VARCHAR(100))`，`embedding TEXT` 存 pgvector 字面量（缓存不做 ANN，规避 vector 类型映射）。
- **读**：`EmbeddingClient.embedForIndex(String)` —— sha256(text) 命中 `(hash, 当前模型)` 直接返回缓存字面量（**不网络调用**）；未命中走 `embed(text)` 后 best-effort 写缓存（`INSERT ... ON CONFLICT DO NOTHING`，独立 `REQUIRES_NEW` 事务，失败仅 warn，绝不污染调用方事务）。缓存未注入/哈希失败 → 退化直调 `embed`。
- **写路径**：`EmbeddingBatchRunner.processOne` 改调 `embedForIndex`（CAR/KB/NEWS 三域文本块经此写入，统一生效）。**查询路径 `embed(String)` 保持无缓存**（查询文本每次不同）。IMAGE 单图嵌入走 `ImageEmbeddingService.embedOne` 直调 `embed`，未接入缓存（图片嵌入文本含逐图字段、重复率可忽略）。
- **模型切换安全**：键含 `embedding_model`，换模型天然 miss、绝不复用旧模型向量（AC3）。
- **回退**：删缓存表 + revert；`embedForIndex` 退回直调 `embed`。

---

## 11. 旧向量表退役（10-03 E6）

- **单一只真源**：4 域向量唯一真源 = 单表 `vector_store`（`metadata.domain`），旧 4 张 `sparkora_{car_doc,kb_chunk,news_doc,image}_embedding` 表**已由 Flyway `V9__drop_legacy_embedding_tables.sql` 物理删除**。写路径（`CarChunkService`/`KbDocService`/`NewsDocService`/`ImageEmbeddingService`）不再写旧表，只写 store。
- **读路径改 store**：
  - `GET /api/car/models/vector-stats`：`chunkCount` 主表权威（未逻辑删除块数），`embeddedCount` 走 `VectorStoreService.countCarEmbeddedByModel`（`metadata->>'domain'='CAR' AND metadata->>'embeddingModel'=当前模型` 按 `modelId` 聚合）。**响应结构不变**：`{modelCount, chunkCount, embeddedCount, missingCount, missingTopN}`。
  - `EmbeddingModelReconcileRunner`（`@Order(60)`）：改查 `VectorStoreService.embeddingModelStats`（store `GROUP BY metadata.domain/embeddingModel`），非当前模型行 WARN、异常不阻断。
  - `ImageEmbeddingService.rebuildMissing`：差集 = 图库 id 全集 − `VectorStoreService.refIdsByDomain("IMAGE", 当前模型)`，语义与原旧表 `LEFT JOIN` 差集一致；store 未注入（单测）时视为无缺失。
- **删除件**：`CarDocEmbeddingMapper`/`KbChunkEmbeddingMapper`/`NewsDocEmbeddingMapper`/`ImageEmbeddingMapper`/`EmbeddingModelStatsMapper`/`VectorStoreBackfillMapper` + `VectorStoreBackfillRunner`（E1 回填已完成）全部删除。
- **兼容/回退**：对拍前旧表与 store 逐字节等价（E1 阶段 A 已证）。DROP 不可逆，回退 = 由 store 重建旧表（向量逐字节可复现）或 `git revert` V9 + 从备份/重嵌恢复。

### 11.1 NEWS 域 metadata 回填（10-05 E，`V14__news_source_metadata.sql`；10-09 M 追加 `V16`）

- **不改 `domain` 名**（仍 `NEWS`）：`docId=UUID(domain+":"+refId)` 确定性主键，改名会使全部存量 NEWS 向量 id 失配、须全量重嵌。V14 **只补 metadata 缺键**（`sourceType=byd-news`/`category=官方新闻`/`publishDate` 由 `news_doc→news.publish_date` 派生），**`id` 列与 `embedding` 不动 → 零重嵌**。
- **10-09 M（`V16__news_source_metadata_url_tier.sql`）**：同一范式幂等补 `url`（`refId→news_doc.news_id→news.url` 派生）与 `authorityTier`（`byd-news` 固定 `official`；通用信源经 `news.source_id→sparkora_source.authority_tier` 派生，缺档不写）；只 `metadata || jsonb`、**`id`/`embedding` 不动**；补全 F-R3 跨源同 URL 去重 / F-R4 权威分档的生产侧数据（此前 `Citation.url` 恒空、`authorityTier` 恒 null）。
- **幂等可重入**：每条 UPDATE 带 `(metadata->'key') IS NULL` 条件，重复执行仅第一次生效；不用 `DO $$`。
- **新增写入侧同步**：`NewsDocService`（BYD）与 `SourceDocService`（通用信源）都在 `upsert` 时写这些键（含 10-09 M 的 `url`/`authorityTier`），不能只靠 V14/V16 回填存量；检索层对 `sourceType==null` 兜底为 `byd-news`。
