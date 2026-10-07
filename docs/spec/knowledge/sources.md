# 知识域 · 通用信源采集基座（SOURCE）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md) ｜ 相关：[官方新闻（NEWS）](news.md) ｜ [图库](../image.md)
> 任务：`10-05-source-crawl-base`（B）。父任务设计：[`10-05-self-hosted-sources/design.md`](../../../.trellis/tasks/10-05-self-hosted-sources/design.md)。

职责：把「单一 BYD 新闻采集」泛化为**可配置、多信源、按发布周期排期**的采集基座。信源以数据/配置注册，
支持 `type=RSS|SITE` 混合形态；一源多栏目；每源独立 cron 或发布窗口；采集产出规范化去重 + 原始留存 +
幂等 upsert；有任务表与失败明细供监控重试。**采集产物复用 NEWS 域（`sparkora_news*`）**，切块/向量化归
`10-05-source-domain-retrieval`（E）。

---

## 1. 数据模型（Flyway `db/migration/V13__source_registry.sql`）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_source` | id / name(≤100) / **type**(`RSS`/`SITE`) / vertical(≤30，预留) / **cron**(≤50) / **window_start_day**/**window_end_day**(SMALLINT，可选发布窗口) / authority_tier(`official`/`industry`/`media`/`ugc`，F 用，默认不启用分档) / need_crawl4ai(BOOLEAN，源级默认) / enabled / **last_batch_key**(发布窗口本批完成标记) / created_at / updated_at / deleted | 信源注册表（站点/机构级）。源级公共字段 + 排期 |
| `sparkora_source_channel` | id / source_id FK / name(≤100) / **list_url**(≤500) / **detail_base_url**(≤500) / **category**(≤30) / **parse_rules**(TEXT JSON) / need_crawl4ai(BOOLEAN NULL=继承源级) / enabled / created_at / updated_at / deleted | 栏目表（一个源 ≥1 个列表页/feed）。列表地址/分类/选择器**下沉到栏目级** |
| `sparkora_source_job` | id / source_id FK / channel_id FK NULL(=整源) / job_type(`SCHEDULED`/`MANUAL`/`RETRY`) / status(`RUNNING`/`SUCCESS`/`PARTIAL`/`FAILED`) / batch_key / total / success / failed / **degraded** / failed_items(JSON) / started_at / finished_at / error_msg(≤1000) / created_by / created_at / deleted | 采集任务表（仿 `sparkora_news_sync_job`） |
| `sparkora_news`（复用，非新表） | 新增 **source_id** BIGINT NULL FK→`sparkora_source` / **channel_id** BIGINT NULL FK→`sparkora_source_channel` / **category** VARCHAR(30) | 通用信源采集产物写入此处；存量 BYD 行 `source_id=NULL` |

- 幂等键 = **`(sourceId, channelId, externalId)`**（同站不同栏目独立去重）。非 BYD `sparkora_news.news_id` 由 `"<sourceId>:<channelId>:<externalId>"` 派生（满足既有 UNIQUE）。
- **共享 id 空间（关键，父 design §2.1/§2.5.1）**：`domain=NEWS` 向量 `refId` = `sparkora_news_doc.id`；通用信源采集产物**复用 `sparkora_news` + `sparkora_news_doc`**，不新建 `sparkora_source_doc`——否则新表自增 id 会与 `sparkora_news_doc.id` **撞号**（`VectorStoreService.docId("NEWS", n)` 产生同一 UUID → 向量互相覆盖）。
- BYD 新闻 = `source='byd-news'`（存量 `source_id IS NULL`）的**特例**，行为零回归（见 [news.md](news.md)）。

### `parse_rules` JSON（栏目级，选择器集中配置）

```json
{
  "list":   ".news-list li",   // 列表条目容器选择器
  "link":   "a",               // 条目内链接选择器（取 href）
  "title":  "a",               // 条目内标题选择器
  "date":   ".date",           // 条目内日期选择器（可选）
  "detail": ".article-content",// 详情正文容器选择器
  "tables": "table",           // 正文内表格选择器（转保留行列文本）
  "images": "img",             // 正文内图片选择器（配图转存）
  "bydOnly": false             // true=只保留标题/链接含 BYD/比亚迪的条目（工信部申报等）
}
```

