# 设计：车型清洗版本对齐与异步补清洗

## 1. 版本对齐

`CarCleanService.cleanForModel`：
- `int limit = Math.min(values.size(), versions.size());`
- 循环 `i < limit`；若 `values.size() > versions.size()`，log.warn 记录 param/version 数量并跳过越界值。
- 不再生成 `version_id=null` 的 cleans；历史行通过 force 补清洗删除重建。

## 2. 异步 CLEAN_BACKFILL

复用 `sparkora_car_sync_job`，`job_type=CLEAN_BACKFILL`：

- `CarCleanRepairService`：
  - `listTargetGoodsIds(boolean force)`：遍历车型，params>0 且（force 或 cleans==0）加入。
  - `cleanOneByGoodsId(String goodsId, boolean allowAi)`：按 goods_id 查车型 → cleanOne(modelId, allowAi)。
- `CarSyncJobService`：
  - 字段注入 `CarCleanRepairService`（不破构造器测试）。
  - `createCleanJob(boolean force)`：目标为空 400；markStale/hasFreshRunning 守卫；`insertJobAndRun(goodsIds, "CLEAN_BACKFILL")`。
  - `runJob` 增加 `CLEAN_BACKFILL` 分支：逐 goodsId 调 `cleanOneByGoodsId(goodsId,false)`，`cleansAfter>0` success++，否则 skipped++；失败 catch 计入 failed；不 markSyncing。
- Controller：`POST /api/car/models/clean-jobs?force=false`（ADMIN）→ `{jobId}`；错误 400/409。
- 前端：
  - `carApi.startCleanJob(force)`。
  - `CarKnowledgePanel` 运维「补全清洗参数」改为创建 job 并 `startPoll`；`CarJobPanel` jobTypeLabel 增「补清洗」。
  - `useCarSyncJob.toastFinished` 增 CLEAN_BACKFILL 文案。
- 保留同步 `/models/clean-missing` 作为运维兜底，不删。

## 3. 测试

- `CarCleanServiceSafetyTest`：values 3 / versions 2 → 2 inserts，versionId 均非 null。
- `CarCleanRepairServiceTest`：listTargets、cleanByGoodsId。
- `CarSyncJobServiceConcurrencyTest`：CLEAN_BACKFILL 分支计数（可选）。
- 前端 build + Playwright。

## 4. 生产修复

1. 部署。
2. `POST /api/car/models/clean-jobs?force=true`（ADMIN）→ 47 台 job；轮询至终态。
3. 统计 FALLBACK；验证 `/car/14` 等详情；向量块不变（首版本清洗未变，null 行本就不参与切块）。
4. 推送。

## 5. 风险

- force 重清洗会删除并重建全部 cleans，耗时约 6–8 分钟；异步 job 可见进度。
- 批量仍只走规则引擎（allowAi=false），剩余 FALLBACK 保留原值；本轮不调 AI。
