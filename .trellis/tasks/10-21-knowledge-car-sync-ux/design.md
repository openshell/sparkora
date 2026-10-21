# 技术设计：知识中心车型 Tab 同步与交互整改

## 1. 范围与目标

本次范围为用户确认的 P0 + P1：

- 车型封面（官网主体图）采集、入库、回填与展示。
- 同步失败状态可信化与失败可见/可重试。
- 知识中心车型 Tab 内聚同步入口、任务恢复。
- 浏览效率：筛选/排序/刷新/统计口径/批量操作。
- 图片展示体验与详情页图集。
- 运维动作安全与三态/交互一致性。

不在本次范围：向量重建异步化（P2）、服务端分页（P2）、详情页问答合成升级（P2）。

## 2. 数据与契约

### 2.1 新增字段

- Flyway `V26__car_model_cover_image.sql`：
  ```sql
  ALTER TABLE sparkora_car_model ADD COLUMN cover_image_id BIGINT;
  COMMENT ON COLUMN sparkora_car_model.cover_image_id IS '封面图库资产 id（官网 img 主体图转存；NULL=无封面，前端回退详情图首图）';
  ```
- 版本号取 `V26` 而非 `V24`：当前 main 最新为 `V23`，但未合并的 `feature/byd-dynasty-miniapp-push` 已占用 `V24`/`V25`。用 `V26` 避免同号冲突；合并顺序若反过来（V26 先应用、V24/V25 后合入）会触发 Flyway ignored migration 校验，需在合并时协调，部署脚本已有版本冲突检查。
- `CarModelEntity`：新增 `Long coverImageId`，以及非持久化 `String coverImageUrl`（`@TableField(exist = false)`）。
- `GoodsInfoDto.Item`：新增 `String img`（官网主体图，实测绝对 URL，透明 PNG）。

### 2.2 派生字段

- `CarModelService.fillImageUrls(model)`（由现有 `fillIntroImageUrls` 扩展）：
  - `introImageUrls` 保持既有语义（id/URL 双读、逐项容错）。
  - `coverImageUrl = imageService.publicUrlQuietly(coverImageId)`；任何异常返回 null，**不做回退**（回退由前端展示层完成，保持「有无封面」统计真实）。
- `list()` 与 `detail()` 均调用 `fillImageUrls`。

## 3. 后端同步链路

### 3.1 图片入库复用

将现有 `persistIntroImageUrl(model, url, ids)` 的入库逻辑抽为通用方法：

```java
private Long persistImageToLibrary(CarModelEntity model, String url, String fileName, List<String> tags)
```

- 内容哈希去重 + 图床转存 + 标签写入仍走 `ImageService.persistOrReuse`。
- 失败仅 `log.warn` 并返回 null，不阻断车型同步。
- `persistIntroImages` 继续按序号命名 `<name>-<n>.<ext>`，标签 `车型-<name>`。
- 封面命名 `<name>-cover.<ext>`，标签 `[车型-<name>, 车型主图]`，`source=byd`、`project_id=null`、`createdBy=system`。

### 3.2 统一图片落库

新增：

```java
protected void persistImages(CarModelEntity model, GoodsInfoDto.Item item)
```

- `item.img` 非空 → 入库封面；成功则 `model.setCoverImageId(id)`；失败/为空**不冲写**已有封面。
- `item.introduce` 非空 → 逐张入库并 `model.setIntroImages(toJson(ids))`。
- 有任一字段变化时统一 `modelMapper.updateById(model)` 一次。
- 保留 `persistModel` 先把 `intro_images` 写 raw URL 的既有行为，随后由 `persistImages` 覆写为 asset id 列表（与现有双格式收敛口径一致）。

调用点：

