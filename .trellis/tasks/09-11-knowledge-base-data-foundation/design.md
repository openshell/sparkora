# Design — 车型库/知识库数据基座 + RAG 问答（父任务）

> 父任务不直接实现代码；本文件是**任务地图 + 跨子任务架构契约**。实现细节由各子任务的 `design.md`/`implement.md` 承载。

## 1. 总体架构：三域统一知识基座

```
比亚迪官方源                        清洗标准化                     统一知识基座（PostgreSQL + pgvector 1024维）
─────────────                     ──────────                    ──────────────────────────────────────
车型 4 接口 ──BydCmsClient──▶ 规则+AI 清洗 ──▶  car_model/version/param_clean
                                  └─▶ CarDocService 切块 ──▶ car_doc ──embedding──▶ car_doc_embedding  (域=CAR)
图片 URL ──▶ ImageService.persistOrReuse ──▶ 七牛图床 + image_asset(source=byd)
新闻列表 /es/search ─┐
                    ├─▶ NewsFetch ─▶ HTML 正文抽取 ─▶ news 表 ─▶ NewsDocService 切块 ─▶ news_doc_embedding (域=NEWS)
详情页 SSR HTML ─────┘
手工知识 ─────────────────────────▶ kb_doc ─▶ kb_chunk ─▶ kb_chunk_embedding  (域=KB)

                                        │
                             CarRagService 统一检索（CAR + KB + NEWS）
                                        │
                    ┌───────────────────┼────────────────────┐
              创作生成注入         知识中心浏览            多轮问答（QA）
         （BriefService 等）   /knowledge（车型/新闻）   跨域检索→LLM 合成+引用
```

**核心原则**：不重建 RAG，新增新闻域复用同一 embedding/检索管线；三域在统一检索中并列，来源可区分。

## 2. 子任务地图与依赖

| 子任务 | 目录 | 交付 | 依赖 |
|---|---|---|---|
| C1 车型数据基座加固 | `09-11-car-foundation-hardening` | 缺陷修复 + 七牛转存验证 + 手动/定时同步 | 无（最先） |
| C2 新闻数据接入与独立知识域 | `09-11-news-ingestion-domain` | 新闻抓取/清洗/入库/向量化 + 统一检索接入 | 建议在 C1 后（复用其同步任务范式与检索改动） |
| C3 知识中心浏览页 | `09-11-knowledge-center-ui` | `/knowledge`（车型/新闻 Tab） | 依赖 C2 的新闻接口；车型 Tab 可先行 |
| C4 多轮对话式问答 | `09-11-qa-multiturn` | QA 接口 + 会话 + 页面 | 依赖 C2（新闻域）与 C3（前端框架/入口约定） |

> 依赖是**顺序建议**，不是树位置含义；每个子任务的 `prd.md`/`implement.md` 必须显式写出其前置依赖与可独立验收范围。

## 3. 跨子任务共享契约（所有子任务必须遵守）

### 3.1 统一检索来源域
- 现有 `UnifiedHit.source` 取值 `CAR` / `KB`。**新增 `NEWS`**。
- 来源标注文案：`【车型数据：<name>】` / `【通用知识：<title>】` / **`【官方新闻：<title>】`**（新增）。
- `CarDocEmbeddingMapper.searchTopKUnified` 由 CAR+KB UNION 扩为 CAR+KB+NEWS UNION；`CarRagService.retrieveUnified`/`retrieveForGeneration` 需同步支持 NEWS 的 `modelName` 语义（承载新闻标题）与配额。
- **约束**：新增域不得破坏既有四态 `RagStatus` 语义与锚点加权（锚点仅对 CAR 生效，NEWS/KB 不受锚点加权）。

### 3.2 向量表范式（沿用现有）
- 每域：`*_doc`（可逻辑删除的切块）+ `*_embedding`（`vector(1024)`，无 `deleted` 列）。
- **索引统一（C1 负责）**：`sparkora_kb_chunk_embedding` 的 IVFFLAT 改为与车型域一致的 **HNSW `vector_cosine_ops`**（幂等：`DROP INDEX IF EXISTS` + `CREATE INDEX IF NOT EXISTS ... USING hnsw`）。
- **删除清理契约（C1 负责）**：所有域删除实体时须物理清理其 embedding 行；`*_embedding` 表无 `deleted` 列，逻辑删除的 doc 通过 JOIN `deleted=0` 过滤。

### 3.3 图片存储契约
- 统一走 `ImageStorage`（当前 `QiniuService`）+ `ImageService.persistOrReuse`（sha256 去重）。**不新增存储实现。**
- **`intro_images` 语义契约（C1 定稿）**：明确为「图库 `image_asset.id` 列表 JSON」；前端展示必须经图片接口解析为 URL（或后端返回带 URL 的 DTO），**不得直接当 URL 用**。

### 3.4 同步触发契约（C1 定义，C2 复用）
- 手动：`POST .../sync/jobs`（现有范式）。
- 定时：`@Scheduled` 读取配置（车型 `sparkora.car.sync-cron`；新闻新增 `sparkora.news.sync-cron`），**默认关闭或低频**，避免个人项目环境空跑；需幂等增量。
- 任务表范式：车型用 `sparkora_car_sync_job`；新闻可新增 `sparkora_news_sync_job` 或泛化（C2 设计时定，倾向新增独立表以隔离）。

