# 实施计划：车型同步数据安全与可靠性修复

## 0. 准备与止血

- [x] 新建分支 `fix/car-sync-safety`（已创建）。
- [x] 只读体检脚本/SQL：统计 57 台的官网响应、版本/参数/清洗/块数量、封面/图集、同步时间、失败明细；输出报告，不改数据。
- [ ] 备份 `sparkora_car_model`、`car_version`、`car_param_group`、`car_param`、`car_param_clean`、`car_chunk` 及向量表。
- [x] 保持 `car_sync_enabled=false`；通知运维：11 台无主图车暂不点「同步」，只用「图片补入库」。
- [x] 前端临时止血：`source_state=EMPTY` 时禁用全量同步（若后端字段尚未上线，可先按 `coverImageUrl==null` 隐藏「同步」并提示「官网无数据，请用图片补入库」）。

## 1. 数据模型与预检（P0）

- [x] `V27__car_sync_safety.sql`：`car_model` 加 `source_state/sync_note/last_checked_at/image_fingerprint/params_fingerprint`；`car_sync_job` 加 `heartbeat_at/cancel_requested/skipped`；新增 `sparkora_car_sync_lock` 锁表。
- [x] `image_fingerprint/params_fingerprint` 已合并进 V27（一次迁移）。
- [x] `CarModelEntity` / `CarSyncJobEntity` 字段同步。
- [x] `BydCmsClient`：按端点校验 `code`/`data`；catalog 失败抛错；HMAC 单时间戳；有限重试/退避/限流。
- [x] `CarModelService.syncOne`：先拉三接口 + 预检；空/不完整 → `markSourceIncomplete`，不写业务表。
- [x] `CarModelService` 新增 `markSourceIncomplete/markSyncSuccess/markSyncPartial/markChecked/markSyncFailed`，统一状态/时间/note。

## 2. 安全写入（P0）

- [x] `persistModel` 改选择性合并（非空才写）；移除 SUCCESS/lastSyncAt；`intro_images` 不再写 raw URL。
- [x] `persistVersions/persistParams`：空数据保留旧值；非空才 delete+insert；`persistCore/persistParamsCore` + `self` 代理保证单事务。
- [x] `persistImages` 返回 `ImagePersistResult`；封面失败保留旧封面；图集全有或全无；失败写 note。
- [x] `syncOne` 最后一步落终态：SUCCESS 仅在全链路成功；PARTIAL/FAILED 不前进 `last_sync_at`。
- [x] `backfillInternal` 适配新状态/note；空数据不再计成功（EMPTY/FAILED）。

## 3. 任务可靠性与并发（P0/P1）

- [x] `createJob/createBackfillJob` 插入后通过 afterCommit 触发 `runJob`。
- [x] 全局锁（`sparkora_car_sync_lock`）+ 按 `goodsId` 锁；`createJob/createBackfillJob/retry/详情页 syncOne/删除` 统一检查。
- [x] `runJob` 外层 try/finally；异常落终态并写 error_msg。
- [x] heartbeat/cancel；`markStaleRunningAsFailed` 改为 heartbeat 优先；旧线程写前校验 RUNNING、条件写不覆盖终态/取消标记。
- [x] 任务计数区分 success/skipped/failed；INCREMENTAL 跳过不计 success。
- [x] `CarJobPanel` / `useCarSyncJob` 适配新计数、取消/后台运行（前端最小适配已完成）。

## 4. 增量完整性与向量（P1）

- [x] `dispatchIncremental` 拆 base/image/params 三类指纹；只图片变化只更新图片；只参数变化只更新参数/清洗/块。
- [x] 存量指纹兼容：旧口径 base 视为未变，仅迁移指纹；新指纹为空只补列不重建域。
- [x] `createBackfillJob` 默认只补缺失/失败/过期；`?mode=FULL` 全量重扫。
- [x] `CarChunkService.rebuildForModel`：先全部 embedding，成功后 `replaceChunks` 事务原子替换；失败保留旧数据；`EmbedStats.failed` 影响终态。
- [ ] `rebuildAll` 异步任务（进度/取消）**延期**：本轮保留同步接口，但失败语义已修正（部分失败计入 failed）。
- [ ] 无主图 top-crop 派生缩略图**延期**：需新增图片派生/资产字段，本任务先保证同步数据安全。

## 5. 图片/接口加固（P1/P2）

- [x] `CarImageDownloader`：HTTPS 白名单、私网拦截、重定向复检、大小/像素上限、Content-Type/魔数、ImageReader 读尺寸。
- [x] 图片失败进入任务 note；不写图库。
- [x] 失败错误分类（`CarSourceException.classify`）；原始 SQL/URL 仅日志；任务明细短文案。
- [x] `CarModelController`：goodsIds 去重/上限/合法性；400/409 语义（未新增 DTO，沿用 Map + 服务层校验）。
- [x] 详情页同步与任务锁互斥（`syncOneExclusive`）。

