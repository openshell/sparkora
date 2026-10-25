# 知识域 · 车型（CAR）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：比亚迪官网车型数据同步、清洗、切块向量化，作为统一检索的 **CAR 域**（可被锚点加权）。

> C1「车型库数据基座加固」（2026-09-11）。同步任务/调度范式与新闻域对称（见 [news.md](news.md)）。

---

## 1. 车型库数据基座加固（C1，2026-09-11）

| 项 | 契约 |
|---|---|
| `car_model.intro_images` | **语义 = 图库 `image_asset.id` 列表 JSON**（非 URL）；存量旧数据可能为 URL 数组，双读兼容 |
| `introImageUrls` | 非持久化派生字段（`@TableField(exist=false)`）：`list()`/`detail()` 由 `introImages` 实时解析——数字 id → `ImageService.publicUrl`，`http` 开头原样保留，解析失败跳过；前端车型卡片取 `coverUrlOf(row)`（封面优先，无封面回退 `introImageUrls[0]` 并按顶部裁剪展示） |
| `car_model.cover_image_id` | **封面图库资产 id**（`BIGINT`，Flyway `V26__car_model_cover_image.sql`；10-21 新增，NULL = 无封面）。来源：官网 `goodsInfo.img` 主体图（透明 PNG）转存；与 `intro_images` 详情图集**分离、互不覆盖** |
| `coverImageUrl` | 非持久化派生字段（`@TableField(exist=false)`）：由 `coverImageId` 实时解析为图床公网 URL；解析失败/无封面为 `null`，**不回退详情图**（保证「封面覆盖」统计真实，回退由前端展示层完成） |
| `car_model.source_state` | 官网数据源状态（`OK/EMPTY/INCOMPLETE/STALE`；NULL=存量未标记，10-21 新增）。空/不完整响应只写此列 + `sync_note` + `last_checked_at`，**不写业务表**；`EMPTY` 车型全量同步入口禁用 |
| `car_model.sync_note` | 最近一次检查/同步的面向用户短说明（如「官网无数据」「图片失败N张」「向量失败N/M块」） |
| `car_model.last_checked_at` | 最近一次官网检查时间；空数据/跳过也更新，`last_sync_at` 只在完整成功后更新 |
| `car_model.source_created_at` | 官网 `goodsInfo.createdAt`（数据创建时间，Flyway V28）；UI 文案固定「创建时间」，**不是上市/发布时间**；与系统审计 `created_at` 分离 |
| `sourceCreatedAt` | 非持久化/实体派生字段（`LocalDateTime`），随列表/详情返回；空值前端不展示 |
| `car_model.image_fingerprint` / `params_fingerprint` | 10-21 拆分的图片域/参数域 SHA-256（64 hex）；只换图只更新图片、只换参数只重建参数/清洗/块；空值=待初始化 |
| `car_sync_job.heartbeat_at` / `cancel_requested` / `skipped` | 任务心跳、取消标记、跳过计数；逐车型更新心跳，`skipped` 不并入 `success` |
| 删除车型清理 | 逻辑删主表/版本/分组/参数/文档块；并按 `modelId` 物理清理单表 store（`deleteByCarModel`，兜底历史逻辑删除残留；**10-03 E6 前为 `sparkora_car_doc_embedding`，已退役**）；**不删全局共享图库资产**（`project_id=null`、`source=byd`、内容哈希去重） |
| 同步触发 | 手动 `POST /api/car/sync/jobs`（`job_type=SELECTED/RETRY`）+ 手动 `POST /api/car/sync/backfill`（`job_type=IMAGE_BACKFILL`，10-15 存量图片轻回填；10-21 起默认只补缺失/失败/过期，`?mode=FULL` 才全库重扫）+ 定时 `@Scheduled`（10-15 起每分钟 tick 读运行时设置，到点创建 `job_type=INCREMENTAL` 增量分派任务；**未过期** RUNNING 任务存在则跳过；陈旧 RUNNING（`heartbeat_at` 超 60 分钟，缺失回退 `started_at`）先置 FAILED 自愈并释放 `job:<id>` 全局锁后继续，见 [news.md §4](news.md)）；默认关闭（运行时设置 `sparkora_setting.car_sync_enabled=false`） |
| 配置 | 运行时设置 `sparkora_setting.car_sync_enabled`（默认 false）/ `car_sync_cron`（默认 `0 0 3 * * ?`），设置 UI 改动免重启生效；`.env` 的 `CAR_SYNC_ENABLED`/`CAR_SYNC_CRON` **降级为遗留种子**（调度不再读取） |
| KB 索引 | 矢量层统一为单表 `vector_store` HNSW cosine + metadata GIN（10-03 E1/E6；原 `sparkora_kb_chunk_embedding` 已退役，见 [kb.md](kb.md)） |

