# 知识域 · 车型（CAR）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：比亚迪官网车型数据同步、清洗、切块向量化，作为统一检索的 **CAR 域**（可被锚点加权）。

> C1「车型库数据基座加固」（2026-09-11）。同步任务/调度范式与新闻域对称（见 [news.md](news.md)）。

---

## 1. 车型库数据基座加固（C1，2026-09-11）

| 项 | 契约 |
|---|---|
| `car_model.intro_images` | **语义 = 图库 `image_asset.id` 列表 JSON**（非 URL）；存量旧数据可能为 URL 数组，双读兼容 |
| `introImageUrls` | 非持久化派生字段（`@TableField(exist=false)`）：`list()`/`detail()` 由 `introImages` 实时解析——数字 id → `ImageService.publicUrl`，`http` 开头原样保留，解析失败跳过；前端车型卡片取 `coverUrlOf(row)`（封面优先，回退 `introImageUrls[0]`） |
| `car_model.cover_image_id` | **封面图库资产 id**（`BIGINT`，Flyway `V26__car_model_cover_image.sql`；10-21 新增，NULL = 无封面）。来源：官网 `goodsInfo.img` 主体图（透明 PNG）转存；与 `intro_images` 详情图集**分离、互不覆盖** |
| `coverImageUrl` | 非持久化派生字段（`@TableField(exist=false)`）：由 `coverImageId` 实时解析为图床公网 URL；解析失败/无封面为 `null`，**不回退详情图**（保证「封面覆盖」统计真实，回退由前端展示层完成） |
| 删除车型清理 | 逻辑删主表/版本/分组/参数/文档块；并按 `modelId` 物理清理单表 store（`deleteByCarModel`，兜底历史逻辑删除残留；**10-03 E6 前为 `sparkora_car_doc_embedding`，已退役**）；**不删全局共享图库资产**（`project_id=null`、`source=byd`、内容哈希去重） |
| 同步触发 | 手动 `POST /api/car/sync/jobs`（`job_type=SELECTED/RETRY`）+ 手动 `POST /api/car/sync/backfill`（`job_type=IMAGE_BACKFILL`，10-15：存量车型图片轻回填，覆盖全部库内存活车型）+ 定时 `@Scheduled`（10-15 起每分钟 tick 读运行时设置，到点创建 `job_type=INCREMENTAL` 增量分派任务；**未过期** RUNNING 任务存在则跳过；陈旧 RUNNING（`started_at` 超 60 分钟）先置 FAILED 自愈后继续，见 [news.md §4](news.md)）；默认关闭（运行时设置 `sparkora_setting.car_sync_enabled=false`） |
| 配置 | 运行时设置 `sparkora_setting.car_sync_enabled`（默认 false）/ `car_sync_cron`（默认 `0 0 3 * * ?`），设置 UI 改动免重启生效；`.env` 的 `CAR_SYNC_ENABLED`/`CAR_SYNC_CRON` **降级为遗留种子**（调度不再读取） |
| KB 索引 | 矢量层统一为单表 `vector_store` HNSW cosine + metadata GIN（10-03 E1/E6；原 `sparkora_kb_chunk_embedding` 已退役，见 [kb.md](kb.md)） |

- `intro_images` 字段级：图库资产 id 列表（JSON 字符串，TEXT 列），由 `CarModelService.persistImages` 在同步时转存图库后写入（单图失败不阻断）；标签 `车型-<车型名>`（见 [image.md](../image.md)「BYD 图片自动分类」）。**10-15 存量回填后统一收敛为 id 列表语义**（旧 URL 数组随 IMAGE_BACKFILL/增量轻回填自然替换）。
- 封面字段级：`cover_image_id` 由 `persistImages` 从 `goodsInfo.img` 转存后写入，命名 `<车型名>-cover.<ext>`，标签 `[车型-<车型名>, 车型主图]`，`source=byd`、`project_id=null`、`created_by=system`。空 `img` 或下载失败**不冲写旧封面**。
- `introImageUrls`/`coverImageUrl` 与 `introImages`/`coverImageId` 的区别是前端常见 bug 来源：**不要把持久化 id 字段当 URL 用**；`coverImageUrl` 为空时由前端 `coverUrlOf` 回退详情图首图。

---

## 2. 切块与向量化

