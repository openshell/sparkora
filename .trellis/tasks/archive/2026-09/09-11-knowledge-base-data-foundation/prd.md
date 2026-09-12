# 车型库/知识库数据基座 + RAG 问答（父任务）

## Goal

把「比亚迪官方权威数据 → 清洗标准化 → 知识库数据基座 → 浏览 + 问答」建成一条扎实、可复用的链路：

- **车型库**：保留并加固现有采集/清洗/入库/同步，图片转存七牛云，修复既有缺陷。
- **新闻库**：接入比亚迪官方新闻全量数据，清洗标准化后作为**第三个独立知识域**入库并向量化。
- **知识库**：保留并扩展现有 pgvector RAG（不重建），新增「知识中心」浏览页与**多轮对话式问答**。

用户价值：系统内拥有一个权威、结构化、可检索、可问答的汽车领域知识底座，供创作工作台（简报/正文/深度研究）与独立问答入口复用。

## Background — 现状事实（代码已确认）

**已经存在一套完整可用的 pgvector RAG，不是从零开始：**

- 车型采集：`car/client/BydCmsClient.java` 覆盖 4 个接口（goodsList / goodsInfo / goodsParams / goodsAttrList），仅 goodsParams 走 HMAC 签名；无分页。图片来源字段 `GoodsInfoDto.introduce`。
- 入库编排：`car/service/CarModelService.java` 幂等 upsert by `goods_id`，先清后插版本/参数/清洗；`persistIntroImages()`（`:296-322`）已下载官网图 → `ImageService.persistOrReuse()`（sha256 去重）→ 把 `car_model.intro_images` 覆盖为 **图库 asset id 列表 JSON**。
- 同步任务：`car/service/CarSyncJobService.java` 异步任务表 + 失败重试；`application.yml` 有 `sync-enabled`/`sync-cron` 但**无 `@Scheduled` 消费者**（死配置）。
- 清洗：`CarCleanService`（规则优先 → AI 兜底 → FALLBACK）+ `ParamCleaner`/`AiParamCleaner`，落 `sparkora_car_param_clean`（NUMBER/ENUM/LIST/STRING + 单位）。
- 向量化：`CarDocService` 切块（MODEL_INFO/PARAM_GROUP/RIGHTS）→ `EmbeddingClient`（axonhub OpenAI 兼容 `/v1/embeddings`，Qwen3-Embedding-8B，**1024 维**）→ `sparkora_car_doc_embedding VECTOR(1024)` + HNSW 索引。
- 通用 KB：`kb/service/KbDocService.java` CRUD + 自动切块 + 向量化 → `sparkora_kb_chunk_embedding`，索引为 **IVFFLAT**（与车型域 HNSW 不一致）。
- 统一检索：`car/service/CarRagService.java` `retrieveUnified`（CAR+KB UNION）、`retrieveForGeneration`（锚点加权 + 子查询 + 配额 + 四态 `RagStatus{OK,LOW_CONFIDENCE,FAILED,NO_KNOWLEDGE}` + citations）。
- 深度链路：`deep/` 已有 `KnowledgeSearchTool`（委托 CarRagService）→ `SubAgentRunner` → `FactSheetService`（KB 冲突时 KB 胜出）。
- 图片存储：`storage/ImageStorage.java`（`configured/upload/publicUrl/download/delete`）**只有七牛实现** `service/QiniuService.java`；`SOURCES` 白名单含 `byd`。**图片存储已是七牛，无需新增实现。**
- 接口：`POST /api/car/rag` 仅返回检索块文本，**不做 LLM 合成答案**；前端 `CarDetail.vue` 的"车型问答（RAG）"实为检索块展示。
- 数据表：`sparkora_car_*`（model/version/param_group/param/param_clean/doc/doc_embedding/sync_job）、`sparkora_kb_doc/chunk/chunk_embedding`、`sparkora_image_asset`。

**外部数据源已实测确认：**

- 车型：`GET https://cms-api.byd.com/car/byd/cn/goodsListForSearch` 可用（`code=0`，一次返回全量目录，含 `img`/`introduce`/`notes` 等图片字段）。
- 新闻列表：`POST https://cms-api.byd.com/es/search`，body `{"brandName":"byd","siteName":"cn","type":"news","page":1,"size":N,"sortField":"date","year":""}`，返回 `data.records[]`（`id`/`title`/`url`/`imageUrl`/`date`/`tags`/`tagNames`/`extraParams`）+ `total/size/current/pages`（实测 total=167）。
- 新闻正文：无独立正文接口，正文在详情页 `https://www.byd.com{url}` SSR HTML 中（需 HTML 解析；部分为图片型内容）。
- `POST /es/listByIds` 只回元数据、不含正文。

