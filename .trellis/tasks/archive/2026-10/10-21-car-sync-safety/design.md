# 技术设计：车型同步数据安全与可靠性修复

## 1. 边界与原则

- 本任务只改同步链路、同步任务、官网客户端、图片下载与必要的前端状态提示；不改车型页视觉结构。
- 核心原则：**先校验、再写入；先构建、再替换；失败保留旧数据；所有异步任务可观测、可收敛。**
- 当前 `carSyncEnabled=false`；完成代码与测试前不开启自动同步。

## 2. 数据模型

### 2.1 迁移 V27（本阶段）

`src/main/resources/db/migration/V27__car_model_sync_state.sql`：

```sql
ALTER TABLE sparkora_car_model ADD COLUMN IF NOT EXISTS source_state VARCHAR(20);
ALTER TABLE sparkora_car_model ADD COLUMN IF NOT EXISTS sync_note VARCHAR(500);
ALTER TABLE sparkora_car_model ADD COLUMN IF NOT EXISTS last_checked_at TIMESTAMP;
```

- `source_state`: `OK / EMPTY / INCOMPLETE / STALE`；NULL 兼容存量。
- `sync_note`: 面向用户的短说明（官网无数据/部分图片失败/官网响应不完整等）。
- `last_checked_at`: 每次同步/检查都更新；`last_sync_at` 只在完整成功后更新。
- 不删除/重命名既有列；回滚代码可保留新列。

### 2.2 迁移 V28（指纹）

```sql
ALTER TABLE sparkora_car_model ADD COLUMN IF NOT EXISTS image_fingerprint VARCHAR(64);
ALTER TABLE sparkora_car_model ADD COLUMN IF NOT EXISTS params_fingerprint VARCHAR(64);
```

- `image_fingerprint = SHA-256(img + introduce)`；`params_fingerprint = SHA-256(goodsParams + goodsAttrList)`。
- 保留现有 `sync_fingerprint`（基础信息），避免存量全部失效。
- 存量回填：一次性计算已有行；无 `img` 的行 `image_fingerprint` 仍记录空图集 hash，避免每轮重复。
- 迁移版本需与未合并的 BYD 分支 V24/V25 协调；当前 main 最新 V26，V27/V28 为下一段。

### 2.3 同步任务表

`CarSyncJobEntity` 增加：

- `heartbeat_at TIMESTAMP`
- `cancel_requested SMALLINT DEFAULT 0`
- `error_msg VARCHAR(1000)`（已有则复用）

迁移可合并进 V27（推荐，减少迁移数）：

```sql
ALTER TABLE sparkora_car_sync_job ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;
ALTER TABLE sparkora_car_sync_job ADD COLUMN IF NOT EXISTS cancel_requested SMALLINT NOT NULL DEFAULT 0;
```

## 3. 官网响应预检

新增内部结果类型（`CarModelService` 内或 `car/service`）：

```java
record SourceSnapshot(
    GoodsInfoDto.Item item,
    GoodsParamsDto.ParamsData params,
    List<GoodsAttrListDto.Version> attrs
) {}
```

`syncOne` 流程改为：

1. 拉取三个接口（网络调用，无事务）。
2. `SourceValidator.validate(...)`：
   - `info != null && info.data/items 非空`；
   - `params != null && params.configs != null && !configs.isEmpty()`；
   - `attrs != null && !attrs.isEmpty()`。
3. 失败：`markSourceIncomplete(goodsId, state, note)`，只更新 `source_state/sync_note/last_checked_at`，**不执行任何 delete/update**；抛出 `SourceEmptyException`（携带状态/note）供任务记录。
4. 成功：执行写入。

`BydCmsClient` 同时负责 code/data 校验，避免把 HTTP 200 + 业务错误当空数据：

- `goodsInfo`/`goodsAttrList`：`code == 0`；`goodsParams`：`code == 200`。
- `data` 为 null/缺失 → 抛 `SourceUnavailableException`，不是返回空对象。
- `catalog`：code 校验 + `data` 必须是数组；否则抛错，调度器记录失败而不是“目录为空”。

## 4. 安全写入

### 4.1 `persistModel` 选择性合并

改为 `UpdateWrapper` 或显式 merge：

- 只有非 null/非空字段才 set；
- `intro_images` 不在 `persistModel` 写 raw URL；由图片入库结果写；
- `last_sync_at/SYNC_STATUS` 从 `persistModel` 移除；
- 新增 `markSourceState`/`markSyncSuccess`/`markSyncPartial`/`markSyncFailed` 统一写状态。

### 4.2 版本/参数事务

- `persistVersions`/`persistParams` 改为 public 方法并通过 `self` 代理调用，或抽到 `CarSyncWriteService`。
- 先校验 `attrs/configs` 非空且结构有效；空则保留旧数据 + note，不 delete。
- delete + insert 在一个事务内；失败抛异常，调用方保留旧数据（事务回滚）。
- `persistModel` 与 versions/params 的写入可以放在同一事务，但**网络/图片/embedding 必须在事务外**。