## 6. 前端最小适配

- [x] 车型卡片/详情：`source_state` 标签；EMPTY 禁用全量同步，保留「重新检查/图片补入库」；PARTIAL 显示 note。
- [x] 统计条/筛选增加「官网无数据」；任务面板显示成功/跳过/失败/备注。
- [x] `source_state=EMPTY` 的空态文案不再引导点击不可用按钮。
- [x] 不引入新 UI 框架；沿用 Element Plus 与现有密度。

## 7. 测试与验证

- [x] 后端单测：空/半响应、字段保护、图集部分失败、事务回滚（persistCore）、afterCommit、并发锁、heartbeat/cancel、跳过计数、向量失败、HMAC、图片安全、输入校验。
- [x] `mvn -q -DskipTests compile`
- [x] `mvn test`（1287 passed / 0 failed）
- [x] `cd frontend && npm run build`（前端另行处理）
- [x] `cd frontend && npx playwright test tests/smoke`（31 passed）
- [x] `git diff --check`
- [ ] 手工/联调：11 台 EMPTY 不改数据；正常车同步；并发只执行一个；图片补入库只补缺失；任务不卡 RUNNING。

## 8. 存量体检与灰度

- [x] 只读体检报告：57 台、11 EMPTY、46 封面、向量 412/412；见 `audit-report.md`（生产库状态标记待部署后执行）。
- [ ] 备份确认。
- [ ] 修复 11 台状态（EMPTY）与异常车（安全重同步/STALE）；不删除图库资产。
- [ ] 部署保持 `carSyncEnabled=false`；手动验证后开定时。
- [ ] 观测：任务成功/部分/失败、source_empty、image_failed、vector_failed、stale、锁等待。

## 风险文件与回滚点

- `src/main/resources/db/migration/V27...` / `V28...`：与 BYD V24/V25 合并顺序；只加列，回滚保留。
- `CarModelService.java`：预检/合并/状态集中改动，先跑空响应与图片失败测试。
- `CarSyncJobService.java`：afterCommit/锁/终态；先跑并发与异常测试。
- `CarChunkService.java`：先建后换；保留旧块回滚路径。
- `BydCmsClient.java` / `ImageService.java`：接口/图片安全；单测覆盖 SSRF/超大图/HTML。
- 前端 `CarKnowledgePanel.vue` / `CarJobPanel.vue` / `useCarSyncJob.js`：仅状态/计数/禁用逻辑，不改版式。

## 完成定义

- PRD 验收清单全部通过或明确记录延期。
- 空数据车点击同步不改变版本/参数/清洗/向量；任务不永久 RUNNING；并发不重复写。
- 全量测试、前端构建、Playwright 冒烟通过。
- 规格文档与实现同步；体检报告与灰度说明归档。


## 本轮完成情况（2026-10-21）

- 已完成：V27 迁移（含 job skipped/heartbeat/cancel、锁表、车型 source_state/note/last_checked_at/image_fingerprint/params_fingerprint）；BydCmsClient code/data 校验/重试/限流/HMAC 单时间戳；syncOne 预检零业务写入；persistModel 选择性合并；版本/参数事务；图片集全有或全无 + 失败 note；syncOne 终态后置；afterCommit 触发；全局 + 按车型锁；heartbeat/cancel；外层 finally；skip 计数；image/params 指纹分派；向量两阶段替换；图片下载白名单/大小/像素/内容/尺寸校验；前端 EMPTY 禁用、官网无数据统计/标签、任务跳过计数/取消、全量重扫入口。
- 测试：`mvn test` 1304 passed；`npm run build` 通过；Playwright smoke 31 passed。
- 只读体检：见 `audit-report.md`（57 台 / 46 封面 / 11 空条目；向量 412/412 无缺失）。未修改生产数据。
- 未执行/延期（明确记录）：
  - `rebuildAll` 异步任务化（当前仍为同步接口，已修复失败计数；耗时/取消留待 UX/运维批次）。
  - 无主图回退图的派生缩略图（当前卡片仍使用原图；下载器已限制单图 10MB/50MP，超大旧图可能不参与新回填）。
  - 生产数据修复/灰度执行（未改生产库；部署后建议先跑默认「图片补入库」将 11 台标为 EMPTY，并保持 `car_sync_enabled=false` 观察）。
  - 存量 46 台的 params 指纹首次初始化：首次增量仍只补参数指纹、不先重建参数/清洗/块（避免全库重清洗）；部署后建议手动执行一次参数同步或观察后再依赖参数变化检测。图片指纹首次初始化已改为真正补图，失败保持 NULL 并在下一次增量重试。