- `intro_images` 字段级：图库资产 id 列表（JSON 字符串，TEXT 列），由 `CarModelService.persistImages` 在同步时转存图库后写入（单图失败不阻断）；标签 `车型-<车型名>`（见 [image.md](../image.md)「BYD 图片自动分类」）。**10-15 存量回填后统一收敛为 id 列表语义**（旧 URL 数组随 IMAGE_BACKFILL/增量轻回填自然替换）。
- 封面字段级：`cover_image_id` 由 `persistImages` 从 `goodsInfo.img` 转存后写入，命名 `<车型名>-cover.<ext>`，标签 `[车型-<车型名>, 车型主图]`，`source=byd`、`project_id=null`、`created_by=system`。空 `img` 或下载失败**不冲写旧封面**。
- 列表派生字段批量填充：`CarModelService.list()` 先收集全部 `cover_image_id`/`intro_images` asset id，一次 `imageMapper.selectBatchIds` 后内存拼 URL（旧 URL 数组原样保留、缺失跳过）；禁止逐车型/逐图 `publicUrl` 造成 N+1（57 台约 100 次单条查询，接口 2–4s）。
- `introImageUrls`/`coverImageUrl` 与 `introImages`/`coverImageId` 的区别是前端常见 bug 来源：**不要把持久化 id 字段当 URL 用**；`coverImageUrl` 为空时由前端 `coverUrlOf` 回退详情图首图，`coverIsFallback` 标记回退态，长图用 `object-fit: cover` + `object-position: top` 裁出顶部，避免整张竖图缩成窄条。

---

## 2. 切块与向量化

- `com.sparkora.car.service.CarChunkService`：按车型参数/版本/分组切块（`PARAM_GROUP` / `MODEL_INFO` / `RIGHTS` / `FEATURE` 等 `chunkType`），首行固定 `车型：<全名>`（消除 EV/DM-i 同系跨版本检索混淆，S6b）；块行文本 `参数名：清洗值`。
- `rebuildForModel`：embedding 并发（固定线程池 ≤4）+ 单块失败重试 1 次；**09-27 起委托 `com.sparkora.ai.EmbeddingBatchRunner`**（embed 在事务外）。**10-21 起两阶段**：先把全部向量算入内存，任一块失败即返回 `EmbedStats(failed>0)` 且**不替换**旧块；全部成功才经自注入 `@Lazy self` 调 `replaceChunks`（`REQUIRES_NEW`）在同一事务内删旧块+向量并插新块，失败回滚保留旧数据。
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
- **定时同步陈旧自愈（kb-cleanup，2026-09-12；10-21 心跳化）**：以 `heartbeat_at`（逐车型更新，缺失回退 `started_at`）为存活时间戳，阈值 `SYNC_STALE_MS=60 分钟`（全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会误判活任务）。清理陈旧 RUNNING 时同时释放其 `job:<id>` 全局锁（锁 TTL 70min），否则崩溃残留锁会挡住新任务。车型与新闻两侧对称实现（细节见 [news.md §4](news.md)）。
- 任务表 `sparkora_car_sync_job`：`job_type`（`SELECTED`/`RETRY` 全量 syncOne、`IMAGE_BACKFILL` 轻回填、`INCREMENTAL` 增量分派、`VECTOR_REBUILD` 全库重建向量、`CLEAN_ONE` 单车型清洗（10-24 新增，见 §4.4）、`SCHEDULED` 旧定时历史值）、`status`、计数、`failed_items`、`started_at`/`finished_at`/`error_msg`。明细条目两类：失败 `{goodsId,name,error}` 与备注 `{goodsId,name,note}`（如「官网无图」「跳过：指纹一致」，成功路径可观测信息）；**retry 只取含 `error` 的条目**（note 条目不进重试候选）。

### 4.1 同步指纹与增量分派（10-15-car-image-backfill）

