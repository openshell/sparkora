# prd.md — 信源采集基座

> 父任务：`10-05-self-hosted-sources`（需求集与设计论证持有者）
> 调研依据：`doc/汽车资讯信源调研报告-2026-10-05.md`
> **前置依赖：`10-05-crawl4ai-transport`（C）提供 `FetchTransport` 接口。** 理由（评审修正，消除与父图矛盾）：
> C 拥有 `FetchTransport` 抽象与 `HttpFetchTransport`/`Crawl4aiFetchTransport` 两个实现；B 的采集器经该接口抓取，
> 故 B 依赖 C 的接口契约。**C 的 Crawl4AI 实现是否配置不影响 B 开工**——B 用 `HttpFetchTransport` 即可采 A 级源，
> B 级源仅在有 Crawl4AI 时可用。父任务 design §7 的 `C → B` 依赖与此一致。
> （若希望 B 完全无依赖，则须把 `FetchTransport` 接口移入 B——当前选择由 C 拥有，B 显式依赖。）

## Goal

把现有「单一 BYD 新闻采集」泛化为**可配置、多信源、按发布周期排期**的采集基座：信源以数据/配置注册，支持 `type=RSS|SITE` 混合形态，每源独立 cron/窗口，采集产出规范化去重 + 原始留存 + 幂等 upsert，并有任务表与失败明细供监控重试。

用户价值：这是自建信源的地基——没有它，后续「入库检索」「知识中心」「融合」都无内容可消费。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置**：`10-05-crawl4ai-transport`（C）的 `FetchTransport` 接口与 `HttpFetchTransport`；Crawl4AI 实现为可选增强（未配置时 B 级源降级，A 级源照常）。
- **后继**：`10-05-source-domain-retrieval` 需要本任务落库的规范化内容与任务表；`10-05-source-center-ui` 需要本任务的注册/任务 API **与内容查询 API（B-R9，U 的硬前置）**。

## Requirements

- **B-R1 信源注册表**：持久化信源定义（表或配置，含 `id/name/url/type(RSS|SITE)/category/vertical/cron 或发布窗口/enabled/解析规则/权威档/created_*`）。`type=RSS` 走 feed 解析，`type=SITE` 走列表页解析 + 详情抓取。**每源可单独启停**。
- **B-R2 采集客户端抽象**：`SourceClient` 接口（列表/详情），实现 `RssSourceClient`（jsoup `Parser.xmlParser()` 解析 RSS/Atom，**不新增依赖**）与 `SiteSourceClient`（jsoup 选择器；选择器集中配置，仿 `NewsContentParser`）。**结构化（表格）解析（评审新增）**：数据型源（乘联会销量表等）内容多为 HTML 表格，`SiteSourceClient` 须把表格转成保留行列语义的文本（逐行），不得直接落入 `TextChunker` 的「段内换行转空格」而被压平（父设计 §6）。抓取经 `10-05-crawl4ai-transport` 的 transport；B 级源（乘联会）标记需 Crawl4AI，未就绪时跳过并记降级原因。
- **B-R3 每源排期（评审修正：须动态调度 + 发布窗口语义）**：每源独立排期。**`@Scheduled` 注解的 cron 是编译期固定的，无法表达「每源独立且可运行时增删」**——须改为动态调度：以 `TaskScheduler` + `CronTrigger`（或等价）在启动/信源变更时按注册表逐源注册/注销任务。调度器按源逐条触发，**全局串行**（复用 `NewsSyncScheduler` 的 `markStaleRunningAsFailed` + `hasFreshRunning` 防重叠范式），同源运行中跳过。**不得每源各自 `@Async` 并发**（资源红线）。
  - **发布窗口语义（P2-3，评审新增）**：实际节奏是「窗口」而非单点 cron——乘联会「每月 8-11 日」、工信部「约 4 日/19 日双批」。月度单点 cron 只触发一次，无法表达「窗口内每日触发直到本批采完/成功」。故信源注册表须支持 **`window_start_day` / `window_end_day`（可选）**：窗口期内**每日触发一次**，成功后标记本批完成、窗口内跳过；失败/部分失败则窗口内次日重试。无窗口的源退回普通 `cron`。
  - **BYD 优先（用户 2026-10-05）**：工信部等源需**过滤出 BYD 相关条目全收**；其他车企仅保留销量/投诉对比字段（见父 PRD D8）。过滤规则走 `parse_rules`，不做通用 NLP 判定。