- `com.sparkora.car.service.CarChunkService`：按车型参数/版本/分组切块（`PARAM_GROUP` / `MODEL_INFO` / `RIGHTS` / `FEATURE` 等 `chunkType`），首行固定 `车型：<全名>`（消除 EV/DM-i 同系跨版本检索混淆，S6b）；块行文本 `参数名：清洗值`。
- `rebuildForModel`：embedding 并发（固定线程池 ≤4）+ 单块失败重试 1 次；**09-27 起委托 `com.sparkora.ai.EmbeddingBatchRunner`**（embed 在事务外，持久化经自注入 `@Lazy self` 走 `@Transactional(REQUIRES_NEW)` 的 `persistCarChunk`——doc 行与向量行同事务，失败回滚不留孤儿块，且不污染调用方事务）。
- 表：`sparkora_car_model`（主表）、`sparkora_car_chunk`（文档块）、`sparkora_car_param_clean`（清洗结果）。向量行存单表 `vector_store`（`metadata.domain=CAR`、`metadata.modelId`、`metadata.chunkType`、`metadata.embeddingModel`，写入盖当前模型、检索按当前模型过滤）；**旧表 `sparkora_car_doc_embedding` 已由 10-03 E6 `V9` 删除**。
- 切块质量与清洗三态（`RULE`/`AI`/`FALLBACK`）、清洗统计、覆盖度声明见 [retrieval.md §5/§6](../retrieval.md)。
- 向量模型防护（09-27 P1-⑧；10-03 E6 迁移 store）：`EmbeddingClient.embedList` 校验返回维度 == `AI_EMBEDDING_DIM`（默认 1024），不符抛 `AiException`；向量行 `metadata.embeddingModel` 记录写入时模型，统一检索 filter 与 store 统计/差集均带当前模型过滤，换模型后旧向量自动失效（不静默混空间）；`vector-stats` 的 `embeddedCount` 只计 `vector_store` 中当前模型行（`VectorStoreService.countCarEmbeddedByModel`，旧模型块判为缺失、可重建补齐）。

---

## 3. 官网采集与清洗

- `com.sparkora.car.client.*`：官网车型目录/详情/参数/属性列表接口（`CAR_GOODS_LIST_URL`/`CAR_GOODS_INFO_URL`/`CAR_GOODS_PARAMS_URL`/`CAR_GOODS_ATTR_LIST_URL`，HMAC 签名 `CAR_HMAC_SIGN_KEY`/`CAR_HMAC_SECRET_KEY`，超时 `CAR_TIMEOUT_MS`）。
- `GoodsInfoDto.Item` 自 10-21 起接收 `img`（车辆主体图，实测绝对 URL、透明 PNG）作为封面来源；`introduce` 仍为详情图集。`img` **不参与同步指纹**（见 §4.1），避免存量指纹全部失效触发重清洗/重嵌入。
- `CarCleanService` + `ParamCleaner`/`AiParamCleaner`：规则引擎优先（覆盖 98.8%+），AI 兜底；AI 返回 value 空白视为失败返回 null（走 `FALLBACK` 兜底），「无值清成空串」不再落库。
- 官网接口历史上曾按模块重复推送 → `persistVersions`/`persistParams` 同名版本/同名分组去重。
- 车型识别兜底：`CarModelMatcherService`（深度研究锚点解析按主题识别车型，失败不阻断；见 [brief-generation.md](../brief-generation.md)）。

---

## 4. 同步任务与调度

