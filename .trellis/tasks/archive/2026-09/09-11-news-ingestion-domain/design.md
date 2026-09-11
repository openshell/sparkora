# Design — C2 新闻数据接入与独立知识域

> 父任务：`09-11-knowledge-base-data-foundation`。遵循父 `design.md` §3.1/§3.2/§3.4/§3.6 契约。

## 1. 架构与边界

```
比亚迪官方源                             清洗标准化                     统一知识基座
────────────────────                    ──────────                    ────────────────────────
POST /es/search (列表,分页)  ──┐
                              ├─ NewsClient ─▶ NewsService ─▶ sparkora_news (幂等 upsert by news_id)
GET www.byd.com{url} (详情SSR)─┘        │         │
                              jsoup 抽取 │         └─▶ NewsDocService 切块 ─▶ news_doc
                        .cmp-news__detail-content                └─embedding─▶ news_doc_embedding (NEWS 域)
                                                                                       │
                                              CarDocEmbeddingMapper.searchTopKUnified ─┘ (CAR+KB+NEWS UNION)
                                                              CarRagService 来源标注【官方新闻：<title>】
```

**边界**：本子任务只负责「采集→清洗→入库→向量化→接入统一检索→REST」。浏览页 UI 属 C3；问答属 C4。新闻与车型**不关联**。

## 2. 数据模型（新增 4 表，幂等 DDL）

```sql
-- 新闻主表
CREATE TABLE IF NOT EXISTS sparkora_news (
    id            BIGSERIAL PRIMARY KEY,
    news_id       VARCHAR(200) NOT NULL UNIQUE,   -- 官方 id,如 /page/byd-cn/news-2026/detail634
    title         VARCHAR(500) NOT NULL,
    url           VARCHAR(500),                   -- 官方相对路径 /cn/detail634
    image_url     VARCHAR(500),                   -- 封面(相对或绝对)
    publish_date  TIMESTAMP,                      -- 官方 date 解析
    tags          TEXT,                           -- JSON 数组(官方 tags)
    tag_names     TEXT,                           -- JSON 数组(官方 tagNames,展示用)
    content       TEXT,                           -- 抽取正文纯文本(可能为空:图片型新闻)
    source        VARCHAR(50)  DEFAULT 'byd-news',
    sync_status   VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    last_sync_at  TIMESTAMP,
    last_sync_error VARCHAR(1000),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_news_publish ON sparkora_news(publish_date);
CREATE INDEX IF NOT EXISTS idx_news_status ON sparkora_news(sync_status);

-- 新闻切块表(检索单元;首行带新闻标题锚点)
CREATE TABLE IF NOT EXISTS sparkora_news_doc (
    id          BIGSERIAL PRIMARY KEY,
    news_id     BIGINT NOT NULL REFERENCES sparkora_news(id),  -- 内部 FK
    seq         INT NOT NULL,
    chunk_type  VARCHAR(20) NOT NULL DEFAULT 'NEWS_BODY',
    chunk_text  TEXT NOT NULL,
    token_count INTEGER,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_news_doc_news ON sparkora_news_doc(news_id);

-- 新闻向量表(pgvector 1024,HNSW)
CREATE TABLE IF NOT EXISTS sparkora_news_doc_embedding (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT NOT NULL REFERENCES sparkora_news_doc(id),
    news_id     BIGINT NOT NULL REFERENCES sparkora_news(id),
    embedding   VECTOR(1024),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_news_doc_emb_news ON sparkora_news_doc_embedding(news_id);
CREATE INDEX IF NOT EXISTS idx_news_doc_emb_vec ON sparkora_news_doc_embedding USING hnsw (embedding vector_cosine_ops);

-- 新闻同步任务表(复用车型任务表字段范式)
CREATE TABLE IF NOT EXISTS sparkora_news_sync_job (
    id           BIGSERIAL PRIMARY KEY,
    job_type     VARCHAR(20) NOT NULL,              -- FULL / INCREMENT / SCHEDULED / RETRY
    status       VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    total        INTEGER DEFAULT 0,
    success      INTEGER DEFAULT 0,
    failed       INTEGER DEFAULT 0,
    failed_items TEXT,
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    error_msg    VARCHAR(1000),
    created_by   VARCHAR(64) NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted      SMALLINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_news_sync_job_created ON sparkora_news_sync_job(created_at);
```