> **10-21-car-sync-safety 更新**：基础指纹已移除 `introduce`；新增 `image_fingerprint`/`params_fingerprint`，并修复空/半响应先删后写、任务并发、图片指纹失败仍推进等问题。本节中「img 不参与指纹」「goodsParams 盲区」为旧口径，以下文 §4.2 为准。

- **`sparkora_car_model.sync_fingerprint`**（VARCHAR(64)，V21 加列，存量行为 NULL）：goodsInfo 稳定字段序列化后 SHA-256（64 hex）。**10-21 起基础稳定字段 = `name`/`salesNetworkName`/`vehicleId`/`price`/`features`/`carRights`**（逐项原始顺序，Jackson 天然保序；`introduce` 已移到 `image_fingerprint`）；`detailPage` 等**不参与**。顺序敏感（同源顺序稳定）。字段契约见 `CarModelService.computeFingerprint`（包级静态，纯函数可单测）；旧口径（含 `introduce`）见 `computeLegacyFingerprint`，仅用于存量迁移判定。
- **双写点统一**：全量路径 `persistModel` 与轻回填路径 `backfillImages`（→ `backfillInternal`）都写 `computeFingerprint(item)`；`sync_fingerprint IS NULL` = 指纹未初始化（存量兼容）。
- **封面与详情图集统一走 `persistImages`**（10-21）：全量 `syncOne` 与轻回填（`item != null`）都调用 `persistImages(model, item)`——`img` 非空则转存封面并写 `cover_image_id`，`introduce` 非空则转存详情图集并写 `intro_images`；有任一实际变化才 `updateById` 一次。**封面为空/下载失败保留旧封面；`introduce` 为空保留旧图集**。轻回填 `item == null`（官网空条目）分支仍只走 `persistLegacyIntroImages` 存量 URL 兜底，不动封面。增量「跳过」分支不更新封面（**已知限制**：官网仅换 `img` 时需手动「图片补入库」或全量同步）。
- **双格式语义收敛**：轻回填**不走 `persistModel`**——`persistModel` 会把 `intro_images` 重写为 raw URL 列表（:543），而 `persistImages` 覆写为图库 asset id 列表（封面同理写 `cover_image_id`）；轻回填只调 `persistImages` + 写指纹/`syncStatus=SUCCESS`/`lastSyncAt`/清空失败原因，**不触版本/参数/清洗/向量域**。`introduce` 为空时早退、不冲写 `intro_images`，明细 note=「官网无图」（官网空图车型：海狮05EV/2026款海豹07DM-i/海狮06EV，官网数据变化后随增量自动补图）；**仅有 `img` 无 `introduce` 的车型仍会写封面**。
- **「无条目」≠「无图」（10-15 真机验收发现并修复）**：官网 `getGoodsInfoById` 对部分存库 goodsId（如 `136`/`123`/`10022` 系列，已不在官网在售目录）返回**空条目**（`goodsinfoList.items` 为空，非 introduce 空）。此时：① 用库存行 `intro_images` 中的存量 URL 逐张下载兜底入库（`persistLegacyIntroImages`，单张失败仅告警）；② 指纹用**库存行快照口径** `computeRowSnapshotFingerprint(row)`（同哈希口径，`features`/`carRights`/`intro_images` 库内 JSON 字符串先 `readTree` 还原结构）；③ note=「官网无数据,已按存量URL兜底入库N张」/「官网无数据,且无存量图可兜底」。官网数据恢复后 goodsInfo 指纹必不匹配快照 → 增量自动触发一次全量收敛；未恢复时快照一致 → 增量跳过（每天仅 1 次 goodsInfo 调用）。
- **增量分派表（`dispatchIncremental(goodsId)`，定时 INCREMENTAL 逐车型入口）**：

| 车型行 | 存量指纹 vs 当前 goodsInfo 指纹 | 动作 | 明细 |
|---|---|---|---|
| 行不存在 | — | 全量 `syncOne`（官网目录新增车型） | — |
| 行存在 | `NULL`/空 | 轻回填（图片域+指纹初始化顺带完成） | 空图车型 note「官网无图」；无条目车型走快照兜底（见上） |
| 行存在 + 有条目 | 一致 | **跳过**（只读比对三接口 `goodsInfo+goodsParams+goodsAttrList`；零下载/零清洗/零嵌入；只写 `last_checked_at`/`sync_note`） | note「跳过：指纹一致」，计入 skipped（10-21 起不并入 success） |
| 行存在 + 有条目 | 不一致 | 全量 `syncOne`（price/卖点/图片字段均在 goodsInfo 内，可触发） | — |
| 行存在 + 无条目 | == 行快照指纹 | **跳过**（官网在售目录已无该车型，快照未变；只写状态列并如实标 `EMPTY`，不碰业务表） | note「跳过：官网无数据(与上次快照一致)」 |
| 行存在 + 无条目 | ≠ 行快照指纹 | 全量 `syncOne`（安全兜底） | — |

