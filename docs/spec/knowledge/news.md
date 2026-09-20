# 知识域 · 官方新闻（NEWS）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：比亚迪官方新闻采集 → 清洗入库 → 切块向量化，作为与车型（CAR）/通用知识（KB）并列的**第三知识域 NEWS** 接入统一检索。**新闻与车型不关联**。

> C2 正式规格（2026-09-11）。父任务：`09-11-knowledge-base-data-foundation`；依赖 C1（复用同步任务/调度范式与统一检索改动，见 [car.md](car.md)）。

---

## 1. 数据模型（schema.sql S11 区块，幂等 `CREATE TABLE IF NOT EXISTS`）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_news` | id / **news_id VARCHAR(200) UNIQUE**（官方字符串 id，业务唯一键）/ title(≤500) / url / image_url / publish_date / tags(JSON) / tag_names(JSON) / content / source(默认 `byd-news`) / sync_status(`SUCCESS`/`FAILED`) / last_sync_at / last_sync_error / **cover_image_id BIGINT**（09-15 img-classify 幂等补列：封面图对应的图库 asset id，可空不建外键）/ created_at / updated_at / deleted | 新闻主表；逻辑删。索引 `idx_news_publish(publish_date)` / `idx_news_status(sync_status)` |
| `sparkora_news_doc` | id / **news_id BIGINT FK→sparkora_news(id)**（内部 id）/ seq / chunk_type(`NEWS_BODY`/`NEWS_TITLE`) / chunk_text / token_count / created_at / updated_at / deleted | 检索块；首行固定「新闻：<title>（<publishDate>）」。索引 `idx_news_doc_news` |
| `sparkora_news_doc_embedding` | id / doc_id FK / news_id FK / embedding VECTOR(1024) / created_at | 物理表（无 `deleted`）；HNSW cosine `idx_news_doc_emb_vec` + `idx_news_doc_emb_news` |
| `sparkora_news_sync_job` | id / job_type(`FULL`/`INCREMENT`/`SCHEDULED`/`RETRY`) / status(`RUNNING`/`SUCCESS`/`PARTIAL`/`FAILED`) / total / success / failed / failed_items(JSON:`[{newsId,title,error}]`) / started_at / finished_at / error_msg / created_by / created_at / deleted | 同步任务表（复用车型任务表范式）。索引 `idx_news_sync_job_created` |

> 命名注意：`sparkora_news.news_id` 是**官方字符串 id**；`sparkora_news_doc.news_id` 是**内部 BIGINT 外键**。实体：`NewsEntity.newsId`(String) vs `NewsDocEntity.newsId`(Long)。

---

## 2. 采集与解析

- `com.sparkora.news.client.BydNewsClient`：`searchPage(page,size)` POST `{listUrl}`（body 固定 `{brandName:"byd",siteName:"cn",type:"news",page,size,sortField:"date",year:""}`，带 `Referer: https://www.byd.com/`，读 `data`）；`fetchDetailHtml(url)` GET `{detailBaseUrl}{url}`。`RestClient`，超时读 `NEWS_TIMEOUT_MS`，失败抛 `AiException`。
- `com.sparkora.news.service.NewsContentParser`（jsoup 1.18.1，选择器集中于此）：标题 `.cmp-news__detail-title`；日期 `.cmp-news__detail-date`（如「发布于 2026-09-01 17:08:31」）；正文容器 `.cmp-news__detail-content`，段落 `.news-text p`（`<br>`→换行），图片 `.news-image img`（仅记 src，以 `[图片] <src>` 并入正文，**不下载**）。解析失败降级空正文，不抛。
- 图片型/无正文新闻：`content` 为空仍入库元数据；切块保留标题锚点块（`NEWS_TITLE`），无标题则跳过切块。

---

## 3. 入库与向量化

