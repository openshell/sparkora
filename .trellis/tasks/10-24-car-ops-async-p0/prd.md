# 车型运维 P0:重建向量异步化、详情页同步/清洗任务化、backfill 支持选中车型

## Goal

修复车型运维三条 P0 缺陷（来源：10-24 知识中心车型页评审）：

1. **R1 重建向量必超时**：「重建全部向量」是同步接口 + 前端 300s 超时，而全库 57 台 × 每台十几秒 ≈ 570s+，**必然超时**；前端报「批量重建失败」但后端仍在跑，用户重试会叠加全库重建。
2. **R2 详情页阻塞调用游离于任务体系外**：详情页「同步」/「重新清洗」是 300s 阻塞 HTTP 调用（`POST /models/{id}/sync`、`POST /models/{id}/clean`），无进度、不可取消、与任务体系的 `jobBusy` 互不知情（可并发打同一车型，仅靠后端 goodsId 锁兜底）。
3. **R3 EMPTY 车型补图无闭环**：`sourceState=EMPTY` 的车型全量同步被禁用、批量同步被拦截，提示「请使用图片补入库」——但 backfill 是全库动作（无参数），没有按选中车型定向执行的入口，引导做不到。

## Requirements

### R1 重建向量异步化
- 新增异步任务 `VECTOR_REBUILD`：逐车型 `rebuildForModel`，复用现有任务体系（全局锁/逐车型锁/心跳/取消/重试/进度轮询）。
- 新接口 `POST /api/car/models/rebuild-jobs`（ADMIN），返回 `{jobId}`；前端「运维 → 重建全部向量」迁移到该接口 + `useCarSyncJob` 轮询。
- 单车型向量失败（`EmbedStats.failed>0`）记为失败明细条目（含 error），**可通过任务「重试失败项」重跑**。
- 旧接口 `POST /models/rebuild-all` 保留不动（一次性兜底），前端不再调用。

### R2 详情页同步/清洗任务化
- 详情页「同步」改走 `POST /api/car/sync/jobs`（`goodsIds=[该车型]`，`job_type=SELECTED`），复用 `markSyncing`/失败回写/清洗统计聚合等既有任务分支，零新 job 逻辑。
- 新增异步单车型清洗任务 `CLEAN_ONE`：`POST /api/car/models/{id}/clean-jobs`（ADMIN/EDITOR，与旧 `/clean` 同权限），`allowAi=true`（与详情页旧 cleanOne 语义一致，区别于批量 CLEAN_BACKFILL 的 allowAi=false）。
- 详情页内嵌 `CarJobPanel` 展示进度；任务终态后自动重载详情；其他任务运行中时创建返回 409 并提示。
- 旧接口 `POST /models/{id}/sync`、`POST /models/{id}/clean` 保留不动，前端不再调用（api/index.js 移除 `syncOne`/`cleanOne`/`rebuildAll`/`cleanMissing` 绑定）。

### R3 backfill 定向化
- `POST /api/car/sync/backfill` 支持可选请求体 `{goodsIds: [...]}`：非空时**只处理指定车型**（忽略 mode 过滤，显式选择即目标，含 EMPTY 车型走存量 URL 兜底）；空/缺省时行为不变（MISSING 过滤 / FULL 全库）。
- 指定 goodsIds 中不存在于库内的 → 400「车型不存在」；沿用去重/上限 100 校验。
- 前端车型 Tab 批量栏新增「补选中图片」（EDITOR+，有选中时可见）→ 定向 backfill；EMPTY 车型的拦截提示与卡片 tooltip 同步指向该入口。

### 约束
- 不做 DB 迁移（`job_type` 为自由字符串列，新增枚举值无需改表）；不改动指纹/同步/清洗域核心逻辑，只动任务分派与入口。
- 后端注释/commit 中文；接口错误码沿用 400（参数）/409（任务冲突）/500 映射；`R<T>` 包装 + 方法级 `@PreAuthorize` 不变。
- 前端沿用 `useCarSyncJob` 范式（beginStart/cancelStart 防重、终态刷新、onBeforeUnmount 清理）。
- spec 三处同步：`docs/spec/knowledge/car.md`（job_type/接口表）、`docs/spec/knowledge/center.md`（车型 Tab/详情页契约）。

## Acceptance Criteria

- [ ] AC1 `POST /api/car/models/rebuild-jobs` 创建 `VECTOR_REBUILD` 任务并异步执行；`EmbedStats.failed>0` 的车型落失败明细（含 error，可重试）；无切块车型计 skipped + note；任务面板/同步记录正确显示类型「重建向量」。
- [ ] AC2 详情页「同步」创建单车型 SELECTED 任务（复用既有分派），「重新清洗」创建 `CLEAN_ONE` 任务（allowAi=true）；两者在详情页显示任务进度，终态后自动刷新详情；已有任务运行中时创建返回 409 并中文提示。
- [ ] AC3 `POST /api/car/sync/backfill` 收 `{goodsIds}` 时只处理指定车型（含 EMPTY 车型存量 URL 兜底）；未知 goodsId 返回 400；不传 body 时行为与现状完全一致（回归）。
- [ ] AC4 前端批量栏「补选中图片」仅对选中车型发起定向 backfill；EMPTY 车型卡片/拦截提示文案指向新入口。
- [ ] AC5 后端 `mvn test` 全绿，新增任务分派/定向校验用例覆盖 VECTOR_REBUILD、CLEAN_ONE、backfill goodsIds 三条新路径。
- [ ] AC6 前端 `npm run build` exit 0；`api/index.js` 无 `syncOne`/`cleanOne`/`rebuildAll`/`cleanMissing` 死绑定；`useCarSyncJob`/`CarJobPanel`/`CarJobHistoryDrawer` 识别两种新 job_type。
- [ ] AC7 `docs/spec/knowledge/car.md` 与 `center.md` 完成字段级/契约级更新（新接口行、job_type 表、旧接口「前端已迁移」标注、车型 Tab 批量栏与详情页契约）。

## Notes

- 评审来源：10-24 知识中心车型页审查（P0-1/P0-2/P0-3）；P1/P2 项（筛选口径对齐、URL 持久化、参数搜索、clean-stats 可视化等）不在本任务范围。