> 2026-10-15 实测：官网在售目录 46 个 goodsId；库内失联 10 台（不在目录、goodsInfo 空条目）由 IMAGE_BACKFILL 存量 URL 兜底收敛后指纹固化，增量每拍全部 skip（无下载/清洗/嵌入，只读比对 + 一条状态写回）。

- **旧盲区已消除（10-21）**：`params_fingerprint` 覆盖 `goodsParams + goodsAttrList`，参数/版本独立变化触发定向重建（见 §4.2）；`goodsInfo.img` 变化由 `image_fingerprint` 捕获。
- 幂等：重跑 IMAGE_BACKFILL 依赖 `persistOrReuse` 内容哈希去重，不重复占图床对象；指纹一致车型手动回填显式重抓（哈希命中零成本）、增量调度跳过。

---


### 4.2 同步安全与可靠性加固（10-21-car-sync-safety）

- **空/半响应预检**：`syncOne` 先拉 `goodsInfo/goodsParams/goodsAttrList` 并校验；任一空/不完整只写 `source_state + sync_note + last_checked_at`（`sync_status=FAILED`，**不写 `last_sync_at`**），**不执行版本/参数/清洗/块/向量的 delete/update**。`BydCmsClient` 按端点校验业务 code（goodsInfo/attrs=0，params=200）与 data 结构；catalog 失败抛错，不再当空目录。
- **安全写入**：`persistModel` 只合并非空字段；`intro_images` 只由图片入库结果写 asset id；`persistVersions/persistParams` 空数据保留旧行；主表+版本+参数通过 `persistCore` 单事务写入。图片集全有或全无，部分失败保留旧图集并写 note。
- **成功口径**：`SUCCESS` + `last_sync_at` 只在采集、图片、清洗、向量全部成功/达到最低阈值后写；部分失败 `PARTIAL`，全失败 `FAILED`；图片失败不推进 `image_fingerprint`，下一次增量仍会重试；向量未全部成功时**不推进** `sync_fingerprint`/`params_fingerprint`（否则下次增量会因指纹一致跳过，旧块永不补齐）。
- **状态分类**：业务 code/data 结构异常标 `INCOMPLETE`；429/5xx/超时等瞬态不可用标 `STALE`（保留旧数据，官网恢复后重同步收敛）；空条目 `EMPTY`。三者都只写状态列，不算成功。
- **任务**：`createJob` 在事务提交后触发 `@Async`；`CLEAN_BACKFILL` 复用同一任务/锁/心跳/进度体系；全局锁 + 按 `goodsId` 锁（`sparkora_car_sync_lock`，锁 TTL 70min、按 JVM 时间判定过期）互斥；`heartbeat_at` 逐车型更新，`cancel_requested` 请求停止后续；外层 finally 保证终态；`skipped` 独立计数，增量跳过不再算 success。跳过只写 `last_checked_at`/`sync_note`（PRD R3），不触业务表。
- **增量**：基础 `sync_fingerprint`（不含图片/参数）+ `image_fingerprint`（img+introduce）+ `params_fingerprint`（params+attrs）；只图片变化走图片域，只参数/版本变化走参数/清洗/块，避免不必要全量重清洗。
- **向量**：`CarChunkService.rebuildForModel` 先构建新块并完成 embedding，全部成功后才在事务内替换旧块；失败保留旧块并返回 failed 计数，任务进入 PARTIAL/FAILED。
- **图片下载**：HTTPS + 比亚迪/CDN 域名白名单、私网拦截、10MB/50MP 上限、Content-Type/严格魔数、`ImageReader` 只读尺寸；失败写 note 不写图库。
- **接口**：`POST /api/car/sync/backfill?mode=FULL` 全量重扫；`POST /api/car/sync/jobs/{id}/cancel` 请求取消；`goodsIds` 去重/上限 100/格式校验，错误 400，运行中任务 409。