- 解析规则类 `com.sparkora.source.client.SourceParseRules`；**非法/缺失 JSON 降级为空规则，不抛**。
- 站点改版只改一条 channel 记录的选择器，**不做通用智能抽取**。

---

## 2. 采集客户端与解析

- `com.sparkora.source.client.SourceClient`：`list(html, channel) → List<SourceItem{externalId,title,url,publishDate,tags}>`；`detail(html, channel, item) → SourceContent{text,html,imageUrls}`。**按栏目驱动**（每 channel 用各自 `parse_rules`/`category`）。
- `RssSourceClient`（`type=RSS`）：jsoup `Parser.xmlParser()` 解析 RSS 2.0（`item`/`guid`/`pubDate`）与 Atom（`entry`/`id`/`published`/`link href`），**不新增依赖**；`pubDate` 缺失/格式异常容错为 null。
- `SiteSourceClient`（`type=SITE`）：列表页选择器定位条目、详情选择器抽正文；**BYD 优先过滤**（`parse_rules.bydOnly=true` 只保留标题/链接含 BYD/比亚迪的条目，其他车企仅按需保留字段，不做通用 NLP 判定）。
- `SourceTableParser`（`source/service`）：**HTML 表格 → 保留行列语义的逐行文本**（`列1 | 列2 | 列3`，空单元格保留占位），先于 `TextChunker` 的「段内换行转空格」处理，避免数据型源（乘联会销量表等）数值/行列被压平丢失（父 design §6）。
- 抓取经 C 的 `FetchTransport`（`com.sparkora.source.fetch`）：A 级源走 `HttpFetchTransport`；`need_crawl4ai` 栏目走 `Crawl4aiFetchTransport`，**未配置时降级跳过并记原因**（`degraded`），不 fallback 到 HTTP 硬闯。

---

## 3. 采集编排与容错（`SourceCollectService`）

1. 遍历源的 **enabled channels**（可限定单个 `channelId`）→ 每 channel 抓列表 → 逐条抓详情 → 解析 → 配图转存 → upsert `sparkora_news`。
2. **幂等 upsert**：按派生 `news_id` 命中则更新（含 `content`/`last_sync_at`），不新增行。
3. **正文空仍入库元数据**（沿用新闻容错，E 端空正文只保留标题块）。
4. **单条详情失败进 `failed_items` 不阻断其余**；列表抓取被限流/未配置 → `degraded`（不算失败，任务可 PARTIAL 而非 FAILED）。
5. 日期解析（尽最大努力，失败置 null）：`yyyy-MM-dd[ HH:mm[:ss]]` / `yyyy/MM/dd` / ISO_OFFSET / RFC_1123；日期解析在编排层。

---

## 4. 任务与失败明细（`SourceJobService`）

- `createJob(sourceId, channelId, jobType)`：同步落 RUNNING（`created_by=SecurityUtil`）→ `@Async runJob`（自注入 `@Lazy self` 触发）→ 全局串行锁内采集 → 终态。
- 终态：`success>0 && failed==0 && degraded==0` → `SUCCESS`；`success>0` 且有失败/降级 → `PARTIAL`；`success==0` → `FAILED`。
- **陈旧自愈**：`markStaleRunningAsFailed()` 将 `status=RUNNING 且 started_at < now-60min` 原子置 `FAILED`；`hasFreshRunning()`/`hasFreshRunning(sourceId)` 只统计新鲜 RUNNING（阈值 60 分钟，同 `NewsSyncJobService`）。
- `retry(jobId)`：从 `failed_items` 取 externalId，创建 `RETRY` 任务（整源重采；幂等）。
- **全局串行**：所有源/触发路径共用一把 `ReentrantLock`；同源运行中跳过。**不每源 `@Async` 并发**（父任务资源红线）。

---