- `com.sparkora.news.service.NewsDocService`（仿 `CarDocService`/`KbDocService`）：`rebuildForNews(newsId)` 先物理清 embedding+doc 再切块（首行「新闻：<title>（<publishDate>）」；空行分段、单段 ≤500、超长按句读切分合并）+ embedding 并发化（固定小线程池）+ 单块失败重试 1 次；`deleteByNews` 物理清块与向量；`chunkTypeOf(chunks)` 纯函数判定块类型（唯一块且无换行 → `NEWS_TITLE`，其余 `NEWS_BODY`）；块数由调用方 `docMapper.selectCount` 计算，不再提供 `chunkCount(newsId)`/`NewsDocEmbeddingMapper.countByNews()`（C2 死代码已删）。
- `com.sparkora.news.service.NewsService`：
  - `syncFull()`（遍历 `data.pages` 全部页）/ `syncIncrement()`（列表按 date 倒序，本页全部「已存在且正文非空」即提前停止）；逐条抓正文 → 按 `news_id` 幂等 upsert → `rebuildForNews`；单条失败记 `failedItems` 不阻断；`list(page,size,keyword)`（分页 + title 模糊 + 块数）、`get(id)`、`existsWithContent(newsId)`。官方 date 解析失败置 null。
  - **09-13 image-tags 起**：`upsertOne` 另下载 `imageUrl` 封面字节走统一入库管线转存图库（`source=byd-news`、标签「新闻」，`sparkora_news.image_url` 保留原 URL 留痕）；**单图下载失败仅告警不阻断新闻入库**（同车型图容错先例）。
  - **09-15 img-classify 起**：封面入库携带 `NewsImageClassifier.toTagsFrom(title, publishDate)` 派生的 `主题/<名>` 与 `年份/<年>` 标签及 `sourceRef=news_id`；入库成功后回填 `cover_image_id`（`null` 或指向已失效图时才写，不覆盖有效值）；`list`/`get` 另填非持久化 `coverImageUrl`（图库公网 URL）与 `themes`（标题分类主题，同分类器重算不查图库）。
- 主题分类词表与标签命名空间见 [image.md §3](../image.md)。

---

## 4. 同步任务与调度

- `com.sparkora.news.service.NewsSyncJobService`（仿 `CarSyncJobService`）：`createJob(jobType)`（`@Transactional` 落 RUNNING，`created_by=SecurityUtil`）；`@Async runJob(jobId)`（原子锁 `status=RUNNING→RUNNING` 影响行数=0 拒绝）；`finish`（`SUCCESS`/`PARTIAL`/`FAILED` + `failed_items` JSON）；`get`/`list`/`retry`/`hasFreshRunning`/`markStaleRunningAsFailed`。
- `com.sparkora.news.service.NewsSyncScheduler`：`@Scheduled(cron="${sparkora.news.sync-cron:0 30 3 * * ?}")`，`NEWS_SYNC_ENABLED=false` 直接返回、先 `markStaleRunningAsFailed()` 清理陈旧 RUNNING 再 `hasFreshRunning()` 防重叠、全程 try/catch，`jobType=SCHEDULED`。默认关闭。
- **定时同步陈旧自愈（kb-cleanup，2026-09-12）**：任务表无 `updated_at`，以 `started_at` 为存活时间戳，阈值 `SYNC_STALE_MS=60 分钟`（全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会误判活任务）。`markStaleRunningAsFailed()` 将 `status=RUNNING 且 started_at < now-60min` 原子置 `FAILED` + `finished_at` + `error_msg='运行超时判定为陈旧,自动终止'`；`hasFreshRunning()` 只统计 `RUNNING 且 started_at >= now-60min`。JVM 中途死亡残留不再永久阻塞定时任务；未过期 RUNNING 仍阻塞（防重叠不回归）。车型（`CarSyncJobService`/`CarSyncScheduler`）与新闻两侧对称实现。手动 `createJob`/`runJob` 的 `status=RUNNING→RUNNING` 原子锁语义不变。

---

## 5. 统一检索接入（C2 跨层关键改动）

- `CarDocEmbeddingMapper.searchTopKUnified`：在既有 CAR + KB 两段 UNION 后**追加第三段 NEWS**（`source='NEWS'`、`modelId=NULL`、`modelName=n.title`、JOIN `sparkora_news_doc d ... d.deleted=0` 与 `sparkora_news n ... n.deleted=0`）；既有两段语义不变。**候选窗口按域隔离（C2 check 修复）**：CAR+KB 合并取 top-`limit`（与 C2 前完全一致），NEWS 单独取 top-`limit`；不可三者共用一个全局 `LIMIT`——新闻块（≈1300+）与车型/KB 同向量空间且语义邻近时会占满整个窗口，把 CAR/KB 完全挤出候选（实测 BYD 新闻类 query CAR 候选从 32 掉到 0），使下游独立配额失效。
- `CarRagService.retrieveForGeneration`：新增 NEWS 候选池 + 独立配额 `AI_RAG_NEWS_TOPK`（默认 4，`0` 关闭 NEWS 注入）；**NEWS 不受 `AI_RAG_KB_ENABLED` 控制**；行内标注「【官方新闻：<title>】」；首行 `sourceLine` 支持三域组合（车型数据 / 通用知识库 / 官方新闻）；**锚点加权仅对 `source=CAR` 生效**（NEWS/KB 不变）；`coveredText` 仅统计 CAR 参数块；citations 纳入 NEWS（`source=NEWS`）。**不改 `RagStatus` 四态语义与既有 CAR/KB 行为**；主查询过采样沿用 C2 前口径 `max(topK*4,32)`（候选窗口隔离由 mapper 负责，无需额外余量）。

