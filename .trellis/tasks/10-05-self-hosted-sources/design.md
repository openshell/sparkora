# design.md — 自建汽车资讯信源与外部搜索融合基座

> 父任务设计：定义跨子任务架构、共享契约、迁移策略、任务边界与回滚。子任务的实现细节在各自 `design.md` 展开。
> 依据：`docs/汽车资讯信源调研报告-2026-10-05.md`；仓库现状见 `prd.md` Background。

---

## 0. 现状与差距（First Principles）

**问题重述**：外部搜索有额度、限流、稳定性、摘要级四大限制，导致写作证据覆盖不足；自建信源能提供可控全文，但采集链路现在只服务 BYD 新闻一个来源。

**基本事实（不可违背）**：

1. `vector_store` 三域共用一表，但**候选窗口必须按域隔离**（news.md §5 实测：共用全局 LIMIT 会让 CAR 候选 32→0）。
2. 现有采集/任务/调度范式（`NewsSync*`/`CarSync*`）已被验证可复用，但只支持单一来源与单一 cron。
3. Crawl4AI 是本机已部署能力，但代码侧 `CRAWL4AI_BASE_URL` 一直预留未接（brief-generation.md §8）。
4. 资源红线是物理约束：无头浏览器 300-400MB、swap 紧 → 并发 ≤2、同站 ≤2/天、调度串行。
5. `rag_status` 四态语义与 `FactSheetService` 置信规则是跨链路契约，变更面大，**默认不得改**。

**差距**：缺「多源注册 + 多通道抓取 + 每源排期」的采集基座；缺「通用信源域」的检索定位；缺「本地 vs 外部」的融合语义。

---

## 1. 分层架构

```
                         ┌──────────────────────────────────────────────┐
                         │  生成链路（简报/正文/深度研究）                 │
                         │  CarRagService 检索注入 + FactSheetService 融合 │
                         └───────────────┬──────────────┬────────────────┘
                                         │              │
                    ┌────────────────────┘              └──────────────────┐
                    ▼                                                     ▼
      ┌──────────────────────────┐                      ┌──────────────────────────┐
      │ 本地信源域（复用 NEWS 域） │                      │ 外部搜索（姊妹任务）       │
      │  vector_store domain=NEWS  │                      │  WebSearchRouter          │
      │  +sourceType/category 标注  │                      │  Tavily/Serper/SearXNG    │
      └────────────▲─────────────┘                      └──────────────────────────┘
                   │ 切块+嵌入（E）
      ┌────────────┴─────────────┐
      │ 采集入库（B）              │
      │  信源注册表+任务表+规范化   │
      └────────────▲─────────────┘
                   │ 抓取（B 调用）
      ┌────────────┴─────────────┐
      │ 抓取通道（C）              │
      │  HttpFetch / Crawl4AI      │
      │  并发≤2 · 同站≤2/天 · 串行 │
      └──────────────────────────┘
```

**层职责**：
- C（抓取通道）：只负责「给 URL 拿 HTML/正文」，不知道信源是谁。
- B（采集基座）：负责「信源是谁、什么时候抓、抓完怎么规范化去重落库、任务怎么记账」。
- E（入库检索）：负责「落库内容怎么变可检索证据、来源怎么标注、配额怎么分」。
- U（知识中心）：负责「怎么让人运营和观测」。
- F（融合）：负责「本地和外部证据怎么裁决」。

---

## 2. 共享契约（子任务必须遵守，不得各自另立）

### 2.1 信源注册表（B 定义，E/U 消费；**支持一个源挂多栏目** — 评审 2026-10-05）

> **修正依据**：乘联会天然是多栏目站（行业新闻 `news.php?types=news` / 车市解读 `?types=csjd` / 发布会报告 `?types=cpc`），工信部亦多公示页。若 `list_url` 单值，只能把「一个站点」拆成多行源，导致 `source_id` 分裂、去重/配额/UI 语义变脏。故注册表**从「一源一列表页」升级为「一源多栏目（channel）」**。