### 4.3 图片结果

`persistImages` 返回：

```java
record ImagePersistResult(Long coverId, List<Long> introIds, int failed, List<String> errors) {}
```

- 封面失败：保留旧 `cover_image_id`，note。
- 图集：仅当全部成功（或按内容哈希合并后）才替换；部分失败保留旧值 + note。
- 更新 `cover_image_id`/`intro_images` 使用显式 `UpdateWrapper`，不整实体写回。
- 图片失败数进入 `sync_note`，任务明细可见。

### 4.4 成功后置

`syncOne` 最后一步：

- 清洗/向量均成功 → `SUCCESS` + `last_sync_at=now` + `last_checked_at=now` + `source_state=OK`；
- 图片/向量部分失败 → `PARTIAL` + note + `last_checked_at`；
- 失败 → `FAILED` + error；不前进 `last_sync_at`；
- 空数据 → `EMPTY` + `last_checked_at`，不前进 `last_sync_at`。

## 5. 任务触发与并发

### 5.1 提交后触发

- `createJob`/`createBackfillJob` 只插入任务并提交；
- 用 `TransactionSynchronizationManager.registerSynchronization(afterCommit -> self.runJob(...))`，或 `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)`；
- 异步线程启动后 `selectById` 必须能读到任务；若读不到，记录错误并结束（watchdog 可兜底）。

### 5.2 任务状态与锁

- 任务状态：`PENDING → RUNNING → SUCCESS/PARTIAL/FAILED`。
- 全局锁：任务表部分唯一索引 `uq_car_sync_running ON sparkora_car_sync_job(status) WHERE status='RUNNING'` 可能过强；推荐应用层全局串行 + 数据库 advisory lock（key 固定）作为兜底。
- 按车锁：`pg_advisory_xact_lock(hashtext('car-sync:'||goodsId))` 在每台车的写入事务内获取；或在 `car_sync_lock` 表用 `INSERT ... ON CONFLICT` 抢占。
- `createJob/createBackfillJob/retry/详情页 syncOne` 统一检查：
  - 已有新鲜 RUNNING → 409/排队；
  - 同一 goodsId 在跑 → 409。
- 删除车型也检查同一把锁，避免与同步竞争。

### 5.3 Heartbeat / stale / cancel

- `runJob` 每台开始/结束更新 `heartbeat_at`；
- 读取 `cancel_requested`；为 1 时停止后续车型，任务置 `FAILED`（error_msg=用户取消）或 `PARTIAL`；
- `markStaleRunningAsFailed` 只在 `heartbeat_at` 超时且无法获取任务锁时执行；不能仅按 `started_at`；
- 旧线程每台写入前检查任务仍为 RUNNING 且自己持有锁；否则退出，避免把 FAILED 写回 RUNNING；
- `runJob` 外层 `try/finally`，任何路径 `finish` 终态。

### 5.4 计数与重试

- 任务实体新增/派生 `skipped` 计数（DB 列可选；至少任务明细与前端展示区分）；
- INCREMENTAL 跳过不 `success++`；`processed` 可等于成功+跳过+失败；
- `retry` 只取 error 条目；note 条目提供「重新检查」而非重试；
- 失败明细写分类短错误；原始异常只进日志。

## 6. 增量指纹与定向回填

### 6.1 指纹分派

```text
baseChanged   = sync_fingerprint != hash(item base)
imageChanged  = image_fingerprint != hash(img + introduce)
paramsChanged = params_fingerprint != hash(params + attrs)

if !baseChanged && !imageChanged && !paramsChanged -> skip
if imageChanged only -> persistImages (+ update image fingerprint)
if paramsChanged only -> persist params/attrs + clean + rebuild chunks (+ update params fingerprint)
else -> full sync
```

- 基础信息仍走现有 `dispatchIncremental`，但不再因 `img` 缺失而漏封面。
- `goodsParams` 独立变化可被发现；版本/参数变化不再依赖手动全量。
- 存量 `image_fingerprint/params_fingerprint` 为 NULL 时，首轮走一次轻量回填：图片只补图；参数只重建参数/清洗/块，不重下图片。

### 6.2 图片补入库范围

`createBackfillJob` 增加参数/任务子类型：

- 默认 `MISSING`：只处理 `cover_image_id IS NULL` 或 `intro_images IS NULL/[]` 或上次图片失败/指纹过期的行；
- `FULL`：显式全量重扫（管理员二次确认）。

### 6.3 向量构建

- `CarChunkService.rebuildForModel` 改为：
  1. 构建新块列表；
  2. embedding（事务外）；
  3. 全部/达到阈值成功后，在一个事务中删除旧块+向量并写入新块；失败则保留旧数据；
- 最低阈值可配置（默认 95%），低于阈值返回 `EmbedStats` 失败给 `syncOne`；
- `rebuildAll` 改为异步任务（复用 job 表或新 job type），带进度/取消。

### 6.4 回退缩略图