## R4 评估结论（交付物，详见 `docs/knowledge-base.md`）

- **入库方式总体合理，保留**：幂等 upsert、先清后插、规则优先+AI 兜底清洗、切块+向量化，工程完整。
- **RAG 有必要，且应扩展而非重建**：pgvector、`EmbeddingClient`、统一检索、四态降级、citations 均已存在，边际成本低；多轮问答本质依赖检索；新闻只需新增独立知识域并复用同一管线。
- **结论**：保留现有 RAG，扩展「新闻域」，补齐问答合成层与会话；修复既有缺陷。**不引入外部向量库、不推翻现有切块方案。**

## Requirements

- **R1 车型数据基座加固**：保留现有采集/清洗/入库/同步，并修复既有缺陷：① `intro_images` 语义统一（前端不再当 URL）；② 删除车型时清理 `car_doc_embedding` 残留与图库引用；③ 车型域 HNSW 与 KB 域 IVFFLAT 索引统一；④ `sync-enabled`/`sync-cron` 死配置落地为**手动任务 + `@Scheduled` 定时增量**。
- **R2 车型图片存储（七牛云）**：车型图片转存七牛云；现有 `QiniuService` 已实现，本项为**验证/加固**（转存成功率、公网 URL 可访问、`intro_images` 语义一致），不新写存储实现。
- **R3 新闻数据接入**：抓取官方新闻**全量历史（167 篇）+ 后续增量**；列表元数据 + 详情页正文清洗标准化入库；按官方 id 幂等更新；同步方式为**手动 + 定时增量**。**新闻与车型不建立关联**（两类独立数据）。
- **R3a 新闻独立知识域**：新闻是继「车型域」「通用 KB 域」之后的**第三个独立知识域**，参与统一检索（CAR+KB+NEWS），并在来源标注/引用中区分。
- **R4 知识库/RAG 评估**：结论落到 `docs/knowledge-base.md`（见上）。
- **R5 知识浏览页面**：新增「知识中心」`/knowledge`，**Tab 仅「车型」「新闻」**；现有 `/car`、`/kb` 路由保留兼容。**问答不是 Tab**。
- **R6 问答程序**：**多轮对话式问答 + 来源引用**，**独立入口**；跨域检索（车型 + 新闻 + 通用 KB）→ LLM 合成答案 + citations；维护会话上下文。
- **R7 任务地图**：拆为可独立验收的子任务，依赖写入子任务 artifact。
- **R8 开关策略**：浏览与问答不设开关（始终可用）；仅「创作生成时是否注入知识库」保留可开关（可默认开）。

## Acceptance Criteria

- [ ] AC1：车型同步可跑通（幂等、失败重试），图片成功转存七牛云且公网 URL 可访问；支持手动与定时增量同步。
- [ ] AC2：`intro_images` 语义前后端一致（无 URL/id 混用）；删除车型后无 embedding/图库残留；向量索引统一。
- [ ] AC3：新闻全量历史（167 篇）+ 增量可抓取，正文清洗入库，按官方 id 幂等；新闻作为独立知识域进入向量库并可被统一检索命中（不与车型关联）。
- [ ] AC4：车型/新闻/通用知识可在统一知识库中检索。
- [ ] AC5：`/knowledge` 知识中心含「车型」「新闻」两个 Tab；`/car`、`/kb` 路由仍可用。
- [ ] AC6：多轮对话式问答（独立入口），答案跨域并带来源引用，同一会话可追问且上下文连贯。
- [ ] AC7：R4 评估结论落到 `docs/knowledge-base.md`。
- [ ] AC8：子任务拆分完成，各有独立可测验收标准与依赖说明。
- [ ] AC9：浏览/问答不受 `kb_enabled` 限制；生成注入开关行为符合 R8。

## Out of Scope

- 非比亚迪品牌数据源。
- 外部向量数据库（继续 PostgreSQL + pgvector）。
- 移动端 App。
- 新闻与车型的关联关系。

## Notes

- 本任务为**父任务**，承载源需求、任务地图与跨子任务验收；实现落到子任务。
- 决策编号 Q1~Q8 已全部收敛，见 `design.md`「决策记录」。
- 复杂任务：`task.py start` 前需补 `design.md` + `implement.md`，并 curate `implement.jsonl`/`check.jsonl`。