### 3.5 AI 调用契约
- 复用 `AiClient`。现有 `chat(system,user,maxTokens)` 仅支持单轮。
- **C4 需扩展多轮**：新增 `chatMessages(List<Message>, maxTokens)` 或等价方法（保持现有方法签名不破坏）。
- QA 答案须带 citations（复用 `CarRagService.Citation` 结构或等价），并标注来源域。

### 3.6 配置与命名
- 沿用环境变量 → `application.yml` 占位符 → `*Properties` 范式；新增同步更新 `.env.example`。
- 现有车型源配置前缀是 `CAR_*`（非 `BYD_*`）；新闻新增建议 `NEWS_*`（URL/超时/同步开关/cron）。

## 4. 数据模型（跨子任务）

- **C1**：不改表结构（仅修数据语义与索引）；可能新增 `@Scheduled` 所需配置项，无新表。
- **C2**：新增新闻域表（建议）：
  - `sparkora_news`：`id, news_id UNIQUE(官方 id), title, url, image_url, publish_date, tags(JSON), tag_names(JSON), content(正文纯文本), source, sync_status, last_sync_at, created_at, updated_at, deleted`
  - `sparkora_news_doc`：`id, news_id FK, seq, chunk_type, chunk_text, created_at, deleted`（首行带新闻标题锚点）
  - `sparkora_news_doc_embedding`：`id, doc_id FK, news_id FK, embedding vector(1024), created_at` + HNSW 索引
  - `sparkora_news_sync_job`：复用车型任务表字段范式
  - 三处同步：`schema.sql`（幂等）+ entity/mapper + `docs/s0-spec.md` 字段级表格。
- **C4**：新增问答会话表（建议）：
  - `sparkora_qa_session`：`id, title, created_by, created_at, updated_at, deleted`
  - `sparkora_qa_message`：`id, session_id FK, role(user/assistant), content, citations(JSON), created_at`
  - 若仅做前端会话（无持久化）可省表，但多轮上下文需在服务端或客户端维护——**C4 设计时明确**。

## 5. 接口契约（跨子任务，前缀 `/api`）

- **C1**：`POST /car/models/{id}/sync`（现有）；新增/启用定时任务，无新 REST 必要。
- **C2**：
  - `GET /news`（分页列表，浏览页用）
  - `GET /news/{id}`（详情）
  - `POST /news/sync/jobs`（全量/增量抓取）、`GET /news/sync/jobs/{id}`、`GET /news/sync/jobs`、`POST /news/sync/jobs/{id}/retry`
  - 全部 `@PreAuthorize` + `R<T>` 包装（读三角色，写 ADMIN/EDITOR）。
- **C3**：纯前端路由 `/knowledge`；复用 C2 新闻接口 + 现有 `carApi`。
- **C4**：
  - `POST /qa/sessions`（建会话）、`GET /qa/sessions`、`GET /qa/sessions/{id}`
  - `POST /qa/sessions/{id}/messages`（提问 → 检索 → 合成 → 返回答案+citations）
  - 全部 `@PreAuthorize` + `R<T>`。

## 6. 兼容与迁移

- 现有 `/car`、`/kb` 路由保留；`/knowledge` 为新增入口，不替换。
- `kb_enabled` 语义收窄：**浏览与问答不受其控制**；仅「创作生成注入」读取（R8）。需核对 `BriefService`/`VersionService` 等注入点，确保浏览/问答链路不读该开关。
- 现有 `POST /car/rag` 保留（检索块），可标记为兼容/内部；问答走新 `/qa`。
- 存量数据：车型图片 `intro_images` 已是 id 列表，前端修复后即可；历史向量索引重建需在部署时执行（幂等 DDL + 可选 `rebuild-all`）。

## 7. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 新闻详情页 HTML 结构变化 | 抽取逻辑容错 + 失败仅告警不阻断；保留原文 URL |
| 图片型新闻无正文文本 | 正文为空时仍入库元数据，切块跳过或仅标题块 |
| 定时同步误跑 | 默认关闭/低频；配置显式开启 |
| 统一检索改动影响创作生成 | 保持 `RagStatus`/锚点语义不变；NEWS 不参与锚点加权；改后跑现有生成链路回归 |
| pgvector 索引切换 | `DROP INDEX IF EXISTS` + `CREATE ... hnsw` 幂等；低数据量无锁风险 |
| 多轮问答上下文超长 | 限制历史轮数/摘要压缩；citations 截断（复用 `CITE_TEXT_MAX`） |

## 8. 决策记录（Q1~Q8）

| # | 决策 |
|---|---|
| Q1 | 问答 = 多轮对话式 + 来源引用 |
| Q2 | 图片存储用**七牛云**（更正，非飞牛），沿用 `QiniuService` |
| Q3 | 新闻接入 = 全量历史(167) + 增量 + 正文 + 向量化 |
| Q4 | 新闻与车型**不关联**（两类独立数据） |
| Q5 | 保留并扩展 RAG（新增新闻域 + 问答层），不引入外部向量库 |
| Q6 | 既有缺陷**全部纳入修复** |
| Q7 | 知识中心 `/knowledge`，Tab 仅「车型」「新闻」；问答为独立入口非 Tab |
| Q8 | 同步 = 手动 + 定时增量；浏览/问答不设开关，仅生成注入可开关 |
