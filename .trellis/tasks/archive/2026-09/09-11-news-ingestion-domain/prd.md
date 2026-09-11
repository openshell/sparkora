# C2 新闻数据接入与独立知识域

> 父任务：`09-11-knowledge-base-data-foundation`。依赖：建议在 C1 之后（复用同步范式与统一检索改动）。

## Goal

抓取比亚迪官方新闻全量历史（实测 167 篇）+ 后续增量，抽取详情页正文，清洗标准化后作为**第三个独立知识域（NEWS）**入库并向量化，接入统一检索。新闻与车型不建立关联。

## Requirements

- **R1 抓取**：`NewsClient` 调 `POST https://cms-api.byd.com/es/search`（`{brandName:"byd",siteName:"cn",type:"news",page,size,sortField:"date",year}`）拿列表；详情正文从 `https://www.byd.com{url}` SSR HTML 抽取（引入 HTML 解析库，倾向 jsoup）。
- **R2 清洗标准化**：正文去噪；日期/标签（`tags`/`tagNames`）标准化；按官方 `news_id` 幂等 upsert。
- **R3 数据模型**：`sparkora_news` / `sparkora_news_doc` / `sparkora_news_doc_embedding`（vector(1024)+HNSW）/ `sparkora_news_sync_job`；`schema.sql` 幂等 + entity/mapper + `docs/s0-spec.md` 字段级表格三处同步。
- **R4 向量化**：切块（首行带新闻标题锚点）→ `EmbeddingClient` → NEWS 域向量表。
- **R5 统一检索接入**：`CarDocEmbeddingMapper.searchTopKUnified` 扩 CAR+KB+NEWS；`CarRagService` 支持 `NEWS` 来源与 `【官方新闻：<title>】` 标注；NEWS 不参与车型锚点加权。
- **R6 同步**：手动任务 + 定时增量（新增 `sparkora.news.*` 配置，复用 C1 范式）。
- **R7 REST**：`GET /news`、`GET /news/{id}`、`POST /news/sync/jobs`、`GET /news/sync/jobs[/{id}]`、`POST /news/sync/jobs/{id}/retry`；`@PreAuthorize` + `R<T>`。

## Acceptance Criteria

- [ ] AC1：全量历史（167 篇）+ 增量可抓取，正文抽取入库，按官方 id 幂等重跑不重复。
- [ ] AC2：新闻作为独立知识域进入向量库，可被统一检索命中。
- [ ] AC3：检索来源标注能区分 NEWS（`【官方新闻：…】`）。
- [ ] AC4：手动与定时增量同步均可用。
- [ ] AC5：图片型/无正文新闻不阻断流程（元数据入库，切块容错）。
- [ ] AC6：`mvn -q -DskipTests compile` 通过；现有创作生成链路回归无异常。

## Out of Scope

- 新闻与车型关联。
- 新闻评论/多媒体下载（除封面图可选转存）。

## Notes

- 复杂子任务：`task.py start` 前补 `design.md` + `implement.md`。
- 与父任务 `design.md` §3.1/§3.2/§3.4 契约衔接。