---

## 6. 接口契约（`/api/news`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/news` | 三角色 | 分页列表 `?page&size&keyword`，`PageResult`（含 id/title/publishDate/tagNames/imageUrl/chunkCount；**09-15 img-classify 起另含 `coverImageUrl`**（图库公网 URL，无同步封面时为 null）**与 `themes`**（标题分类命中主题，保序数组）；列表/详情均携带） |
| GET | `/api/news/{id}` | 三角色 | 详情（含 `content`）；不存在 `R.fail(404)` |
| POST | `/api/news/sync/jobs` | ADMIN/EDITOR | body `{jobType:"FULL"\|"INCREMENT"}`（缺省 INCREMENT），返回 `{jobId}` |
| GET | `/api/news/sync/jobs/{id}` | 三角色 | 任务进度；不存在 `R.fail(404)` |
| GET | `/api/news/sync/jobs` | 三角色 | 任务历史（按 id 倒序） |
| POST | `/api/news/sync/jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项，返回新任务 `{jobId}` |

---

## 7. 配置（三处同步）

| `.env` 变量 | 默认 | 用途 |
|---|---|---|
| `NEWS_LIST_URL` | `https://cms-api.byd.com/es/search` | 列表接口 |
| `NEWS_DETAIL_BASE_URL` | `https://www.byd.com` | 详情页站点前缀 |
| `NEWS_TIMEOUT_MS` | `30000` | 采集读超时 |
| `NEWS_PAGE_SIZE` | `12` | 列表分页大小 |
| `NEWS_SYNC_ENABLED` | `false` | 定时增量开关 |
| `NEWS_SYNC_CRON` | `0 30 3 * * ?` | 定时 cron |
| `AI_RAG_NEWS_TOPK` | `4` | 新闻域生成注入块数上限（0=关闭 NEWS 注入） |

---

## 8. 前端

- `views/knowledge/NewsKnowledgePanel.vue`（知识中心新闻 Tab）：分页列表 + 详情抽屉（正文 pre-wrap / 官方原文 `https://www.byd.com`+`url` / 切块数）；`tagNames` JSON 容错；`content` 空显示图片型提示；EDITOR 及以上 FULL/INCREMENT 同步 + 2s 轮询。
- 封面与主题标签（09-15 img-classify）：封面 URL 取 `coverImageUrl || resolveUrl(imageUrl)`（图库图优先，官网原始 URL 回退）；卡片/详情展示 `themes`，**点标签跳图库并按 `主题/<名>` 筛选**（见 [image.md §8](../image.md)）。

---

## 9. 关键实现路径

- 后端：`com.sparkora.news.client.BydNewsClient`、`news.service.{NewsContentParser,NewsService,NewsDocService,NewsSyncJobService,NewsSyncScheduler}`、`news.classify.NewsImageClassifier`、`web.controller.NewsController`、`mapper.NewsDocEmbeddingMapper`。
- 前端：`views/knowledge/NewsKnowledgePanel.vue`、`api/index.js` 的 `newsApi`。
- 表：`sparkora_news` / `sparkora_news_doc` / `sparkora_news_doc_embedding` / `sparkora_news_sync_job`。

---

## 10. 验收清单

- [ ] AC1 全量历史（≈167 篇）+ 增量可抓取，正文抽取入库，按官方 id 幂等重跑不重复
- [ ] AC2 新闻作为独立知识域进入向量库，可被统一检索命中
- [ ] AC3 检索来源标注能区分 NEWS（`【官方新闻：…】`）
- [ ] AC4 手动与定时增量同步均可用
- [ ] AC5 图片型/无正文新闻不阻断流程（元数据入库，切块容错）
- [x] AC6 `mvn -q -DskipTests compile` 通过；单元测试 59 全绿（2026-09-11 implement 复验 ✓）；创作生成链路回归：2026-09-12 check 真机跑 `/deep/run`+`/deep/generate` 成功（无 500，rag 输出 `car=2 news=4`，NEWS 参与注入未挤占车型域）

---

## 11. 已知限制

- WEB 命中为摘要级，不做正文抓取（`CRAWL4AI_*` 未接入）。
- 新闻正文内嵌图**不入库**（图片型新闻多为装饰长图，量级/噪音风险，范围外）；仅封面入库。