- `CarSyncJobService`：`createJob(jobType)`（`@Transactional` 落 RUNNING，`created_by=SecurityUtil`）；`@Async runJob(jobId)`（原子锁 `status=RUNNING→RUNNING` 影响行数=0 拒绝）；`finish`（`SUCCESS`/`PARTIAL`/`FAILED` + `failed_items` JSON）；`get`/`list`/`retry`/`hasFreshRunning`/`markStaleRunningAsFailed`。
- **失败状态回写（10-21 R2）**：`runJob` 的 catch 分支与详情页阻塞同步 `POST /api/car/models/{id}/sync` 的异常分支都调用 `CarModelService.markSyncFailed(goodsId, error)`——按 `goods_id` 置 `sync_status='FAILED'` + `last_sync_error`（截断 500 字符）+ `updated_at`，**不改 `last_sync_at`**（保留最后成功时间）；回写自身失败仅 `log.warn`，不覆盖原始异常/响应。全量、`IMAGE_BACKFILL`、`INCREMENTAL` 与阻塞单车型同步同口径。成功路径（`persistModel`/`backfillInternal`）与 `markSyncing` 均清空 `lastSyncError`。车型不再永久停留在「同步中」。
- `CarSyncScheduler`：10-15 起 `@Scheduled(cron = "0 * * * * ?")` **每分钟 tick**（不再直读 `.env` cron）——读 `SettingService.get()`（内存缓存）→ `car_sync_enabled=false` 早退 → `CronExpression.parse(car_sync_cron)` 维护 `nextRunAt`（cron 变化/首轮只装载不开跑；非法/未配置 log 后跳过不崩）；到点执行原编排（**先 `markStaleRunningAsFailed()` 清陈旧 RUNNING、再 `hasFreshRunning()` 防重叠**（顺序不可反）→ 官网目录取 goodsId → `createJob(goodsIds, "INCREMENTAL")`），完成后顺延下一拍（跳过/失败也顺延防同拍重复）；全程 try/catch，异常不中断 tick。设置改动下一 tick 生效，免重启。
- **定时同步陈旧自愈（kb-cleanup，2026-09-12）**：任务表无 `updated_at`，以 `started_at` 为存活时间戳，阈值 `SYNC_STALE_MS=60 分钟`（全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会误判活任务）。车型与新闻两侧对称实现（细节见 [news.md §4](news.md)）。
- 任务表 `sparkora_car_sync_job`：`job_type`（`SELECTED`/`RETRY` 全量 syncOne、`IMAGE_BACKFILL` 轻回填、`INCREMENTAL` 增量分派、`SCHEDULED` 旧定时历史值）、`status`、计数、`failed_items`、`started_at`/`finished_at`/`error_msg`。明细条目两类：失败 `{goodsId,name,error}` 与备注 `{goodsId,name,note}`（如「官网无图」「跳过：指纹一致」，成功路径可观测信息）；**retry 只取含 `error` 的条目**（note 条目不进重试候选）。

### 4.1 同步指纹与增量分派（10-15-car-image-backfill）

- **`sparkora_car_model.sync_fingerprint`**（VARCHAR(64)，V21 加列，存量行为 NULL）：goodsInfo 稳定字段序列化后 SHA-256（64 hex）。稳定字段 = `name`/`salesNetworkName`/`vehicleId`/`price`/`features`/`introduce`（逐项原始顺序，Jackson 天然保序）/`carRights`；`detailPage` 等**不参与**。顺序敏感（同源顺序稳定）。字段契约见 `CarModelService.computeFingerprint`（包级静态，纯函数可单测）。
- **双写点统一**：全量路径 `persistModel` 与轻回填路径 `backfillImages`（→ `backfillInternal`）都写 `computeFingerprint(item)`；`sync_fingerprint IS NULL` = 指纹未初始化（存量兼容）。
- **封面与详情图集统一走 `persistImages`**（10-21）：全量 `syncOne` 与轻回填（`item != null`）都调用 `persistImages(model, item)`——`img` 非空则转存封面并写 `cover_image_id`，`introduce` 非空则转存详情图集并写 `intro_images`；有任一实际变化才 `updateById` 一次。**封面为空/下载失败保留旧封面；`introduce` 为空保留旧图集**。轻回填 `item == null`（官网空条目）分支仍只走 `persistLegacyIntroImages` 存量 URL 兜底，不动封面。增量「跳过」分支不更新封面（**已知限制**：官网仅换 `img` 时需手动「图片补入库」或全量同步）。
- **双格式语义收敛**：轻回填**不走 `persistModel`**——`persistModel` 会把 `intro_images` 重写为 raw URL 列表（:543），而 `persistImages` 覆写为图库 asset id 列表（封面同理写 `cover_image_id`）；轻回填只调 `persistImages` + 写指纹/`syncStatus=SUCCESS`/`lastSyncAt`/清空失败原因，**不触版本/参数/清洗/向量域**。`introduce` 为空时早退、不冲写 `intro_images`，明细 note=「官网无图」（官网空图车型：海狮05EV/2026款海豹07DM-i/海狮06EV，官网数据变化后随增量自动补图）；**仅有 `img` 无 `introduce` 的车型仍会写封面**。
- **「无条目」≠「无图」（10-15 真机验收发现并修复）**：官网 `getGoodsInfoById` 对部分存库 goodsId（如 `136`/`123`/`10022` 系列，已不在官网在售目录）返回**空条目**（`goodsinfoList.items` 为空，非 introduce 空）。此时：① 用库存行 `intro_images` 中的存量 URL 逐张下载兜底入库（`persistLegacyIntroImages`，单张失败仅告警）；② 指纹用**库存行快照口径** `computeRowSnapshotFingerprint(row)`（同哈希口径，`features`/`carRights`/`intro_images` 库内 JSON 字符串先 `readTree` 还原结构）；③ note=「官网无数据,已按存量URL兜底入库N张」/「官网无数据,且无存量图可兜底」。官网数据恢复后 goodsInfo 指纹必不匹配快照 → 增量自动触发一次全量收敛；未恢复时快照一致 → 增量跳过（每天仅 1 次 goodsInfo 调用）。
- **增量分派表（`dispatchIncremental(goodsId)`，定时 INCREMENTAL 逐车型入口）**：