- 图片入库时为 `introduce` 生成 top-crop 4:3 缩略图（新图库资产或 Qiniu 派生 URL），存 `cover_fallback_image_id` 或在 `car_model` 加 `fallback_thumb_id`；
- 前端 `coverUrlOf` 优先 `coverImageUrl`，其次 `fallbackThumbUrl`，最后 `introImageUrls[0]`；
- 避免卡片加载 38MB 原图。

## 7. 官网接口与图片加固

### 7.1 `BydCmsClient`

- 每个方法校验 `code`（goodsParams=200，其余=0）+ `data` 类型；
- `catalog` 失败抛 `SourceUnavailableException`，调度器记录错误并跳过，不创建空任务；
- 重试：仅对 429/5xx/网络异常重试 2 次，指数退避 + jitter；尊重 `Retry-After`；单次任务总调用限速（如 2 QPS）；
- HMAC：计算一次 `ts`，header 与签名共用；
- 所有异常带端点 + goodsId，不把完整 URL/SQL 暴露给前端。

### 7.2 图片下载

- URL 白名单：`https` + `*.byd.com` / `*.bydstatic.com` / `pic.caiqz.cn`（以实际 CDN 域名为准）；
- 解析 URL 后拒绝 private/link-local/loopback；重定向后重新校验；
- 最大字节（默认 10MB）与最大像素（默认 50MP）；流式下载到临时文件/字节数组；
- `Content-Type` 必须 `image/*`；严格魔数（png/jpg/webp），未知直接失败；
- 用 `ImageReader` 读取宽高，不在同步线程 `ImageIO.read` 全量解码；
- 失败进入图片结果 note，不写图库。

### 7.3 错误与输入

- `CarModelController` 使用 DTO + `@Valid`；`goodsIds` 去重、上限（如 100）、校验存在/来自目录；非法返回 400；
- 用户可见错误：`官网无数据 / 官网响应不完整 / 网络超时 / 图片格式错误 / 图片过大 / 官网限流`；
- 原始堆栈、SQL、URL 只进日志；任务明细保留短错误 + 可选 errorCode。

## 8. 前端最小改动

- 车型卡片/详情展示 `source_state`：
  - `EMPTY`：标签「官网无数据」；隐藏/禁用全量同步；保留「图片补入库」。
  - `INCOMPLETE/STALE`：标签「数据待复核」；允许安全重试。
  - `PARTIAL`：显示 note（图片/向量部分失败）。
- `CarJobPanel` 计数区分成功/跳过/失败；全跳过显示「检查完成」。
- `CarKnowledgePanel` 统计条增加「官网无数据」；筛选支持 `EMPTY`。
- 同步按钮在 `source_state=EMPTY` 时改为「重新检查」（只读）或禁用；不触发全量写入。
- 图片失败/向量失败 note 在任务明细中可见。
- 不做完整视觉改版、不做手动封面选择器（另开 UX 任务）。

## 9. 测试设计

后端：

- `CarModelServiceTest` / `CarModelBackfillImagesTest`：
  - 空 goodsInfo/params/attrs 不写库、状态 EMPTY；
  - 部分字段保留旧值；
  - 部分图片失败保留旧图集；
  - 清洗/向量失败 → PARTIAL/FAILED，last_sync_at 不变；
  - 事务失败回滚版本/参数。
- `CarSyncJobServiceTest`：
  - afterCommit 触发（或事件）后任务可读；
  - 重复 createJob 被拒绝/排队；
  - heartbeat/cancel/stale 不误杀；
  - 外层异常落终态；
  - 跳过计数不进入 success。
- `BydCmsClientTest`：code/data 校验、429 重试、HMAC 时间戳一致、catalog 失败。
- `CarImageDownloadTest`：私网 URL、超大图、HTML、未知魔数、重定向。
- `CarChunkServiceTest`：embedding 部分/全部失败时旧块保留、任务 PARTIAL。
- 迁移测试/启动测试：V27/V28 可应用。

前端/联调：

- `npm run build`；
- Playwright smoke：车型 Tab 无 console error；`source_state=EMPTY` 显示禁用态；任务面板跳过计数文案；
- 手动：11 台空数据车点「重新检查」不改数据；正常车同步成功；并发两次同步只执行一个；图片补入库只补缺失。

## 10. 灰度与回滚

1. 代码合并前：只读体检 + 备份。
2. 部署时保持 `carSyncEnabled=false`。
3. 手动验证空数据、部分图片、并发、失败回滚。
4. 开启手动同步给 EDITOR；观察 1–2 天。
5. 开启定时增量；观察一个周期。
6. 回滚：代码回退；V27/V28 列保留不删；任务表新增列保留；不影响旧代码读取。

## 11. 风险

- 迁移版本与 BYD 分支 V24/V25 的合并顺序；先在本地/预发验证 Flyway。
- 旧线程仍可能持有锁：以 heartbeat + 锁 + 取消三重保护，不单纯依赖时间。
- 首轮指纹回填可能触发一次参数重建；限制为“只补缺失/首次为空”，避免全量重清洗。
- 生产数据修复不可自动执行；先出审计报告，逐项确认。
