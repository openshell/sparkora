# design.md — B: 信源采集基座

> 父任务设计：`../10-05-self-hosted-sources/design.md`（共享契约以父 §2 为准）。
> 本文只写 B 自身的实现边界、表设计、调度机制与取舍。
> 依赖：无（Crawl4AI 通道 C 为可选增强）。

## 1. 边界

| 层 | 文件（新增/改） | 改动 |
|---|---|---|
| 迁移 | `db/migration/V13__source_registry.sql` | 新增 `sparkora_source` / `sparkora_source_job` / `sparkora_source_doc`；对 `sparkora_news` 加 `source_id` |
| 配置 | `config/SourceProperties.java`（新增） | 采集开关、Crawl4AI base、并发/同站上限 |
| 领域 | `source/domain/SourceEntity`、`SourceJobEntity`、`SourceDocEntity` | MyBatis-Plus 实体 |
| 客户端 | `source/client/SourceClient`、`RssSourceClient`、`SiteSourceClient` | 列表/详情；RSS 用 jsoup XML，SITE 选择器集中 |
| 解析 | `source/service/SourceTableParser` | HTML 表格 → 保留行列的文本（父 §6） |
| 调度 | `source/service/SourceScheduleService` | 动态 `TaskScheduler` + `CronTrigger` 按源注册/注销 |
| 服务 | `source/service/SourceCollectService`、`SourceJobService` | 采集编排、幂等 upsert、任务/失败明细 |
| 控制器 | `web/controller/SourceController` | `/api/sources*`、`/api/source-jobs*` |
| 文档 | `docs/spec/knowledge/news.md` + 新 `docs/spec/knowledge/sources.md`、`.env.example` | 字段级契约 |

**不做**：切块/向量/检索（E）；管理 UI（U）；Crawl4AI 客户端实现（C）。

## 2. 表设计（关键：复用 `domain=NEWS` 的共享 id 空间）

### 2.1 为什么必须让新信源文档进入 `sparkora_news_doc`

`VectorStoreService.docId(domain, refId) = UUID.nameUUIDFromBytes(domain + ":" + refId)`，`domain=NEWS` 的向量
`refId` 语义是 **`sparkora_news_doc.id`**（`VectorStoreService` 类注释 + `CarRagService:66` 明写）。

→ 若为新表 `sparkora_source_doc` 单独维护自增 `id`，其 `id` 会与 `sparkora_news_doc.id` **撞号**：
`docId("NEWS", 5)` 对两条不同文档产生**同一 UUID**，向量互相覆盖。这是不改 domain 名后**唯一必须处理的 id 空间问题**。

**决策**：`domain=NEWS` 的向量仍以 **`sparkora_news_doc.id`** 为 `refId`。通用信源采集产物**写入/复用 `sparkora_news*` 表**
（`sparkora_news` 增加 `source_id` FK 指向注册表；`sparkora_news_doc`/`sparkora_news_doc_embedding` 结构不变）。
- 理由：一张全局自增 id 空间，零撞号；BYD 新闻 = `source_id=byd` 的特例；`VectorStoreService`/`CarRagService`/`QaImageRefService`
  的 `refId=news_doc.id` 语义**完全不动**；E 的「回填 metadata `sourceType`」退化为纯 metadata 操作。
- 代价：`sparkora_news` 的 `news_id UNIQUE`（官方字符串 id）对非 BYD 源需由 `source.externalId` 派生填充（见 §2.2）。

### 2.2 表结构