```
SourceDefinition {
  id, name,
  type: RSS | SITE,
  vertical:  web | news,
  schedule:  { cron 或 发布窗口(每月 8-11 日 / 月初 / 每月 4,19 日) },
  fetchMode: HTTP | CRAWL4AI,        // B 级源标 CRAWL4AI（源级默认，栏目可覆盖）
  authorityTier: 1|2|3,              // F 置信分档用（预留，默认不启用分档）
  enabled:   boolean,
  channels: [ SourceChannel ]        // 一个源 ≥1 个栏目
}

SourceChannel {
  id, sourceId,
  name,                              // 栏目名（如「车市解读」）
  listUrl,                           // 该栏目列表/feed 地址
  detailBaseUrl,                     // 详情相对链接基址
  category,                          // 官方新闻 | 销量数据 | 政策公示 | 行业资讯 | ...
  parseRule: { 列表选择器 / 详情选择器 / 日期选择器 / 正文容器 / 表格选择器 / 图片选择器 },
  fetchMode: HTTP | CRAWL4AI?,       // 可选覆盖源级
  enabled:   boolean
}
```

- **一个源 = 一个站点/机构；一个栏目 = 一个列表页**。`category`/`parseRule` 下沉到栏目级（同站不同栏目选择器与分类不同）。
- **零回归**：仅一个栏目的源 = 只建一条 channel，行为与「一源一列表页」等价。
- **兼容**：`sparkora_source` 仍持有源级公共字段（name/type/schedule/fetchMode/authorityTier/enabled）；列表相关字段全部移入子表 `sparkora_source_channel`（见 B design §2.2）。
- **扩展性**：新增「乘联会其他报告」= 给该源**追加一个 channel 行**（配 `listUrl` + `parseRule` + `category`），代码零改动。

### 2.2 抓取契约（C 定义，B 消费）

```
FetchTransport.fetch(url, opts) → FetchResult {
  finalUrl, status, html, content, latencyMs, error?, limited?
}
```
- `FetchResult.limited=true` 表示被并发/频控拒绝，B 据此降级跳过而非无限重试。

### 2.3 采集内容契约（B 定义，E 消费）

```
RawSourceItem {
  sourceId, channelId,             // channelId = 具体栏目（一个源可有多个）
  externalId,                      // externalId = 源内唯一键（官方 id / 规范化 URL）
  title, url, publishDate, content, category, tags?
}
```
- 幂等键 = `(sourceId, channelId, externalId)`（同站不同栏目独立去重）。

### 2.4 向量域契约（E 定义，F 消费）— 关键（评审修正 2026-10-05）

> **修正依据**：`VectorStoreService.docId()`（`src/main/java/com/sparkora/ai/vector/VectorStoreService.java:64`）
> 用 `UUID.nameUUIDFromBytes(domain + ":" + refId)` 生成确定性向量主键。**改 `domain` 名会导致全部存量 NEWS 向量 id 失配、必须全量重嵌**，而非仅回填 metadata。

**决策：保留 `domain=NEWS` 不改名**，把现有 NEWS 域作为「通用信源域」，用 metadata 维度细化来源。`vector_store.metadata` 在既有
`{domain, refId, modelId, chunkType, name, active, embeddingModel}` 基础上，**NEWS 域行**新增：

```
domain      = NEWS                          // 保持不变（不改名，避免 id 失配/重嵌）
sourceType  = byd-news | user-source | ...  // 具体来源
category    = 官方新闻 | 销量数据 | 投诉榜 | 政策公示 | 行业资讯 | ...  // 来源分类
publishDate = ISO 日期（可空）               // 新鲜度用
```

- **BYD 新闻 = `sourceType=byd-news`, `category=官方新闻`**，其检索/标注/配额/前端行为等价的回归门。
- **域内二级隔离（评审新增）**：泛化后 BYD 官方新闻与用户采集源同处 NEWS 域。因 `CarRagService.java:256` 现为「CAR+KB 合并窗 + NEWS 独立窗」且配额 `AI_RAG_NEWS_TOPK` 仅 4，**若 BYD 与采集源共用窗口会被挤占（破 AC-E3）**。故检索层须在 NEWS 域内**按 `sourceType` 再隔离候选窗与配额**：BYD 新闻保留原窗口/原配额，用户采集源用独立窗口/独立配额（默认 off 时零回归）。
- 检索来源标注（E 定义）：`【信源：<title>】` 或按 category 细分；**旧 `【官方新闻：<title>】` 文案与前端分支在 BYD 场景（`sourceType=byd-news`）保持逐字等价**。