### 4.3 清洗完整性与补清洗（10-21-car-clean-repair）

- **详情页兜底**：`groups[].cleans` 为空但 `params` 非空时，`CarDetail.vue` 展示原始参数并标注「原始参数/待清洗」，不再静默空白；RAW 行类型不伪装成已清洗数据。
- **补清洗**：`CarCleanRepairService.cleanOne/cleanMissing` 只读库内 `car_param` 重跑 `cleanForModel`，不请求官网、不重建图片；批量只处理 `params>0 && cleans=0`，失败逐台记录、不中断。
- **删除保护**：`cleanForModel` 先读参数/版本，参数或版本为空时保留旧清洗，不执行 delete；所有参数值均为空同样保留。
- **成功口径**：全量同步在清洗后校验 `CleanStats.total()>0`，为 0 记「清洗结果为空」并落 `PARTIAL`，不得完整 `SUCCESS`；图片补入库若该车型无清洗结果，只能 `PARTIAL`，不得单独刷新 `last_sync_at`。
- **版本对齐**：按 `values_json` 下标对齐 `car_version.sort_order`，**只清洗 `i < versions.size()`**；超出部分丢弃并告警，不再生成 `version_id=NULL` 的幽灵清洗行（这些行详情/切块读不到，却会污染 `clean-stats` 的 FALLBACK 比例）；数量不足时部分版本无清洗并记录日志。
- **运维**：知识中心「运维 → 补全清洗参数」触发批量补清洗；详情页在 raw 回退时提供「重新清洗」。补清洗后参数块/向量仍是旧的，需按需执行「重建全部向量」（10-24 起为异步 `VECTOR_REBUILD` 任务，见 §4.4）。

### 4.4 运维任务化与 backfill 定向（10-24-car-ops-async-p0）

- **`VECTOR_REBUILD`（全库/定向重建向量异步任务）**：`POST /api/car/models/rebuild-jobs`（ADMIN）→ `R.ok({jobId})`；**10-25 起支持可选 body `{goodsIds:[...]}` 定向重建**——非空时只圈定指定车型（`normalizeGoodsIds` 去重/上限 100 + 逐个存在性校验，未知 → 400「车型不存在: x」）；不传 body 行为不变（全库 = 全部 `goods_id` 非空车型，id 升序，LinkedHashSet 去重，**不套用单次 100 上限**——全库运维口径，心跳防陈旧误杀）。逐车型 `CarModelService.rebuildOneByGoodsId(goodsId)`（goods_id 解析 → `rebuildForModel`）。明细口径：`EmbedStats.failed>0` → failed++ + 失败明细「向量失败 N/M 块」（进 retry 候选）；`total==0` → skipped + note「跳过:无切块数据」；否则 success + note「重建 M 块向量」。取代旧同步接口 `rebuild-all`（300s 前端超时 < 全库实耗 570s+ 必超时，前端已迁移；旧接口保留为兼容兜底）。详情页 ADMIN「重建向量」入口即走定向 body（单车型）。
- **`CLEAN_ONE`（单车型清洗异步任务）**：`POST /api/car/models/{id}/clean-jobs`（ADMIN/EDITOR）→ `R.ok({jobId})`；车型不存在 404、缺 goodsId 400。`allowAi=true`（显式单台重跑允许 AI 兜底，**区别于批量 `CLEAN_BACKFILL` 的 `allowAi=false`**）；`cleansAfter>0` → success + note，否则 skipped + note「跳过:无可清洗参数/版本」。取代详情页旧阻塞接口 `POST /models/{id}/clean`（前端已迁移，接口保留兜底）。
- **运维域失败不写车型同步状态**：`VECTOR_REBUILD`/`CLEAN_ONE` 分支与异常路径**均不调 `markSyncFailed`**——向量/清洗是独立运维域，失败只进任务明细（error 条目可重试），不污染 `sync_status`/`last_sync_error`。
- **backfill 定向（10-24）**：`POST /api/car/sync/backfill` 支持可选 body `{goodsIds:[...]}`——非空时**只处理指定车型**（忽略 mode 过滤：显式选择即目标，含 `EMPTY` 车型走存量 URL 兜底）；未知 goodsId → 400「车型不存在」；沿用 `normalizeGoodsIds` 去重/上限 100 校验。不传 body 行为不变（MISSING 过滤 / FULL 全库）。前端入口 = 车型 Tab 批量栏「补选中图片」。
- **已知行为**：任务「重试失败项」（`retry`）固定创建 `RETRY` 全量任务——对 `VECTOR_REBUILD`/`CLEAN_ONE` 的失败条目重试 = 对该车型做**全量同步**（含采集/清洗/向量，功能上收敛但比预期重）；如需仅重跑向量/清洗域的重试，留待后续任务让 retry 保留源 job_type。

