# 技术设计：车型运维 P0（任务体系扩展 + 入口迁移）

> 回链：`prd.md`。改动原则：**只动任务分派与入口，不动指纹/同步/清洗域核心逻辑**；无 DB 迁移（`sparkora_car_sync_job.job_type` 为自由字符串列）。

## 1. 现状与扩展点（已核对代码）

- `CarSyncJobService.runJob`（`car/service/CarSyncJobService.java:214`）按 `job.getJobType()` 分派：`IMAGE_BACKFILL`/`CLEAN_BACKFILL`/`INCREMENTAL`/其余（SELECTED/RETRY 全量）。逐车型抢 `goods:<id>` 锁、心跳、`cancel_requested`、终态 `finish()` 收尾 —— 新 job_type 只需在分派处加 case + 在 `create*` 处落目标列表。
- 任务条目统一以 **goodsId** 为键（`normalizeGoodsIds` 去重/上限 100/格式校验）；明细条目 `{goodsId,name,error}`（可重试）与 `{goodsId,name,note}`（不可重试）两类。
- `CarModelService.rebuildAll()`（:703）已实现逐车型 `docService.rebuildForModel(modelId)` + EmbedStats 汇总 —— 异步化后按 goodsId 逐台调用同款逻辑。
- `CarCleanRepairService.cleanOneByGoodsId(goodsId, allowAi)`（:63）已存在 —— `CLEAN_ONE` 直接复用，`allowAi=true` 对齐详情页旧 `cleanOne(id)`（显式单台重跑允许 AI；批量 `CLEAN_BACKFILL` 保持 `allowAi=false`）。
- `backfillImages(goodsId)` 的 `IMAGE_BACKFILL` 分派分支零改动即可消费任意 goodsId 列表 —— 定向化只改**创建时的目标圈定**。
- 前端 `useCarSyncJob` 与 `CarJobPanel`/`CarJobHistoryDrawer` 均按 `jobType` 字符串映射文案/toast，新增两枚举值即可。

## 2. 后端设计

### 2.1 CarSyncJobService 新增/扩展

| 方法 | 签名 | 行为 |
|---|---|---|
| 新增 | `createRebuildJob()` | 目标 = 全部 `goods_id` 非空的车型（按 id 升序）；空 → `IllegalArgumentException("库内没有可重建向量的车型")`；沿用 `markStaleRunningAsFailed` + `hasFreshRunning` 409 + `insertJobAndRun(ids, "VECTOR_REBUILD")` |
| 新增 | `createCleanOneJob(String goodsId)` | 单台：`normalizeGoodsIds([goodsId])` → `insertJobAndRun(..., "CLEAN_ONE")`；车型不存在/goodsId 空 → 400 |
| 扩展 | `createBackfillJob(boolean full, List<String> goodsIds)` | `goodsIds` 非空 → 校验每个 id 在 `car_model`（未删）存在（`selectOne(goods_id in...)`，未知 → 400「车型不存在: <id>」），目标=指定列表（**忽略 full 过滤**——显式选择即目标）；`goodsIds` 空 → 现行为（full ? 全库 : 缺失/失败/过期过滤）。旧签名 `createBackfillJob(boolean)` 保留为委托重载，既有测试零改动 |

`runJob` 分派新增两个分支（与现有分支同构：异常 → failed++ + error 明细 + `markSyncFailed` **不适用**（向量/清洗域不改车型同步状态，仅记任务明细））：

```
VECTOR_REBUILD: st = modelService.rebuildOneByGoodsId(goodsId)
  - st.failed > 0  → failed++，error="向量失败 N/M 块"   // 可被任务重试重跑
  - st.total == 0  → skipped++，note="跳过:无切块数据"
  - 否则           → success++（note="重建 M 块向量" 可选）
CLEAN_ONE: r = cleanRepairService.cleanOneByGoodsId(goodsId, true)
  - r.cleansAfter > 0 → success++，note="补清洗 N 条"
  - 否则              → skipped++，note="跳过:无可清洗参数/版本"   // 与 CLEAN_BACKFILL 同文案
```

> 设计取舍：VECTOR_REBUILD 的失败走**任务失败明细**（而非车型 `syncStatus=FAILED`）——重建向量是独立运维域，失败不该污染车型同步状态；且 error 条目天然进入 `retry()` 重试候选。

### 2.2 CarModelService 新增

- `EmbedStats rebuildOneByGoodsId(String goodsId)`：按 goods_id 解析车型（含名称日志），委托 `docService.rebuildForModel(modelId)`。`rebuildAll()` 旧方法保留不动。

### 2.3 CarModelController 接口变更

