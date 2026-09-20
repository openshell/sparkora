# 知识域 · 车型（CAR）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：比亚迪官网车型数据同步、清洗、切块向量化，作为统一检索的 **CAR 域**（可被锚点加权）。

> C1「车型库数据基座加固」（2026-09-11）。同步任务/调度范式与新闻域对称（见 [news.md](news.md)）。

---

## 1. 车型库数据基座加固（C1，2026-09-11）

| 项 | 契约 |
|---|---|
| `car_model.intro_images` | **语义 = 图库 `image_asset.id` 列表 JSON**（非 URL）；存量旧数据可能为 URL 数组，双读兼容 |
| `introImageUrls` | 非持久化派生字段（`@TableField(exist=false)`）：`list()`/`detail()` 由 `introImages` 实时解析——数字 id → `ImageService.publicUrl`，`http` 开头原样保留，解析失败跳过；前端 `CarLibrary.vue` 缩略图取 `introImageUrls[0]` |
| 删除车型清理 | 逻辑删主表/版本/分组/参数/文档块，并按 `model_id` 物理清理 `sparkora_car_doc_embedding`（兜底历史逻辑删除残留）；**不删全局共享图库资产**（`project_id=null`、`source=byd`、内容哈希去重） |
| 同步触发 | 手动 `POST /api/car/sync/jobs`（`job_type=SELECTED/RETRY`）+ 定时 `@Scheduled`（`job_type=SCHEDULED`，以官网目录全量幂等刷新，**未过期** RUNNING 任务存在则跳过；陈旧 RUNNING（`started_at` 超 60 分钟）先置 FAILED 自愈后继续，见 [news.md §4](news.md)）；默认关闭 |
| 配置 | `CAR_SYNC_ENABLED`（默认 false）/ `CAR_SYNC_CRON`（默认 `0 0 3 * * ?`） |
| KB 索引 | `sparkora_kb_chunk_embedding` 由 IVFFLAT 统一为 HNSW cosine（见 [kb.md](kb.md)） |

- `intro_images` 字段级：图库资产 id 列表（JSON 字符串，TEXT 列），由 `CarModelService.persistIntroImages` 在同步时转存图库后写入（单图失败不阻断）；标签 `车型-<车型名>`（见 [image.md](../image.md)「BYD 图片自动分类」）。
- `introImageUrls` 与 `introImages` 的区别是前端常见 bug 来源：**不要把 `introImages` 当 URL 用**。

---

## 2. 切块与向量化

- `com.sparkora.car.service.CarDocService`：按车型参数/版本/分组切块（`PARAM_GROUP` / `MODEL_INFO` / `RIGHTS` / `FEATURE` 等 `chunkType`），首行固定 `车型：<全名>`（消除 EV/DM-i 同系跨版本检索混淆，S6b）；块行文本 `参数名：清洗值`。
- `rebuildForModel`：embedding 并发（固定线程池 ≤4）+ 单块失败重试 1 次；完成日志输出「成功 X/失败 Z」，失败块记 `sortOrder`（消除静默丢块）。
- 切块质量与清洗三态（`RULE`/`AI`/`FALLBACK`）、清洗统计、覆盖度声明见 [retrieval.md §5/§6](../retrieval.md)。
- 表：`sparkora_car_model`（主表）、`sparkora_car_doc`（文档块）、`sparkora_car_doc_embedding`（物理向量表，无 `deleted`，HNSW cosine `idx_car_doc_emb_vec`）、`sparkora_car_param_clean`（清洗结果）。

---

## 3. 官网采集与清洗

- `com.sparkora.car.client.*`：官网车型目录/详情/参数/属性列表接口（`CAR_GOODS_LIST_URL`/`CAR_GOODS_INFO_URL`/`CAR_GOODS_PARAMS_URL`/`CAR_GOODS_ATTR_LIST_URL`，HMAC 签名 `CAR_HMAC_SIGN_KEY`/`CAR_HMAC_SECRET_KEY`，超时 `CAR_TIMEOUT_MS`）。
- `CarCleanService` + `ParamCleaner`/`AiParamCleaner`：规则引擎优先（覆盖 98.8%+），AI 兜底；AI 返回 value 空白视为失败返回 null（走 `FALLBACK` 兜底），「无值清成空串」不再落库。
- 官网接口历史上曾按模块重复推送 → `persistVersions`/`persistParams` 同名版本/同名分组去重。
- 车型识别兜底：`CarModelMatcherService`（深度研究锚点解析按主题识别车型，失败不阻断；见 [brief-generation.md](../brief-generation.md)）。

---

## 4. 同步任务与调度