## 5. 接口契约（`/api/car`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/car/models` | 三角色 | 车型列表（含 `introImageUrls`、`coverImageUrl` 派生字段） |
| GET | `/api/car/catalog` | 三角色 | 官网车型目录（供同步页手动选择） |
| GET | `/api/car/models/{id}` | 三角色 | 车型详情（可带 `?versionId=`） |
| POST | `/api/car/sync/jobs` | ADMIN/EDITOR | 创建同步任务（`{goodsIds:[...]}`），异步执行，返回 `{jobId}` |
| POST | `/api/car/sync/backfill` | ADMIN/EDITOR | 创建「图片补入库」任务；10-21 起默认只补缺失/失败/过期图片，`?mode=FULL` 才全库重扫；**10-24 起支持可选 body `{goodsIds:[...]}` 定向执行**（只处理指定车型，忽略 mode 过滤；未知 id 400；不传 body 行为不变）；返回 `data=jobId`（数字，非 `{jobId}` 包装）；同时补**封面**（`img`）与详情图集；库内无车型 400 |
| GET | `/api/car/sync/jobs/{id}` | 三角色 | 任务进度；不存在 404 |
| GET | `/api/car/sync/jobs` | 三角色 | 任务历史 |
| POST | `/api/car/sync/jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项，返回新任务 `{jobId}`；备注条目（官网无图/跳过）不参与重试 |
| POST | `/api/car/sync/jobs/{id}/cancel` | ADMIN/EDITOR | 请求取消运行中任务；当前车型结束后停止后续（非强制中断） |
| POST | `/api/car/models/{id}/sync` | ADMIN/EDITOR | 单车型同步（**10-24 起前端已迁移任务体系**：详情页「同步」改走 `POST /sync/jobs` 单车型 SELECTED 任务；本接口保留为兼容兜底）；同步阻塞；返回 `{model, cleanStats}`；车型不存在 404 |
| POST | `/api/car/sync` | ADMIN/EDITOR | 全量同步**已取消**（S6 重构），恒 `R.fail(400, "全量同步已取消,请在同步页选择车型后同步")` |
| DELETE | `/api/car/models/{id}` | ADMIN/EDITOR | 删除车型（逻辑删 + 物理清 store 向量，不删图库资产）；同步任务运行中或该车型正在同步返回 409（防任务把已删车型 `syncOne` 复活） |
| POST | `/api/car/models/{id}/clean` | ADMIN/EDITOR | 仅基于库内原始参数重跑该车型清洗；不请求官网、不改版本/参数/图片/向量；返回前后清洗数。**10-24 起前端已迁移 `CLEAN_ONE` 任务**（`{id}/clean-jobs`），本接口保留为兼容兜底 |
| POST | `/api/car/models/clean-missing` | ADMIN | 同步批量补清洗兜底；逐台返回 cleaned/skipped/failed（耗时可能数分钟，前端优先用异步 job） |
| POST | `/api/car/models/clean-jobs` | ADMIN | 创建异步补清洗任务；`?force=true` 重清洗全部有参数车型；返回 `{jobId}`，`job_type=CLEAN_BACKFILL`，复用任务进度/取消 |
| POST | `/api/car/models/{id}/clean-jobs` | ADMIN/EDITOR | 创建单车型异步清洗任务（10-24）；`allowAi=true`（区别于 `CLEAN_BACKFILL` 批量口径）；返回 `{jobId}`，`job_type=CLEAN_ONE`；车型不存在 404、缺 goodsId 400 |
| POST | `/api/car/models/backfill-created-at` | ADMIN | 一次性回填官网 `goodsInfo.createdAt` 到 `source_created_at`；只读官网、只更新该列，返回 `{total,updated,skipped,failed,failedGoodsIds}` |
| GET | `/api/car/models/{id}/clean-stats` | 三角色 | 清洗统计（按 `method`/`valueType` 分组） |
| GET | `/api/car/models/vector-stats` | 三角色 | 向量对账 `{modelCount, chunkCount, embeddedCount, missingCount, missingTopN}`（仅 `deleted=0`；`embeddedCount` 10-03 E6 起查 `vector_store`，结构不变） |
| POST | `/api/car/models/rebuild-jobs` | ADMIN | 创建重建向量异步任务（10-24 全库；**10-25 起支持可选 body `{goodsIds:[...]}` 定向单台/少量**，未知 id 400「车型不存在」；不传 body = 全库，空库 400）；返回 `{jobId}`，`job_type=VECTOR_REBUILD`，复用任务锁/心跳/取消/重试体系 |
| POST | `/api/car/models/rebuild-all` | ADMIN | 逐车型重建向量汇总（同步接口，**10-24 起前端已迁移 `rebuild-jobs` 异步任务**；保留为 ADMIN 兼容兜底——300s 前端超时 < 全库实耗，仅 curl/脚本场景使用） |
| POST | `/api/car/rag` | 三角色 | 内部问答检索 `{modelId, query, topK?}` |

---

## 6. 前端

- `views/knowledge/CarKnowledgePanel.vue`（知识中心车型 Tab，10-21 整改 + 10-24 运维任务化 + 10-25 P1 交互补齐，见 [center.md](center.md)）：搜索/网络/状态（含「官网无数据」「数据待复核」「部分同步」「无封面」）/排序（最近同步·名称·价格）/刷新；**状态口径单一真源 `rowStatusKey`（EMPTY > 复核 > 部分 > syncStatus）——筛选/统计/卡片标签三处同源**；统计条 8 项「全部·已同步·失败·数据待复核·部分同步·官网无数据·同步中·封面覆盖」且**可点击切筛选**（「全部车型」清状态、「封面覆盖」= 无封面）；**筛选随 URL 持久化**（`kw`/`net`/`status`/`sort`，`router.replace` + 挂载恢复 + 清除筛选清 query）；**「全选筛选结果」checkbox**（EDITOR+，全选/indeterminate/取消清空，勾选=与已选并集）；批量栏 `position: sticky` 吸附（不透明背景）；卡片缩略图 `coverUrlOf(row)`（封面优先，回退详情图首图）；主图 `fit="contain"` + 纸色底，回退详情图时 `fit="cover"` + 顶部对齐裁剪（`object-position: top`）；失败原因截断 + tooltip、失败卡片可「重试」；EDITOR+ 恒定可见批量 checkbox + 批量同步/删除（删除文案说明不删共享图库资产）+ **「补选中图片」（10-24，定向 backfill，见 §4.4）**；「重建全部向量」为异步 `VECTOR_REBUILD` 任务（不再有 300s 阻塞等待）。
- 同步记录（10-24）：知识中心与 `/car/sync` 的「同步记录」打开 `CarJobHistoryDrawer.vue`，展示最近 50 条任务（类型/状态/成功·跳过·失败/操作人/时间），选中行复用 `CarJobPanel` 看明细/备注。
- 创建时间（10-24）：卡片 `c-meta` 与详情 Hero 展示官网 `createdAt`，文案「创建 YYYY-MM-DD / 创建时间」，不称发布时间；空值不展示。
- 内聚同步（R4）：`components/car/CarCatalogDrawer.vue`（抽屉内加载官网目录 + 同步选中 + 图片补入库）、`CarCatalogGrid.vue`（目录网格/筛选/全选，纯展示）、`CarJobPanel.vue`（任务进度 + 失败/备注明细 + 重试；`can-retry` 控写权限；job_type 文案含「选中同步/重试/图片补入库/增量同步/补清洗/单车型清洗/重建向量/定时同步」）。任务由 `composables/useCarSyncJob.js` 统一轮询（2s、终态停止、连续 3 次失败停止；`beginStart`/`cancelStart` 覆盖 createJob 请求在途 → 首次轮询的防重窗口；`recoverLatest` 只恢复 `heartbeatAt`（缺失回退 `startedAt`）60 分钟内的 RUNNING（陈旧 RUNNING 不阻塞新建，与后端 `SYNC_STALE_MS` 同口径），否则展示 24h 内最近终态任务；`toastFinished` 识别 `VECTOR_REBUILD`/`CLEAN_ONE`/`IMAGE_BACKFILL`/`INCREMENTAL`/`CLEAN_BACKFILL` 完成文案）。
- `views/CarSync.vue`（`/car/sync` 保留）：复用上述三组件 + composable，页面内直接展示目录网格；删除重复的轮询/任务面板实现；同步/补入库按钮按 EDITOR+ 显隐。
- `views/CarDetail.vue`：Hero 封面（无则详情图首图顶部裁剪，原图预览）、详情图集缩略图（`preview-src-list` 指原图）、卖点 chips（`parseFeatures`）、失败告警（`lastSyncError` 完整展示）；**10-24 起同步/清洗任务化**：「同步」创建单车型 SELECTED 任务（`createJob([goodsId])`，goodsId 缺失时拦截提示）、「重新清洗」创建 `CLEAN_ONE` 任务，均内嵌 `CarJobPanel` 展示进度、终态自动重载详情、挂载 `recoverLatest`；按钮 `jobBusy` 防重（409 兜底已有任务冲突）。**10-25 起 ADMIN 另有「重建向量」**：`createRebuildJob([goodsId])` 定向 `VECTOR_REBUILD`（同一任务面板/终态刷新，无 goodsId 禁用），状态标签统一用共享 `rowStatusMeta`。
- `utils/carDisplay.js`：`coverUrlOf` / `parseFeatures` / `formatPrice` / `sortCars` / `statusMeta` / `rowStatusKey` / `rowStatusMeta` 纯函数（卡片、详情、同步页共用；`rowStatusKey` 是状态口径唯一真源，`statusMeta` 仅作 syncStatus 基础映射）。
- 10-21 安全适配：卡片/详情按 `sourceState` 展示「官网无数据」（`EMPTY`）/「数据待复核」（`INCOMPLETE`/`STALE`）标签；`EMPTY` 车型禁用全量同步，10-24 起提示改为「勾选后使用批量栏『补选中图片』」（定向 backfill 闭环，见 §4.4）；`PARTIAL` 显示 `syncNote`；统计条增加「官网无数据」；任务面板显示 `skipped`，全跳过时标题为「检查完成(无变化)」，运行中可请求取消。
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
| `CAR_RETRY_MAX` | `2` | 采集失败最大重试次数（仅 429/5xx/网络异常） |
| `CAR_RETRY_BACKOFF_MS` | `500` | 重试指数退避基准（毫秒，带 jitter，上限 30s） |
| `CAR_MIN_INTERVAL_MS` | `200` | 两次官网调用最小间隔（单实例串行限速） |
| `CAR_HMAC_SIGN_KEY` / `CAR_HMAC_SECRET_KEY` | — | 官网 API 签名 |
| `CAR_SYNC_ENABLED` | `false` | **遗留种子**（10-15 起）：仅运行时设置 `sparkora_setting.car_sync_enabled` 为准（设置 UI 承载，免重启），env 值不再被调度读取 |
| `CAR_SYNC_CRON` | `0 0 3 * * ?` | **遗留种子**（10-15 起）：仅运行时设置 `sparkora_setting.car_sync_cron` 为准 |

---

## 8. 已知限制

- 清洗口径变更仅对新重建的车型生效；存量车型可一键「重建全部向量」（10-24 起为异步 `VECTOR_REBUILD` 任务）收敛。
- 任务「重试失败项」对 `VECTOR_REBUILD`/`CLEAN_ONE` 失败条目是**全量同步**语义（`retry` 固定建 `RETRY` 任务，功能收敛但比仅重跑向量/清洗域重），见 §4.4；全库重建的互斥全局锁 TTL 70min，车型数超 ~250 台单任务可能超 TTL（锁过期仅失去互斥保护，任务靠心跳不会被陈旧清理误杀）。
- 10-21 起 `params_fingerprint` 已覆盖 `goodsParams + goodsAttrList`，参数/版本独立变化可触发定向重建；存量行首次需要一次图片/参数指纹初始化（图片可走 `图片补入库`，参数在首次增量时按现有数据初始化，避免全库重清洗）。
- 车型库图片接入文章配图**预留**（暂不开发）；图库中 `source=byd` 的车型介绍图可经来源过滤/`车型` 标签组/关键词/语义搜索检索（10-15 存量回填后），但未接入文章配图面板。