### 2.5.1 B/E 表归属与向量 id 空间（评审新增，B/E 共同约束 — 关键）

> **修正依据**：`VectorStoreService.docId(domain, refId) = UUID(domain+":"+refId)`，`domain=NEWS` 的 `refId`
> 语义是 **`sparkora_news_doc.id`**（`VectorStoreService` 类注释、`CarRagService:66`）。若为新表
> `sparkora_source_doc` 单独分配 `id`，会与 `sparkora_news_doc.id` **撞号**，`docId("NEWS", n)` 产生同一 UUID → 向量互相覆盖。

- **决策**：通用信源采集产物**复用 `sparkora_news*` 表**。`sparkora_news` 增 `source_id` FK（NULL=存量 BYD）；
  `sparkora_news_doc`/`sparkora_news_doc_embedding` 结构不变，仍是 `domain=NEWS` 向量的 `refId` 来源。
- **职责切分**：B 落**原始内容**（`sparkora_news.content`）；E 负责**切块+嵌入**（`_doc`/`_embedding` + `vector_store.metadata`）。
- **非 BYD `news_id`**：由 `source.externalId` 派生（如 `<sourceId>:<externalId>`），满足既有 `news_id UNIQUE`。
- 后果：E 的「回填 `sourceType`」退化为纯 metadata 操作；BYD 同步路径零改动。

### 2.6 事实来源类型与权威分档（评审新增，F 定义，E 消费）

> **修正依据**：`SubAgentRunner.validateFacts`（`src/main/java/com/sparkora/deep/service/SubAgentRunner.java:268-271`）
> 硬白名单仅 `KB|WEB`，其他类型直接 `rejected` 转 gap。自建信源若不突破该白名单，只能二选一地出错：
> 产出 `SOURCE` → 证据丢失；沿用 `KB` → 采集的网络内容被当手写权威知识库拿 0.9 分（误导）。

- **决策（用户）**：自建信源事实使用**新增 `SOURCE` 类型 + 权威分档**。
- `SearchHit` / 事实 `source.type` 新增 `SOURCE`；`KnowledgeSearchTool` 需能区分 NEWS 域命中的来源类型（当前统一 `name()="KB"` + `SearchHit.kb(...)`，须扩展）。
- **权威分档（`authorityTier` → confidence）**：官方/政务（BYD 官方、工信部、乘联会）0.9；行业媒体（盖世等）0.7；论坛/自媒体/公众号 0.5。分档存于信源注册表，**默认不启用分档（全部走一个保守档）以保证零回归**。
- `validateFacts` 白名单扩展为 `KB|WEB|SOURCE`；`SOURCE` 事实按信源身份核验（本地命中或本次输入 sourceId），不得自造 URL。
- `FactSheetService` 新增 `SOURCE` 分级分支；`SOURCE` 胜普通 `WEB`、与 `KB` 的优先级由 F 定义（见 F-R2）。

### 2.5 事实来源契约（F 定义，与姊妹任务共享）

- 本地信源事实 `source.type` 新增 `SOURCE`（见 §2.6），**保留旧 `KB|WEB|MULTI` 兼容**。
- 独立交叉判定仍以「provider/来源身份去重」为准，**同源多通道（本地 BYD + 外部同篇）不计独立交叉**。
- 与 `10-04-brief-retrieval-sources` 的 `usedProvider→usedProviders`、`SearchMeta.provider→providers`、`attempts` 增量字段是**同一契约**。
- **provider 身份 × endpoint 实例分层（评审补记）**：同一 provider 的多个 endpoint（如 Tavily 官方 + 任意中转）共享 provider 身份，**不得提升独立来源计数**；仅记 `witnessEndpoints` 供调度/健康观测。此分层应回填到姊妹任务契约（见 §8）。

---

## 3. 关键设计决策与取舍