> 命名注意：`sparkora_news.news_id` 是**官方字符串 id**（业务唯一键）；`sparkora_news_doc.news_id` 是**内部 BIGINT FK**。为避免混淆，实体字段：`NewsEntity.newsId`(String) vs `NewsDocEntity.newsId`(Long)。

## 3. 外部接口契约（已实测）

### 3.1 列表
`POST https://cms-api.byd.com/es/search`
- Headers：`Content-Type: application/json`、`Referer: https://www.byd.com/`
- Body：`{"brandName":"byd","siteName":"cn","type":"news","page":1,"size":12,"sortField":"date","year":""}`
- 响应：`{code:0,data:{records:[{id,title,url,imageUrl,date,tags,tagNames,extraParams}],total,size,current,pages}}`（实测 total=167）
- **分页**：按 `pages` 循环 page=1..N。

### 3.2 详情正文（SSR HTML 抽取）
`GET https://www.byd.com{url}`（如 `/cn/detail634`）
- 正文容器：`.cmp-news__detail-content`
  - 段落：`.news-text p`（`<br>` → 换行）
  - 图片：`.news-image img`（记 src，暂不下载）
- 标题：`.cmp-news__detail-title`；日期：`.cmp-news__detail-date`
- **图片型新闻**：正文可能仅图无文字 → `content` 为空，仍入库元数据，切块跳过（AC5）。

## 4. 组件设计

| 组件 | 职责 | 关键签名 |
|---|---|---|
| `config/NewsProperties` | 配置绑定 `sparkora.news.*` | `listUrl/detailBaseUrl/timeoutMs/pageSize/syncEnabled/syncCron` |
| `news/client/BydNewsClient` | HTTP 采集（列表 + 详情 HTML） | `List<Map> searchPage(int page,int size)`；`String fetchDetailHtml(String url)` |
| `news/service/NewsContentParser` | jsoup 抽取正文/标题/日期 | `static Parsed parse(String html)` → `record Parsed(String title,String content,String publishDate)` |
| `news/service/NewsService` | 编排：抓列表→逐条抽正文→upsert→切块向量化；分页/增量 | `SyncOutcome syncFull()`；`SyncOutcome syncIncrement()`；`PageResult<NewsEntity> list(page,size,keyword)`；`NewsEntity get(id)` |
| `news/service/NewsDocService` | 切块 + embedding（仿 CarDocService/KbDocService） | `void rebuildForNews(Long newsId)`；`void deleteByNews(Long newsId)` |
| `news/service/NewsSyncJobService` | 异步任务（仿 CarSyncJobService） | `Long createJob(String jobType)`；`@Async runJob(...)`；`get/list/retry` |
| `news/service/NewsSyncScheduler` | 定时增量（仿 CarSyncScheduler） | `@Scheduled(cron="${sparkora.news.sync-cron:...}")` |
| `mapper/NewsMapper` / `NewsDocMapper` / `NewsDocEmbeddingMapper` / `NewsSyncJobMapper` | 持久化 | 向量表用注解 SQL（仿 CarDocEmbeddingMapper） |
| `web/controller/NewsController` | REST | 见 §5 |