| 接口 | 权限 | 变更 |
|---|---|---|
| `POST /api/car/models/rebuild-jobs` | ADMIN | 新增；调 `createRebuildJob()`，返回 `{jobId}`；400/409/500 映射与 `createCleanJob` 同款 |
| `POST /api/car/models/{id}/clean-jobs` | ADMIN/EDITOR | 新增；`service.get(id)` 404 兜底 → 取 goodsId（空 → 400「该车型缺少官网 goodsId」）→ `createCleanOneJob`，返回 `{jobId}` |
| `POST /api/car/sync/backfill` | ADMIN/EDITOR | 加 `@RequestBody(required=false) Map<String,List<String>> body`；`body.goodsIds` 非空 → `createBackfillJob(full, goodsIds)`，否则 `createBackfillJob(full, null)`。**不传 body 时契约不变**（旧调用方/测试零感知） |
| `POST /models/rebuild-all`、`/models/{id}/sync`、`/models/{id}/clean` | 不变 | **保留不动**（兼容兜底），仅前端停止调用；spec 标注「前端已迁移」 |

## 3. 前端设计

### 3.1 api/index.js（carApi）

```js
createRebuildJob: () => http.post('/car/models/rebuild-jobs'),
createCleanOneJob: (id) => http.post(`/car/models/${id}/clean-jobs`),
createBackfillJob: (full = false, goodsIds) => http.post('/car/sync/backfill',
  goodsIds?.length ? { goodsIds } : null, { params: full ? { mode: 'FULL' } : {} }),
// 删除死绑定：syncOne / cleanOne / rebuildAll / cleanMissing
```

### 3.2 useCarSyncJob.js — `toastFinished` 新增

`VECTOR_REBUILD` →「重建向量完成」（处理 N 个车型口径）；`CLEAN_ONE` →「清洗完成」（同口径）。失败/部分失败文案沿用通用分支。

### 3.3 CarJobPanel.vue / CarJobHistoryDrawer.vue — `jobTypeLabel`/`typeLabel`

新增 `VECTOR_REBUILD: '重建向量'`、`CLEAN_ONE: '单车型清洗'`。

### 3.4 CarKnowledgePanel.vue

- 「运维 → 重建全部向量」：改 `onRebuildJob` —— 确认文案保留耗时告知（改为「全库逐车型重建，任务可随时在面板查看进度」），调 `createRebuildJob()` → `beginStart`/`startPoll` 范式（复用 `onJobCreated`）；删除 `rebuildingAll` 同步等待逻辑。
- 批量栏新增「补选中图片」（`selectedIds.length && user.isEditorOrAbove`，`:disabled="jobBusy"`）：确认文案说明只处理选中车型的图片域 → `createBackfillJob(false, selectedGoodsIds)` → `startPoll`。
- EMPTY 引导文案闭环：批量同步拦截提示由「请取消选择后使用图片补入库」改为「已选车型中有 N 台官网无数据，已自动跳过全量同步；如需补图请单独使用『补选中图片』」→ 保留拦截不变（全量同步对 EMPTY 无意义），但给出可执行下一步；卡片 EMPTY tooltip 同步改「官网无数据；可勾选后使用批量栏『补选中图片』」。

### 3.5 CarDetail.vue

- 引入 `useCarSyncJob` + `CarJobPanel`；任务面板插在 page-header 之后、告警条之前。
- 「同步」→ `createJob([detail.model.goodsId])`（SELECTED 复用）；「重新清洗」确认框保留 → `createCleanOneJob(id)`。两者均走 `beginStart` → `startPoll(id, { onFinished: load })`；按钮 `:disabled="jobBusy"`。
- 挂载 `recoverLatest({ onFinished: load })`：切走再切回/刷新后恢复运行中任务（与知识中心 Tab 同口径，读接口轮询无害）。
- 删除 `syncing`/`cleaning` 阻塞等待逻辑与对应 `carApi.syncOne`/`cleanOne` 调用。
- 409（已有任务运行中）走既有 `onJobCreated` 分支的中文报错。

## 4. 兼容与风险

| 风险 | 缓解 |
|---|---|
| 旧接口保留但前端不再调用 → 半死代码 | 有意保留（ADMIN curl 兜底 + 兼容窗口）；spec 明确标注，后续任务再收口 |
| 详情页任务与知识中心 Tab 各自 `useCarSyncJob` 实例对同一 RUNNING 任务双轮询 | 只读 GET，无害；终态各自刷新各自页面 |
| `CLEAN_ONE` 与 `CLEAN_BACKFILL` 语义近似易混 | 类型文案区分（单车型清洗/补清洗）+ allowAi 语义差异写入 spec |
| 定向 backfill 传了已删/不存在 goodsId | 显式 400（列表来自前端勾选，正常路径不触发；防御性校验） |
| `VECTOR_REBUILD` 无切块车型计入 skipped | 观察口径诚实（不虚报 success），全 skipped 时任务面板已有「检查完成(无变化)」标题兜底 |

## 5. 回滚

纯代码回滚（git revert）：新接口为增量、旧接口未动、无 DB 迁移；前端随同 revert 即回到阻塞式调用。