### 3.1 泛化 NEWS vs 新增平行域（D2，评审修正）

- **选泛化**：只维护一套窗口隔离/配额/标注逻辑；来源类型由 metadata 维度表达。
- **修正：不改 `domain` 名**（原设计写「泛化为 SOURCE 域」）。依据 `VectorStoreService.docId()` 的确定性 id = `UUID(domain+":"+refId)` —— 改名会使全部存量 NEWS 向量 id 失配、须全量重嵌。**保留 `domain=NEWS`**，仅新增 `sourceType/category/publishDate` metadata 键。
- **迁移退化**：从「改名 + 全量回填」降为「为存量 NEWS 行补 `sourceType=byd-news`/`category=官方新闻`」——**零重嵌、零 id 失配**。
- **代价（修正后）**：仍需迁移回填 metadata（可重入、幂等），但破坏面显著缩小。
- **缓解**：实施前做 BYD 检索对拍留基线（照 10-03 E1/E2 parity 范式）；回滚 = `git revert` 迁移脚本。
- **域内二级隔离**：见 §2.4——NEWS 域内按 `sourceType` 再隔离窗口/配额，保护 BYD 不被采集源挤占。
- **测试连带面**：`"NEWS"` 字面量分布在 `VectorStoreService`/`CarRagService`/`QaImageRefService` 及约 10 个测试文件；保留 domain 名使这些**无需改名**，降低回归面。

### 3.2 采集表归属（B 与 E 的边界协调）

**决策（评审收口 2026-10-05，与 §2.5.1 一致）：复用 `sparkora_news*` 表，不新建平行 id 空间。**
- 新增 `sparkora_source`（信源注册表）+ `sparkora_source_job`（采集任务表）；采集产物**复用 `sparkora_news`**（增可空 `source_id` FK，NULL=存量 BYD）+ `sparkora_news_doc`/`sparkora_news_doc_embedding`（结构不变）。
- 理由：`domain=NEWS` 向量的 `refId` 必须是 `sparkora_news_doc.id`（`VectorStoreService` 类注释 + `CarRagService:66`）。若为 `sparkora_source_doc` 另建自增 id，会与 `sparkora_news_doc.id` **撞号** → `docId("NEWS", n)` 产生同一 UUID → 向量互相覆盖。
- 被否决的替代：新建独立 `sparkora_source_doc` 作为向量 `refId` 来源（撞号，见 §2.5.1）；把 `sparkora_news*` 整体改名/改语义（破坏面大）。
- **B/E 职责切分**：B 落**原始内容**（`sparkora_news.content`），E 负责**切块+嵌入**（`_doc`/`_embedding` + `vector_store.metadata`）。两者须在各自 design 锁定同一方案（B design §2.1、E design §2 已锁定）。

### 3.3 调度粒度与资源红线（B）

- 每源独立 cron/窗口，但**全局串行执行**，复用 `markStaleRunningAsFailed` + `hasFreshRunning` 防重叠。
- 不得每源 `@Async` 并发；Crawl4AI 抓取经 C 的并发 ≤2 信号量。
- 取舍：串行牺牲吞吐换稳定（数据源是分钟级低频，无吞吐诉求）。

### 3.4 抓取通道降级（C→B）

- `CRAWL4AI_BASE_URL` 未配置时 `FetchTransport` 仅 HTTP；B 级源（标 `CRAWL4AI`）在降级时跳过并记原因，**不 fallback 到 HTTP 硬闯 403**（避免触发 WAF 升级）。

### 3.5 融合优先级（F）

- 本地信源 > 外部 WEB（同 claim），沿用「KB 胜 WEB」范式扩展。
- 理由：本地是可控全文/权威数值，外部是摘要级/可能转载失真；但本地覆盖有限，故外部在本地无覆盖时补缺。
- 时效：本地带 `publishDate` 可参与，但**默认不启用强时效衰减**（零回归）。

---

## 4. 数据流（端到端）