| 车型行 | 存量指纹 vs 当前 goodsInfo 指纹 | 动作 | 明细 |
|---|---|---|---|
| 行不存在 | — | 全量 `syncOne`（官网目录新增车型） | — |
| 行存在 | `NULL`/空 | 轻回填（图片域+指纹初始化顺带完成） | 空图车型 note「官网无图」；无条目车型走快照兜底（见上） |
| 行存在 + 有条目 | 一致 | **跳过**（只拉 goodsInfo 比对，零下载/零清洗/零嵌入/零写库） | note「跳过：指纹一致」，计入 success |
| 行存在 + 有条目 | 不一致 | 全量 `syncOne`（price/卖点/图片字段均在 goodsInfo 内，可触发） | — |
| 行存在 + 无条目 | == 行快照指纹 | **跳过**（官网在售目录已无该车型，快照未变） | note「跳过：官网无数据(与上次快照一致)」 |
| 行存在 + 无条目 | ≠ 行快照指纹 | 全量 `syncOne`（安全兜底） | — |

> 2026-10-15 实测：官网在售目录 46 个 goodsId；库内失联 10 台（不在目录、goodsInfo 空条目）由 IMAGE_BACKFILL 存量 URL 兜底收敛后指纹固化，增量每拍全 skip（零成本）。

- **已知盲区**（明示接受）：goodsParams 独立变化（goodsInfo 不变）**不会触发增量**，需手动同步页「同步选中」全目录重跑兜底。
- 幂等：重跑 IMAGE_BACKFILL 依赖 `persistOrReuse` 内容哈希去重，不重复占图床对象；指纹一致车型手动回填显式重抓（哈希命中零成本）、增量调度跳过。

---