- **B-R4 内容规范化与去重**：产出统一 `{sourceId, externalId, title, url, publishDate, content, tags?}`；`externalId` 为源内唯一键（官方 id / 规范化 URL）做幂等 upsert；正文空仍入库元数据（沿用新闻容错）；保留原始 HTML 或摘要留痕。
- **B-R5 任务与失败明细**：采集任务表（仿 `sparkora_news_sync_job`：`jobType/status/total/success/failed/failed_items/started_at/finished_at/error_msg/created_by`）；单条失败记 `failed_items` 不阻断；`markStaleRunningAsFailed`（60 分钟陈旧自愈）；手动触发/重试失败项 API。
- **B-R6 接口契约**：`/api/sources`（列表/详情/启用停用，写 ADMIN/EDITOR）、`/api/sources/{id}/collect`（手动触发，返回 `{jobId}`）、`/api/source-jobs`（进度/历史/重试）；全部 `R<T>` + 方法级 `@PreAuthorize`。
- **B-R9 内容查询 API（P1-2，评审新增：U 的硬前置）**：U 的 `SourceContentPanel` 需要内容列表，但 B/E 均未定义该端点——**归属 B**（B 拥有 `sparkora_news` 写入与 `SourceController`）。新增：`GET /api/source-contents`（分页 `page/size` + `keyword` + `category` + `sourceId` 筛选，返回 `{id,sourceId,title,url,publishDate,category,source,chunkCount?}`）；`GET /api/source-contents/{id}`（详情，含正文、切块数、原创链接）。返回走 `R<T>`；`source=byd` 条目须带同步/切块数/官方原文所需字段供 U 条件渲染。**E 不提供 HTTP 接口**，故该端点只能由 B 承担。
- **B-R7 配置与文档**：每源 cron 走配置；`.env.example` 新增采集相关配置项；同步 `docs/spec/knowledge/news.md`（说明 NEWS 为通用信源特例的来源）与新增/更新信源 spec。
- **B-R8 迁移策略（关键）**：**不改现有 `sparkora_news*` 表语义**（BYD 新闻作为 `category=官方新闻` 的特例继续可用）；新增信源表与内容表（或扩展为通用表并在 `10-05-source-domain-retrieval` 统一）。具体域表归属与 `10-05-source-domain-retrieval` 协调，本任务先落「注册表 + 任务表 + 原始内容」，切块/向量在下一任务。

## Acceptance Criteria

- [ ] **AC-B1 注册与采集**：注册工信部（A 级 HTTP，BYD 申报全收）与乘联会（B 级，需 Crawl4AI）后，工信部可手动采集入库；乘联会在 Crawl4AI 未配置时明确降级、配置后可采；幂等重跑不产生重复。
- [ ] **AC-B2 每源排期**：两个源 cron 不同时互不阻塞；同源运行中再次触发被跳过（防重叠）；**新增/停用信源后调度任务能运行时动态注册/注销**（验证 `@Scheduled` 静态注解不满足此需求已改用动态调度）。
- [ ] **AC-B8 发布窗口**：配置乘联会「每月 8-11 日」窗口后，窗口期内每日触发一次、成功后本批跳过、失败次日重试；非窗口期不触发（与单点 cron 行为区分）。
- [ ] **AC-B9 内容查询 API**：`/api/source-contents` 支持分页/关键词/category/sourceId 筛选；详情含正文与切块数；`source=byd` 条目返回 U 条件渲染所需字段；viewer 可读、写接口 403。**U 可直接消费该契约**。
- [ ] **AC-B7 结构化解析**：乘联会销量表等表格内容解析后保留行列语义，数值不丢失（下游 E 可检索命中）。
- [ ] **AC-B3 RSS 与 SITE 双形态**：至少 1 个 RSS 源与 1 个 SITE 源各自可采（可用测试 feed + 工信部/盖世验证）；RSS 解析不新增依赖。
- [ ] **AC-B4 容错**：单条详情失败记入 `failed_items` 且其余继续；正文空仍入库；陈旧 RUNNING 超 60 分钟自动置 FAILED。
- [ ] **AC-B5 任务可见**：`/api/source-jobs` 能查到进度/失败明细，失败项可重试。
- [ ] **AC-B6 零回归**：不改 `sparkora_news*` 表语义；BYD 新闻同步行为不变；**既有 `/api/news` 只返回 `source=byd-news`，不泄漏通用信源内容**；`mvn test` 全绿。

## Out of Scope

- 切块/向量化/检索接入（→ `10-05-source-domain-retrieval`）。
- 信源管理 UI（→ `10-05-source-center-ui`）。
- Crawl4AI 客户端实现（→ `10-05-crawl4ai-transport`）。
- 公众号/B站/微博采集（走外部搜索线）。
- 采集内容人工编辑/审核。
- LLM 语义清洗。

## Notes

- 工信部公示列表页准确路径首次接入需人工定位一次，之后固定写进源配置。
- 站点选择器随改版易腐；选择器集中配置便于单源修复，不做通用智能抽取。
- 本机服务地址（Crawl4AI）走 `.env`，不硬编码。