```
[B] 每源 cron 触发
      → 列表抓取（C: HTTP / Crawl4AI）
      → 详情抓取（C）
      → 规范化 + (sourceId,externalId) 幂等 upsert 到 sparkora_source*
      → 任务表记账（success/failed_items）
[E] 采集内容 → 切块（结构化保留行列语义，§6） → EmbeddingBatchRunner 嵌入 → vector_store(domain=NEWS, sourceType, category, publishDate)
      → CarRagService 按域隔离窗口取候选 → 配额 → 来源标注 → 注入生成
[F] SubAgentRunner 产 fact（本地 SOURCE 事实 + 外部 WEB 事实）
      → FactSheetService.merge：同 claim 本地优先 / 跨源同 URL 去重 / 独立交叉判定 / 置信分档
      → fact_sheet → 蓝图 → 正文
[U] 知识中心：信源注册/启停/触发/任务监控/内容浏览
```

---

## 5. 兼容与迁移（评审修正 2026-10-05）

- **Flyway**：新增 `V13+` 脚本，**不改已应用的 V1–V11**。
- **不改 `domain` 名**：保留 `domain=NEWS`，避免 `docId` 确定性主键失配与全量重嵌（见 §3.1）。
- **存量 NEWS 回填**：迁移脚本将 `vector_store` 中 `metadata->>'domain'='NEWS'` 行补 `sourceType=byd-news`、`category=官方新闻`、`publishDate`（如可解析）；**幂等可重入**。新增信源表（`sparkora_source*`）另行建表。
- **零回归基线**：未启用自建信源时，BYD 新闻同步、深度研究、`fact_sheet`、`rag_status` 行为逐位等价；`mvn test` 现有 842 例全绿。
- **新增配置默认关闭**：所有采集开关、融合开关、权威分档、新鲜度默认 off；`.env.example` 同步，URL 类键 `_BASE_URL` 结尾。

## 5.5 采集信源配图接入图库（用户 2026-10-05 新增，B/E 共同约束）

> 需求：采集信源（工信部/乘联会/盖世）的**正文配图**要能作为**文章配图**素材。现 BYD 新闻封面图已入图库，通用信源图片此前在 Out of Scope。

**复用既有图库设施，不新建图库**：

- 图库主表 `sparkora_image_asset` 已有 `source` 列（`ImageService.SOURCES` 白名单，现 `{upload, ai-text2img, ai-img2img, byd, byd-news}`）与 `source_ref` 列。
- 转存入口：`ImageService.saveExternalImage(projectId, url, fileName, source, tags, operator, sourceRef)`（图库完全依赖图床，本地不留）。

**B（转存侧）**：
- `SiteSourceClient` 详情解析新增**正文图片抽取**（选择器来自 `parse_rules.images`，与正文选择器同样集中配置）；
- 逐图调 `saveExternalImage(null, imgUrl, "source-<id>-<n>.<ext>", "source", tags, "system", sourceRef)`，**单图失败 warn 跳过不阻断**（照 `NewsService` 封面图容错）；
- **关键**：现 `saveExternalImage` 的 `resolveBydUrl()` 对**相对 URL 硬编码拼 `https://www.byd.com`** → 须改为「按调用方传入的 `detail_base_url` 解析」（新增重载或参数），否则工信部/盖世的相对图链会被拼错域。`sourceRef` = 该内容对应的 `news_id`（非 BYD）或其 `sparkora_news_doc.id`，供 E 反查标题。
- `ImageService.SOURCES` 白名单新增 `"source"`。

**E（检索侧）**：
- `ImageEmbeddingTextBuilder.build` 新增 `source` 分支：以**来源内容标题**（经 `sourceRef` 反查 `sparkora_news`）为嵌入主信号 + 标签，同 `byd-news` 模式；
- `ImageEmbeddingService.newsTitleOf` 的反查当前硬编码仅 `byd-news` → 扩展为对 `source` 来源也反查（或统一「凡 `sourceRef` 可反查即用」）；
- 使新来源图可被 `ImageEmbeddingService.searchImages` 命中，进入既有**文章自动配图 / 问答配图**链路。

**不做**：视频入库；反盗链/水印；图片版权审核（沿用「采集公开内容供内部创作参考」定位）。零回归：无图源/未配开关时行为不变。

---