```
sparkora_source                       -- 信源注册表
  id BIGSERIAL PK
  name            VARCHAR(100)  源名（工信部/乘联会/盖世/...）
  type            VARCHAR(10)   RSS | SITE
  list_url        VARCHAR(500)  列表/feed 地址
  detail_base_url VARCHAR(500)  详情基址（相对链接拼接）
  category        VARCHAR(30)   官方新闻|销量数据|投诉榜|政策公示|行业资讯
  vertical        VARCHAR(30)   汽车/政策/...（预留）
  cron            VARCHAR(50)   每源 cron（动态注册）
  authority_tier  VARCHAR(20)   official|industry|media|ugc（F 用；默认不启用分档）
  need_crawl4ai   BOOLEAN       该源是否需 Crawl4AI（B 级源）
  parse_rules     TEXT          JSON：列表选择器/详情选择器/表格选择器
  enabled         BOOLEAN
  created_at/updated_at/deleted

sparkora_source_job                   -- 采集任务表（仿 sparkora_news_sync_job）
  id BIGSERIAL PK
  source_id       BIGINT REFERENCES sparkora_source(id)   -- 单源任务
  job_type        VARCHAR(20)   SCHEDULED|MANUAL|RETRY
  status          VARCHAR(20)   RUNNING|SUCCESS|PARTIAL|FAILED
  total/success/failed INTEGER
  failed_items    TEXT          JSON:[{externalId,title,error}]
  started_at/finished_at
  error_msg       VARCHAR(1000)
  created_by      VARCHAR(64)
  created_at/deleted

-- 复用（不新建）：
sparkora_news       + source_id BIGINT NULL REFERENCES sparkora_source(id)   -- NULL=byd（存量）
                      news_id 对非 BYD 源 = source.externalId（派生，满足 UNIQUE）
sparkora_news_doc   结构不变（domain=NEWS 向量 refId 来源）
sparkora_news_doc_embedding  结构不变
```

- 存量 BYD 行 `source_id=NULL` → E 回填时按 `source='byd-news'` 认成 `sourceType=byd-news`。
- `news_id` 唯一性：BYD 保持官方字符串 id；其他源用 `sourceId + ":" + externalId` 前缀化，避免跨源撞 `news_id`。
- **既有 `/api/news` 必须加 `source` 过滤（P1，数据隔离 — 评审新增）**：`NewsService.list/get`（`NewsService:256-285`）现查全表、无来源过滤；
  复用 `sparkora_news` 后工信部/乘联会/盖世内容会被 `/api/news` 一并列出，污染「新闻=BYD」语义。修正：`list` 默认
  `source='byd-news'`（或 `source_id IS NULL`）；若要浏览通用信源走 B-R9 的 `/api/source-contents`。`get(id)` 同理校验来源。

## 3. 调度：动态注册（`@Scheduled` 不满足）

`NewsSyncScheduler` 的 `@Scheduled(cron="${...}")` 是**编译期固定**，无法「每源不同 cron + 运行时增删」。
`SparkoraApplication` 已有 `@EnableScheduling`（:18）；Spring Boot 自动装配一个 `TaskScheduler`。

**决策**：`SourceScheduleService` 持有 `Map<Long, ScheduledFuture<?>>`，启动时（`ApplicationRunner`）
与信源增删改时：
- `enabled && cron` 有值 → `taskScheduler.schedule(task, new CronTrigger(cron))`，把 `ScheduledFuture` 存入 map；
- 停用/改 cron → `future.cancel(false)` 后按新 cron 重注册。

**发布窗口（P2-3）**：`window_start_day`/`window_end_day` 有值时注册**每日 cron**（如 `0 30 3 * * ?`），
任务执行时判断「今日在窗口内 && 本批未完成」才真正采集；成功后置本批完成标记（按 `(sourceId, 年月)`），窗口内剩余日跳过；失败次日重试。无窗口的源用其自身 `cron`（单点）。

**并发红线**：所有源**全局串行**——调度触发只创建任务（照 `NewsSyncScheduler` 模式：
`markStaleRunningAsFailed()` → `hasFreshRunning()` 有则跳过 → `createJob`），实际采集由单一执行器串行消费；
**不得每源 `@Async` 并发**（父任务资源红线：Crawl4AI 并发≤2；频控按通道区分见父 PRD D9）。

**防重叠**：复用 `markStaleRunningAsFailed`（60 分钟陈旧自愈）+「同源运行中跳过」。

## 4. 客户端与解析