## 5. 动态调度与发布窗口（`SourceScheduleService`）

- `@Scheduled` 的 cron 是**编译期固定**的，无法表达「每源独立且可运行时增删」。改为 `TaskScheduler` + `CronTrigger`：`Map<sourceId, ScheduledFuture<?>>`，启动（`SourceScheduleRunner`，`@Order(30)`）与信源变更时按注册表**逐源注册/注销**（map 以 sourceId 为键天然幂等，重复注册先 cancel）。
- **总开关** `SOURCE_COLLECT_ENABLED=false`（默认）：不注册任何 trigger、不发起采集（零回归）。
- **发布窗口**：`window_start_day`/`window_end_day` 非空时注册**每日 cron**（`0 30 3 * * ?`）；执行时判断「今日在窗口内**且**本批未完成」才采集；成功后按 `(sourceId, 年月)` 写入 `last_batch_key`，窗口内剩余日跳过；失败/部分失败次日重试。**非窗口期不触发**。无窗口的源退回其自身 `cron`（单点）。
- 触发入口 `runScheduled(sourceId)`：`markStaleRunningAsFailed()` → 同源 `hasFreshRunning` 有则跳过 → `createJob(...SCHEDULED...)`。

---

## 6. 接口契约（全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/sources` | 三角色 | 信源列表（含 `enabled`/`channelCount`） |
| GET | `/api/sources/{id}` | 三角色 | 详情（含 **`channels[]`**）；不存在 `R.fail(404)` |
| PUT | `/api/sources/{id}` | ADMIN/EDITOR | 编辑源级字段（`@Valid SourceUpdateDTO`；触发调度重注册） |
| POST | `/api/sources/{id}/collect` | ADMIN/EDITOR | 手动触发采集（body `{channelId?}`），返回 `{jobId}` |
| GET | `/api/source-jobs` | 三角色 | 任务历史/进度（`?sourceId` 可选） |
| GET | `/api/source-jobs/{id}` | 三角色 | 任务进度；不存在 `R.fail(404)` |
| POST | `/api/source-jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项（无失败项 `R.fail(400)`） |
| GET | `/api/source-contents` | 三角色 | 内容列表（分页 `page/size` + `keyword` + `category` + `sourceId`）；**U 前置** |
| GET | `/api/source-contents/{id}` | 三角色 | 内容详情（含正文 / 切块数 / 原文 url）；不存在 `R.fail(404)` |

`SourceContentDTO` 字段：`{id, sourceId, channelId, title, url, publishDate, category, source, chunkCount, content?}`。
**`source=byd-news` 条目**（`/api/news` 的存量）同样可经本接口读取，携带 U 条件渲染所需字段（`chunkCount`、`url`）。
**数据隔离**：`/api/news` 只返回 `source='byd-news'`（或 `source_id IS NULL`）的内容；通用信源内容仅经本接口暴露（不泄漏进「新闻=BYD」语义）。

---

## 7. 正文配图转存（B-R10，父 design §5.5）

- `SiteSourceClient` 详情解析抽取正文图片（选择器 `parse_rules.images`）；`SourceImageService.transfer(...)` 逐图经 `ImageService.saveExternalImage` 转存图库。
- 图库 `source="source"`（`ImageService.SOURCES` 白名单新增）；`sourceRef` = 该内容的**派生 `news_id`**（供 E 反查标题）。
- **相对 URL 按栏目 `detail_base_url` 解析**（`ImageService` 新增可传基址的重载；`resolveBydUrl` 保留给 BYD 旧路径，零回归）——否则跨源相对图链会被拼成 `www.byd.com` 域而下载失败。
- **单图失败 warn 跳过、不阻断本条/本任务**（照 `NewsService` 封面图容错）；不做视频入库/反盗链/水印/版权审核。
- 图片嵌入文本分派（E 侧）：`source` 与 `byd-news` 同模式（来源标题 + 标签），见 [image.md](../image.md)。

---

## 8. 配置（三处同步：`.env.example` / `application.yml` / 本节）

| `.env` 变量 | 默认 | 用途 |
|---|---|---|
| `SOURCE_COLLECT_ENABLED` | `false` | 采集总开关（默认关=零回归） |
| `SOURCE_MAX_CONCURRENCY` | `2` | 全局采集并发（资源红线） |
| `SOURCE_PER_SITE_DAILY_MAX` | `2` | 同站每天上限（资源红线；Crawl4AI 侧由 C 强制 `CRAWL4AI_PER_HOST_DAILY_LIMIT`） |

- `CRAWL4AI_BASE_URL` / `CRAWL4AI_*` 由 C 的 `Crawl4aiProperties` 持有，B 只读（未配置时 B 级栏目降级）。
- 配置绑定类：`com.sparkora.config.SourceProperties`（`prefix=sparkora.source`）。HTTP 通道无硬日上限（仅 `SOURCE_HTTP_MIN_INTERVAL_MS` 同 host 最小间隔）。

---

## 9. 关键实现路径

- 后端：`com.sparkora.source.client.{SourceClient,RssSourceClient,SiteSourceClient,SourceParseRules,SourceItem,SourceContent}`、`source.service.{SourceTableParser,SourceCollectService,SourceJobService,SourceScheduleService,SourceScheduleRunner,SourceImageService,SourceService,SourceContentService}`、`web.controller.SourceController`、`config.SourceProperties`、`domain.entity.{SourceEntity,SourceChannelEntity,SourceJobEntity}`。
- 复用：`com.sparkora.source.fetch.FetchTransport`（C）、`com.sparkora.service.ImageService`（图库转存）、`com.sparkora.news.service.NewsDocService`（E 的切块入口）。
- 表：`sparkora_source` / `sparkora_source_channel` / `sparkora_source_job`；采集产物落 `sparkora_news`（+ `sparkora_news_doc`）。

---

## 10. 验收清单

- [x] AC-B1 注册与采集：工信部（A 级 HTTP）可采；乘联会（B 级需 Crawl4AI）未配置时降级、配置后可采；幂等重跑不重复
- [x] AC-B11 一源多栏目：两栏目各自 parse_rules/category 采集、独立去重（同 externalId 跨栏目不同条目）、独立降级；源详情返回 `channels[]`；单栏目源等价旧行为
- [x] AC-B2 每源排期：动态 `TaskScheduler`+`CronTrigger`（非 `@Scheduled`）；增删改热注册/注销；同源运行中跳过
- [x] AC-B8 发布窗口：窗口内每日触发、成功后本批跳过、失败次日重试、非窗口不触发
- [x] AC-B9 内容查询 API：分页/关键词/category/sourceId 筛选；详情含正文与切块数；`source=byd` 字段齐备；viewer 可读、写 403
- [x] AC-B10 配图转存：`source="source"`/`sourceRef` 可反查；相对链按 `detail_base_url` 解析（断言非 byd.com）；单图失败不阻断；无图源不变
- [x] AC-B7 结构化解析：表格转行文本，数值/行列不丢
- [x] AC-B3 RSS 与 SITE 双形态；RSS 解析不新增依赖
- [x] AC-B4 容错：单条失败记 `failed_items` 其余继续；正文空仍入库；陈旧 RUNNING 超 60 分钟自动 FAILED
- [x] AC-B5 任务可见：`/api/source-jobs` 查进度/失败明细，失败项可重试
- [x] AC-B6 零回归：不改 `sparkora_news*` 表语义；BYD 同步不变；`/api/news` 只返回 `source=byd-news`；`mvn test` 全绿（978；2026-10-08 check 复验）

---

## 11. 已知限制 / 后续

- **切块/向量化/检索接入**（E）与**信源管理 UI**（U）不在本任务范围；本文只落注册表 + 任务表 + 原始内容。
- Crawl4AI 客户端实现属 C；B 只在未配置时降级。
- 站点选择器随改版易腐：集中配置便于单源修复，**不做通用智能抽取**。
- 工信部公示列表页准确路径首次接入需人工定位一次，之后固定写进源配置。