- 全量 `syncOne`：`persistIntroImages` 替换为 `persistImages`。
- 轻回填 `backfillInternal`（`item != null` 分支）：替换为 `persistImages`，使**官网无介绍图但有主体图**的车型也能补上封面。
- `item == null` 分支：只走 `persistLegacyIntroImages` 存量兜底，不动封面。
- 增量跳过分支：不更新封面（已知限制，写入文档）。

### 3.3 失败状态回写

- 新增 `CarModelService.markSyncFailed(String goodsId, String error)`：
  - `UpdateWrapper` 按 `goods_id` 更新：`sync_status='FAILED'`、`last_sync_error=<截断 500 字符>`、`updated_at=now()`。
  - **不更新 `last_sync_at`**（该字段保留最后成功时间）。
- `CarSyncJobService.runJob` 的 `catch` 分支调用 `markSyncFailed`，用 try/catch 包裹，避免回写失败覆盖原始异常；任务明细仍记录原错误。
- 成功路径清理失败原因：`persistModel` 与 `backfillInternal` 置 `SUCCESS` 时同时 `setLastSyncError(null)`；`markSyncing` 也清空。
- `IMAGE_BACKFILL` 失败同样回写 FAILED；增量任务失败也回写（不影响指纹分派逻辑）。
- 补充（check 阶段）：详情页阻塞同步 `POST /api/car/models/{id}/sync` 的异常分支同样调用 `markSyncFailed`（否则只有异步任务路径可信，详情页同步失败仍无 FAILED/原因）；回写异常仅 `log.warn`，响应保留原始错误。

## 4. 前端设计

### 4.1 组件拆分

新增 `frontend/src/components/car/`：

- `CarJobPanel.vue`
  - props：`job`、`retrying`。
  - emits：`retry`、`close`。
  - 职责：任务头部（类型/状态/计数）、进度条、失败/备注明细、失败项重试按钮。从 `CarSync.vue` 原样抽出。
- `CarCatalogGrid.vue`
  - props：`catalog`、`syncedIds`、`modelValue`（已选 goodsId 数组）。
  - emits：`update:modelValue`。
  - 职责：官网目录卡片网格、搜索/入库状态筛选、全选、已选计数；不负责请求与任务创建。
- `CarCatalogDrawer.vue`
  - props：`modelValue`（visible）、`syncedIds`。
  - emits：`update:modelValue`、`created`（`{ jobId, type }`）。
  - 职责：抽屉壳 + 加载 catalog + 同步选中 + 图片补入库；创建成功后关闭并上抛任务。
- `frontend/src/composables/useCarSyncJob.js`
  - 返回：`job`、`jobId`、`jobRunning`、`progress`、`startPoll(jobId)`、`stopPoll()`、`recoverLatest()`、`retryCurrent()`。
  - 轮询 2s；终态停止；连续 3 次轮询失败停止并提示；`onBeforeUnmount` 由宿主调用 `stopPoll`。
  - `recoverLatest()`：`carApi.listJobs()` → 优先第一条 `RUNNING`；否则取最近一条（24h 内）终态任务展示。

`CarSync.vue` 与 `CarKnowledgePanel.vue` 共用上述组件与 composable，消除重复实现。

### 4.2 知识中心车型 Tab 结构

工具栏：

- 搜索（名称/网络）、网络筛选、状态筛选（全部/已同步/失败/同步中/待同步/无封面）。
- 排序（最近同步 / 名称 / 价格）。
- 刷新按钮（`Refresh`，显式 import）。
- 「同步车型」打开 `CarCatalogDrawer`。
- 「运维」`el-dropdown`：图片补入库（打开抽屉的补入库动作或直接触发）、重建全部向量。

统计条：

- 全部车型、已同步、失败、同步中、封面覆盖 `coverImageUrl 非空数 / 总数`。
- 移除全库 `source=byd` 图片数请求（语义误导且多一次请求）。

任务区：

- `CarJobPanel` 展示当前或恢复的任务；终态任务可关闭。
- 创建任务后立即轮询；刷新/切 Tab 回来通过 `recoverLatest()` 恢复。