- `CarSyncJobService`：`createJob(jobType)`（`@Transactional` 落 RUNNING，`created_by=SecurityUtil`）；`@Async runJob(jobId)`（原子锁 `status=RUNNING→RUNNING` 影响行数=0 拒绝）；`finish`（`SUCCESS`/`PARTIAL`/`FAILED` + `failed_items` JSON）；`get`/`list`/`retry`/`hasFreshRunning`/`markStaleRunningAsFailed`。
- `CarSyncScheduler`：`@Scheduled(cron="${sparkora.car.sync-cron:...}")`，`CAR_SYNC_ENABLED=false` 直接返回；**先 `markStaleRunningAsFailed()` 清陈旧 RUNNING、再 `hasFreshRunning()` 防重叠**（顺序不可反）；全程 try/catch。
- **定时同步陈旧自愈（kb-cleanup，2026-09-12）**：任务表无 `updated_at`，以 `started_at` 为存活时间戳，阈值 `SYNC_STALE_MS=60 分钟`（全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会误判活任务）。车型与新闻两侧对称实现（细节见 [news.md §4](news.md)）。
- 任务表 `sparkora_car_sync_job`：`job_type`（`SELECTED`/`RETRY`/`SCHEDULED`）、`status`、计数、`failed_items`、`started_at`/`finished_at`/`error_msg`。

---

## 5. 接口契约（`/api/car`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/car/models` | 三角色 | 车型列表（含 `introImageUrls` 派生字段） |
| GET | `/api/car/catalog` | 三角色 | 官网车型目录（供同步页手动选择） |
| GET | `/api/car/models/{id}` | 三角色 | 车型详情（可带 `?versionId=`） |
| POST | `/api/car/sync/jobs` | ADMIN/EDITOR | 创建同步任务（`{goodsIds:[...]}`），异步执行，返回 `{jobId}` |
| GET | `/api/car/sync/jobs/{id}` | 三角色 | 任务进度；不存在 404 |
| GET | `/api/car/sync/jobs` | 三角色 | 任务历史 |
| POST | `/api/car/sync/jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项，返回新任务 `{jobId}` |
| POST | `/api/car/models/{id}/sync` | ADMIN/EDITOR | 单车型同步（详情页用，同步阻塞；返回 `{model, cleanStats}`）；车型不存在 404 |
| POST | `/api/car/sync` | ADMIN/EDITOR | 全量同步**已取消**（S6 重构），恒 `R.fail(400, "全量同步已取消,请在同步页选择车型后同步")` |
| DELETE | `/api/car/models/{id}` | ADMIN/EDITOR | 删除车型（逻辑删 + 物理清 embedding，不删图库资产） |
| GET | `/api/car/models/{id}/clean-stats` | 三角色 | 清洗统计（按 `method`/`valueType` 分组） |
| GET | `/api/car/models/vector-stats` | 三角色 | 向量对账 `{modelCount, chunkCount, embeddedCount, missingCount, missingTopN}`（仅 `deleted=0`） |
| POST | `/api/car/models/rebuild-all` | ADMIN/EDITOR | 逐车型重建向量汇总 |
| POST | `/api/car/rag` | 三角色 | 内部问答检索 `{modelId, query, topK?}` |

---

## 6. 前端

- `views/CarLibrary.vue`（车型库列表/筛选/缩略图取 `introImageUrls[0]`；批量重建走 `carApi.rebuildAll()`）。
- `views/CarDetail.vue`（车型详情/单车型同步）、`views/CarSync.vue`（同步任务创建 + 2s 轮询 + 历史）。
- `views/knowledge/CarKnowledgePanel.vue`（知识中心车型 Tab，见 [center.md](center.md)）。
- `api/index.js` 的 `carApi`。

---

## 7. 配置（三处同步）

| `.env` 变量 | 默认 | 用途 |
|---|---|---|
| `CAR_GOODS_LIST_URL` | — | 官网车型目录接口 |
| `CAR_GOODS_INFO_URL` | — | 车型详情接口 |
| `CAR_GOODS_PARAMS_URL` | — | 参数接口 |
| `CAR_GOODS_ATTR_LIST_URL` | — | 属性列表接口 |
| `CAR_TIMEOUT_MS` | — | 采集读超时 |
| `CAR_HMAC_SIGN_KEY` / `CAR_HMAC_SECRET_KEY` | — | 官网 API 签名 |
| `CAR_SYNC_ENABLED` | `false` | 定时同步开关 |
| `CAR_SYNC_CRON` | `0 0 3 * * ?` | 定时 cron |

---

## 8. 已知限制

- 清洗口径变更仅对新重建的车型生效；存量车型需逐个重建向量。
- 车型库图片接入文章配图**预留**（暂不开发）；图库中 `source=byd` 的车型介绍图仅作素材。