## 5. 接口契约（`/api/car`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/car/models` | 三角色 | 车型列表（含 `introImageUrls`、`coverImageUrl` 派生字段） |
| GET | `/api/car/catalog` | 三角色 | 官网车型目录（供同步页手动选择） |
| GET | `/api/car/models/{id}` | 三角色 | 车型详情（可带 `?versionId=`） |
| POST | `/api/car/sync/jobs` | ADMIN/EDITOR | 创建同步任务（`{goodsIds:[...]}`），异步执行，返回 `{jobId}` |
| POST | `/api/car/sync/backfill` | ADMIN/EDITOR | 创建「图片补入库」任务（10-15 新增，无 body，覆盖全部库内存活车型，`job_type=IMAGE_BACKFILL` 轻回填），返回 `data=jobId`（数字，非 `{jobId}` 包装）；10-21 起同时补**封面**（`img`）与详情图集；库内无车型 400 |
| GET | `/api/car/sync/jobs/{id}` | 三角色 | 任务进度；不存在 404 |
| GET | `/api/car/sync/jobs` | 三角色 | 任务历史 |
| POST | `/api/car/sync/jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项，返回新任务 `{jobId}` |
| POST | `/api/car/models/{id}/sync` | ADMIN/EDITOR | 单车型同步（详情页用，同步阻塞；返回 `{model, cleanStats}`）；车型不存在 404 |
| POST | `/api/car/sync` | ADMIN/EDITOR | 全量同步**已取消**（S6 重构），恒 `R.fail(400, "全量同步已取消,请在同步页选择车型后同步")` |
| DELETE | `/api/car/models/{id}` | ADMIN/EDITOR | 删除车型（逻辑删 + 物理清 store 向量，不删图库资产） |
| GET | `/api/car/models/{id}/clean-stats` | 三角色 | 清洗统计（按 `method`/`valueType` 分组） |
| GET | `/api/car/models/vector-stats` | 三角色 | 向量对账 `{modelCount, chunkCount, embeddedCount, missingCount, missingTopN}`（仅 `deleted=0`；`embeddedCount` 10-03 E6 起查 `vector_store`，结构不变） |
| POST | `/api/car/models/rebuild-all` | ADMIN/EDITOR | 逐车型重建向量汇总 |
| POST | `/api/car/rag` | 三角色 | 内部问答检索 `{modelId, query, topK?}` |

---

## 6. 前端

- `views/knowledge/CarKnowledgePanel.vue`（知识中心车型 Tab，10-21 整改，见 [center.md](center.md)）：搜索/网络/状态（含「无封面」）/排序（最近同步·名称·价格）/刷新；统计条为「全部·已同步·失败·同步中·封面覆盖」；卡片缩略图 `coverUrlOf(row)`（封面优先，回退详情图首图；`fit="contain"` + 纸色底）；失败原因截断 + tooltip、失败卡片可「重试」；EDITOR+ 恒定可见批量 checkbox + 批量同步/删除（删除文案说明不删共享图库资产）。
- 内聚同步（R4）：`components/car/CarCatalogDrawer.vue`（抽屉内加载官网目录 + 同步选中 + 图片补入库）、`CarCatalogGrid.vue`（目录网格/筛选/全选，纯展示）、`CarJobPanel.vue`（任务进度 + 失败/备注明细 + 重试；`can-retry` 控写权限）。任务由 `composables/useCarSyncJob.js` 统一轮询（2s、终态停止、连续 3 次失败停止；`beginStart`/`cancelStart` 覆盖 createJob 请求在途 → 首次轮询的防重窗口；`recoverLatest` 只恢复 `startedAt` 60 分钟内的 RUNNING（陈旧 RUNNING 不阻塞新建，与后端 `SYNC_STALE_MS` 同口径），否则展示 24h 内最近终态任务）。
- `views/CarSync.vue`（`/car/sync` 保留）：复用上述三组件 + composable，页面内直接展示目录网格；删除重复的轮询/任务面板实现；同步/补入库按钮按 EDITOR+ 显隐。
- `views/CarDetail.vue`：Hero 封面（无则详情图首图，原图预览）、详情图集缩略图（`preview-src-list` 指原图）、卖点 chips（`parseFeatures`）、失败告警（`lastSyncError` 完整展示）；同步后刷新详情。
- `utils/carDisplay.js`：`coverUrlOf` / `parseFeatures` / `formatPrice` / `sortCars` / `statusMeta` 纯函数（卡片、详情、同步页共用）。
- ~~`views/CarLibrary.vue`~~ 已不存在（其能力由知识中心车型 Tab 承接）。
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
| `CAR_SYNC_ENABLED` | `false` | **遗留种子**（10-15 起）：仅运行时设置 `sparkora_setting.car_sync_enabled` 为准（设置 UI 承载，免重启），env 值不再被调度读取 |
| `CAR_SYNC_CRON` | `0 0 3 * * ?` | **遗留种子**（10-15 起）：仅运行时设置 `sparkora_setting.car_sync_cron` 为准 |

---

## 8. 已知限制

- 清洗口径变更仅对新重建的车型生效；存量车型需逐个重建向量。
- goodsParams 独立变化不触发增量分派（指纹只覆盖 goodsInfo 稳定字段），需手动「同步选中」全目录重跑兜底（10-15 明示接受）。
- 车型库图片接入文章配图**预留**（暂不开发）；图库中 `source=byd` 的车型介绍图可经来源过滤/`车型` 标签组/关键词/语义搜索检索（10-15 存量回填后），但未接入文章配图面板。