批量操作：

- 仅 EDITOR/ADMIN 显示每卡 checkbox（恒定可见，不依赖 hover）。
- 选中后出现批量条：已选 N、批量同步（`createJob(goodsIds)`）、批量删除（逐个 `remove`，`Promise.allSettled`，汇总成功/失败数）。
- 批量删除二次确认文案含「不删除共享图库资产」。

卡片：

- 缩略图 `coverUrlOf(row)`（封面优先，回退 `introImageUrls[0]`），`fit="contain"` + 纸色底 + 内边距，避免透明 PNG 被裁切。
- 状态标签 + 最后成功时间；失败时展示 `lastSyncError`（卡片内截断 + tooltip）。
- 操作：详情、同步、失败时「重试」、删除。
- 无图统一占位图标。

### 4.3 详情页

`CarDetail.vue` 增加：

- Hero 区：封面（无则首图）大图；无图隐藏。
- 图集：`introImageUrls` 缩略图行，`el-image` 用 `preview-src-list` 指向原图 URL。
- 卖点：`features` 为 JSON 字符串，解析为 chips；解析失败静默不渲染。
- 失败状态：`lastSyncError` 以 `el-alert` 展示。
- 同步完成后刷新详情（保留现有阻塞同步）。

### 4.4 纯函数

新增 `frontend/src/utils/carDisplay.js`：

- `coverUrlOf(row)`：`coverImageUrl || introImageUrls?.[0] || ''`。
- `parseFeatures(raw)`：兼容字符串/数组，失败返回 `[]`。
- `formatPrice(range)`：`"96,800 - 126,800"` → `9.68–12.68 万`；无法解析原样返回。
- `sortCars(rows, key)`：`recent`（lastSyncAt 倒序，空值最后）/ `name`（localeCompare zh）/ `price`（解析下限升序，空值最后）。
- `statusMeta(status)`：标签文案/类型/颜色。

## 5. 兼容与迁移

- `cover_image_id` 对存量行为 NULL；前端回退逻辑保证旧数据展示不变（仍显示详情图首图）。
- 存量封面补齐：部署后由 ADMIN/EDITOR 在知识中心「运维 → 图片补入库」触发一次 `IMAGE_BACKFILL`；该任务已具备内容哈希去重，可重复执行。
- `intro_images` 字段语义不变；封面独立字段不参与正文配图/向量域。
- `http.js` 已拆包，所有调用读 `res.data`。
- 新增 `listJobs` 调用在 E2E fixtures 中补 mock（`/api/car/models`、`/api/car/sync/jobs`），避免未 mock 404 噪声。

## 6. 测试与验证

后端：

- `mvn -q -DskipTests compile`。
- `mvn test`，补充：
  - `CarModelBackfillImagesTest`：`img` 有值时写封面；`img` 空时保留旧封面；仅 `img` 无 `introduce` 时也写封面。
  - `CarSyncJobService` 失败路径回写 `markSyncFailed`（mock 验证）。
  - `CarModelService.markSyncFailed` 更新语句字段（ArgumentCaptor）。
- 前端：`npm run build`。
- E2E：`npx playwright test tests/smoke/console-noise.spec.js`（知识中心无 console 错误）。
- 手动联调：`./dev.sh start` → 知识中心 → 图片补入库 → 抽查 3 台车型封面、失败重试、刷新后任务恢复。

## 7. 风险与回滚

- Flyway 版本协调：V24/V25 在未合并的 BYD 分支，本任务用 V26；合并顺序需确认。
- 图片回填会集中下载 47 台车型主图（每台 1 张），哈希去重后图床增量有限；失败单图不阻断。
- 失败状态回写只在任务 catch 分支，若回写本身失败仅告警，不影响任务终态。
- 回滚：前端可单独回退；后端新增列可保留（NULL 无副作用），代码回退后字段闲置；不建议回删迁移。