## 5. 接口契约（前缀 `/api`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/news` | 三角色 | 分页列表 `?page&size&keyword`，返回 `PageResult`（含 id/title/publishDate/tagNames/imageUrl/chunkCount） |
| GET | `/news/{id}` | 三角色 | 详情（含 content） |
| POST | `/news/sync/jobs` | ADMIN/EDITOR | body `{jobType:"FULL"\|"INCREMENT"}`（默认 INCREMENT），返回 `{jobId}` |
| GET | `/news/sync/jobs/{id}` | 三角色 | 任务进度 |
| GET | `/news/sync/jobs` | 三角色 | 任务历史 |
| POST | `/news/sync/jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项 |

全部 `R<T>` 包装；异常映射沿用 `R.fail(400/404/500,...)`。

## 6. 统一检索接入（跨层关键改动）

`CarDocEmbeddingMapper.searchTopKUnified`：在现有 CAR UNION KB 基础上追加第三段：
```sql
UNION ALL
SELECT 'NEWS' AS "source", e.doc_id AS "docId", NULL AS "modelId",
       d.chunk_type AS "chunkType", d.chunk_text AS "chunkText",
       1 - (e.embedding <=> #{queryVec}::vector) AS "score", n.title AS "modelName"
FROM sparkora_news_doc_embedding e
JOIN sparkora_news_doc d ON d.id = e.doc_id AND d.deleted = 0
JOIN sparkora_news n ON n.id = d.news_id AND n.deleted = 0
```

`CarRagService.retrieveForGeneration`：
- 配额层：现有 `kbCandidates` 逻辑旁边新增 `newsCandidates`（`"NEWS".equals(source)`），配额 `ragNewsTopk`（新增 `AiProperties.ragNewsTopk=4`，与 KB 独立互不挤占）。NEWS 是否受 `ragKbEnabled` 控制？→ **不受**（R8：浏览/问答不设开关；生成注入的 NEWS 独立配置 `AI_RAG_NEWS_TOPK`，默认 4）。
- 来源标注：`【官方新闻：<modelName>】`。
- `sourceLine`：三域组合文案（车型数据 / 通用知识库 / 官方新闻）。
- 锚点加权：仅 `"CAR".equals(source)` 生效，NEWS/KB 不变。
- `covered` 摘要：仅 CAR 块参与（不变）。
- citations：NEWS 块同样进 `citations`（source=NEWS），供 C4 引用展示。

## 7. 配置（三处同步）

`application.yml`：
```yaml
sparkora:
  news:
    list-url: ${NEWS_LIST_URL:https://cms-api.byd.com/es/search}
    detail-base-url: ${NEWS_DETAIL_BASE_URL:https://www.byd.com}
    timeout-ms: ${NEWS_TIMEOUT_MS:30000}
    page-size: ${NEWS_PAGE_SIZE:12}
    sync-enabled: ${NEWS_SYNC_ENABLED:false}
    sync-cron: ${NEWS_SYNC_CRON:0 30 3 * * ?}
  ai:
    rag-news-topk: ${AI_RAG_NEWS_TOPK:4}
```
`.env.example` 补 `NEWS_*` + `AI_RAG_NEWS_TOPK`；`NewsProperties` 绑定；`AiProperties` 加 `ragNewsTopk`。

## 8. 幂等与增量

- **upsert by `news_id`**：存在则更新元数据+正文，并**重建该新闻的切块与向量**（先物理清 embedding+doc，再重切）。
- **增量判定**：列表按 `sortField=date` 倒序，遇到「已存在且正文非空」的连续记录即停止翻页（早期快速退出）；FULL 则遍历全部 pages。
- **失败不阻断**：单条新闻抽取/向量化失败 → 记 failed_items，继续其余（AC5）。

## 9. 兼容与回滚

- 纯新增表与组件；`searchTopKUnified` 追加 UNION 段，若需回滚删该段即恢复 CAR+KB。
- `retrieveForGeneration` 的 NEWS 配额独立，`AI_RAG_NEWS_TOPK=0` 可临时关闭 NEWS 注入（不影响浏览/检索接口）。
- 不触碰车型/C1 逻辑；不改 `RagStatus` 四态语义。

## 10. 风险

| 风险 | 缓解 |
|---|---|
| 详情页 HTML 结构变化 | jsoup 选择器集中在 `NewsContentParser`；抽取失败仅告警、元数据仍入库 |
| 图片型新闻无正文 | content 空则跳过切块，保留元数据（AC5） |
| 167 篇逐条抓正文耗时 | 异步任务 + 进度轮询；分页 size=12；单条失败重试不阻断 |
| jsoup 引入 | 已实测 `org.jsoup:jsoup:1.18.1` 可解析（本地仓库 /mnt/workspace/dev/maven/repo） |
| 统一检索改动影响生成 | NEWS 独立配额 + 锚点语义不变；改后跑现有生成链路回归（AC6） |
