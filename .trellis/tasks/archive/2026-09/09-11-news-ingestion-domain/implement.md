# Implement — C2 新闻数据接入与独立知识域

> 父任务：`09-11-knowledge-base-data-foundation`。依赖：C1（已完成，复用同步范式）。

## 有序实现清单

### 1. 配置与依赖
- [ ] `pom.xml` 加 `org.jsoup:jsoup:1.18.1`。
- [ ] `application.yml` 加 `sparkora.news.*`（list-url/detail-base-url/timeout-ms/page-size/sync-enabled/sync-cron）+ `sparkora.ai.rag-news-topk`。
- [ ] `config/NewsProperties.java`（`@ConfigurationProperties("sparkora.news")`）。
- [ ] `AiProperties` 加 `ragNewsTopk=4`。
- [ ] `.env.example` 补 `NEWS_*` + `AI_RAG_NEWS_TOPK`。

### 2. 数据模型（三处同步）
- [ ] `schema.sql` 追加 §S11 新闻域 4 表（幂等 `CREATE TABLE IF NOT EXISTS` + 索引）。
- [ ] 实体：`NewsEntity` / `NewsDocEntity` / `NewsSyncJobEntity`（`@TableLogic deleted`）。
- [ ] mapper：`NewsMapper`/`NewsDocMapper`/`NewsSyncJobMapper`（BaseMapper）；`NewsDocEmbeddingMapper`（注解 SQL：insert/deleteByDocId/deleteByNewsId/countByNews）。
- [ ] `docs/s0-spec.md` 新增新闻域字段级表格。

### 3. 采集与解析
- [ ] `news/client/BydNewsClient.java`：`searchPage(page,size)` POST JSON；`fetchDetailHtml(url)` GET。
- [ ] `news/service/NewsContentParser.java`：jsoup 抽取 `.cmp-news__detail-content`（`.news-text p` + `.news-image img`）→ `Parsed(title,content,publishDate)`；容错空正文。

### 4. 入库与向量化
- [ ] `news/service/NewsDocService.java`：切块（首行「新闻：<title>（<publishDate>）」；仿 KbDocService 分段/句读/≤500）+ embedding 并发化 + 失败重试；`rebuildForNews`/`deleteByNews`。
- [ ] `news/service/NewsService.java`：
  - `syncFull()` / `syncIncrement()`：分页抓列表 → 逐条抽正文 → upsert by `news_id` → 重建切块。
  - `list(page,size,keyword)` 分页 + `get(id)` 详情（含 content）。
  - `SyncOutcome(success, failed, failedItems)`。

### 5. 同步任务
- [ ] `news/service/NewsSyncJobService.java`：仿 CarSyncJobService（createJob/@Async runJob/原子锁/get/list/retry/终态）。
- [ ] `news/service/NewsSyncScheduler.java`：`@Scheduled(cron="${sparkora.news.sync-cron:0 30 3 * * ?}")`，默认关闭、`hasRunning()` 防重叠、try/catch。

### 6. 统一检索接入
- [ ] `CarDocEmbeddingMapper.searchTopKUnified` 追加 NEWS UNION 段（`source='NEWS'`, `modelName=n.title`）。
- [ ] `CarRagService.retrieveForGeneration`：新增 `newsCandidates` + `newsQuota=ragNewsTopk`；来源标注 `【官方新闻：…】`；`sourceLine` 三域组合；锚点仅 CAR；citations 含 NEWS。

### 7. REST
- [ ] `web/controller/NewsController.java`：§5 全部路由 + `@PreAuthorize` + `R<T>`。

## 验证命令

```bash
mvn -q -DskipTests compile
./dev.sh restart backend
# 手动全量抓取（异步，轮询）
curl -s -X POST localhost:5661/api/news/sync/jobs -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' -d '{"jobType":"FULL"}'
curl -s localhost:5661/api/news/sync/jobs/1 -H "Authorization: Bearer <token>"
# 列表/详情
curl -s 'localhost:5661/api/news?page=1&size=5' -H "Authorization: Bearer <token>"
# 幂等：再跑一次 INCREMENT，计数不增长
# 检索：确认 NEWS 命中 + 【官方新闻：…】标注
```

## 验证要点（对照 AC）

- AC1：FULL 抓取 total≈167；重复 INCREMENT 不新增（幂等）。
- AC2：`searchTopKUnified` 命中 NEWS 块。
- AC3：`CarRagService` 输出含 `【官方新闻：…】`。
- AC4：手动任务 + 定时器装配（默认关闭）。
- AC5：图片型/空正文新闻不阻断（元数据入库，切块跳过）。
- AC6：`mvn -q -DskipTests compile` 通过；现有生成链路回归无异常。

## 回滚点

- 检索接入：删 `searchTopKUnified` 的 NEWS UNION 段即恢复 CAR+KB。
- 配置回滚：`AI_RAG_NEWS_TOPK=0` 关闭 NEWS 生成注入（浏览/检索接口不受影响）。
- 数据：新表，`DROP TABLE` 即可；不影响既有域。

## 风险文件

- `CarDocEmbeddingMapper.java`（统一检索 SQL，改动影响全链路）
- `CarRagService.java`（生成前检索，改动需回归创作链路）
- `schema.sql`（启动时执行，必须幂等）