- `SourceClient.list(source) → List<SourceItem{externalId,title,url,publishDate,tags}>`；
  `SourceClient.detail(source, item) → SourceContent{text, html, tables?}`。
- `RssSourceClient`：jsoup `Parser.xmlParser()` 解析 RSS/Atom（`item`/`entry`，`pubDate`/`published` 容错），**不新增依赖**。
- `SiteSourceClient`：列表页选择器定位条目、详情选择器抽正文；选择器来自 `parse_rules`（集中配置，仿 `NewsContentParser`），
  站点改版只改一条记录，**不做通用智能抽取**。
- `SourceTableParser`（父 §6）：命中 `parse_rules.tables` 时把 `<table>` 转成逐行文本（`列1 | 列2 | 列3`），
  **不**交给 `TextChunker` 的「段内换行转空格」处理。表格文本作为 `content` 的一部分入库。
- 抓取经 C 的 transport；`need_crawl4ai && !crawlAvailable()` → 跳过并记降级原因（不失败整个任务）。

## 5. 幂等与容错

- `sparkora_news` upsert key = `(source_id, news_id)`；重复采集同 `externalId` → 更新 `content`/`last_sync_at`，不新增行。
- 单条详情失败 → 进 `failed_items`，其余继续（照 `NewsService` 容错）。
- 正文空 → 仍入库元数据（沿用新闻容错，E 端空正文只保留标题块）。
- 任务状态：全失败=FAILED、部分失败=PARTIAL、全成=SUCCESS；`markStaleRunningAsFailed` 防永久 RUNNING。

## 6. 接口契约

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/sources` | 登录 | 列表（含 enabled/tier/最近任务态） |
| GET | `/api/sources/{id}` | 登录 | 详情 |
| PUT | `/api/sources/{id}` | ADMIN/EDITOR | 编辑/启停（触发调度重注册） |
| POST | `/api/sources/{id}/collect` | ADMIN/EDITOR | 手动触发，返回 `{jobId}` |
| GET | `/api/source-jobs` | 登录 | 进度/历史 |
| POST | `/api/source-jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项 |
| GET | `/api/source-contents` | 登录 | 内容列表（分页/`keyword`/`category`/`sourceId`）——**U 前置** |
| GET | `/api/source-contents/{id}` | 登录 | 内容详情（正文/切块数/原文链接） |

全部 `R<T>` 包装 + 方法级 `@PreAuthorize`；写操作 DTO + `@Valid`。

## 7. 配置（`.env`，URL 类键 `_BASE_URL` 结尾）

| 键 | 默认 | 说明 |
|---|---|---|
| `SOURCE_COLLECT_ENABLED` | `false` | 总开关，默认关=零回归 |
| `CRAWL4AI_BASE_URL` | 空 | C 的通道地址（B 只读） |
| `SOURCE_MAX_CONCURRENCY` | `2` | 全局采集并发（红线） |
| `SOURCE_PER_SITE_DAILY_MAX` | `2` | 同站每天上限（红线） |

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 新表 id 与 `news_doc.id` 撞（向量覆盖） | §2.1 共享 `sparkora_news_doc` id 空间；设计即约束 |
| 选择器随改版失效 | `parse_rules` 集中配置 + 单源停用不影响他源 |
| 动态调度在信源热更新时漏注册/重复 | 单测覆盖「注册→改 cron→注销」；map 以 sourceId 为键天然幂等 |
| 采集打爆站点资源 | 全局串行 + 并发≤2 + 同站≤2/天 |
| 表格内容被压平 | `SourceTableParser` 先行转行文本 |
| 存量 BYD 行为回归 | `sparkora_news` 仅新增可空 `source_id`，BYD 同步路径不改；`mvn test` 全绿 |

## 9. 回滚

无代码引用则默认关（`SOURCE_COLLECT_ENABLED=false`）即零回归。全量回滚 = 停开关 + `git revert` 迁移
（新增表可 `DROP`，`sparkora_news.source_id` 为可空列不动数据）。