## 6. 结构化内容切块（评审新增，B/E 共同约束）

> **修正依据**：`TextChunker`（`src/main/java/com/sparkora/ai/TextChunker.java:97-102`）按空行分段、**段内换行转空格**，且空正文仅保留标题块。乘联会销量表等 HTML 表格若不在 `<p>` 中会解析为空、被只存标题；若在文本流中会被压成无结构空格长串。

- **约束**：自建数据型信源的**表格/结构化内容不得走默认 `TextChunker` 直接压平**。
- **方案（择一，E 的 design 锁定）**：
  - (a) 解析阶段把表格转成「行文本」（如 `车型 | 销量 | 同比` 逐行）再切块，保留行列语义；
  - (b) 结构化内容以整段/按行切块，不转空格；
  - (c) `TextChunker` 增加「保留换行」可选模式（新增重载，默认不改既有行为）。
- **不新增依赖**：jsoup 已具备表格解析能力。
- **验收**：AC-E1/B1 须包含「表格类内容数值不丢失」的断言。

---

## 7. 任务边界与依赖

```
C(crawl4ai-transport) ─→ B(source-crawl-base) ─→ E(source-domain-retrieval) ─┬→ U(source-center-ui)   [U 另需 B]
                                                                              └→ F(source-web-fusion) ← 10-04-brief-retrieval-sources
```

- C 与 B 无依赖，可并行开工。
- E 依赖 B；U 依赖 B+E；F 依赖 E + 姊妹任务 A。
- **共享契约以本文件 §2 为准**；子任务若需变更契约，回到父任务更新本文件。

---

## 8. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 泛化迁移破坏存量检索 | 不改 domain 名（零重嵌）+ BYD 对拍基线 + 迁移幂等 | revert 迁移 |
| 信源块挤占 CAR/KB 候选 | 强制按域隔离窗口 + AC-E2 验证 | 关闭信源域配额 |
| 采集源挤占 BYD 新闻配额 | NEWS 域内按 sourceType 二级隔离窗口/配额 | 关闭用户源配额 |
| 表格/结构化数据被 TextChunker 压平丢失 | §6 结构化切块策略 + 数值断言 | 单源停用 |
| SOURCE 事实被 validateFacts 拒绝或误当 KB | §2.6 白名单扩展 + 权威分档 + 反例单测 | 回退为纯 KB/WEB |
| Crawl4AI 触发 WAF/资源打爆 | 并发≤2 + 同站≤2/天 + 串行 | 停用 B 级源 |
| 站点改版导致解析失效 | 选择器集中配置 + 失败可见 | 单源停用，不影响他源 |
| 融合规则误判交叉/置信 | 确定性规则 + 反例单测 + 默认零回归 | 关闭融合开关 |
| 子任务契约分叉 | 本文件 §2 共享契约 + 父任务集成评审 | 回到父任务统一 |

## 9. 对姊妹任务的修正项（评审发现；2026-10-05 已回填 `10-04-brief-retrieval-sources`）

> 以下项已回填到 `10-04`（本文件记录追溯），F 的契约前提已满足。

1. **Tavily 中转结论已过时（已修正）**：`10-04-brief-retrieval-sources/prd.md` §D5/§Out of Scope/§Confirmed Facts 原写「不可用、不采用」；已更新为「**可连通、间歇性慢失败（恒定约 16s）**」，与 `TavilySearchTool` 15s readTimeout 冲突（客户端必先超时），故采用「中转优先 + 独立短超时 + 官方兜底」。
2. **provider 身份 × endpoint 实例分层（已回填）**：同一 provider 多 endpoint 不得提升独立来源计数（见 §2.5）——已回填 `10-04` PRD D6 与 design §2.3（`witnessEndpoints`），F 依赖该契约。
3. **多源/多通道的 `usedProviders` 契约**：由 `10-04-web-fanout-merge`（B）定义/实现；F 依赖。
4. **Tavily 双端点策略（用户 2026-10-05 指令）**：独立子任务 `10-05-tavily-endpoint-priority`；F 依赖其「provider 身份固定 `TAVILY`」契约（双端点同 URL 只算 1 源）。
