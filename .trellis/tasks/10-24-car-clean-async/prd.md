# 车型清洗版本对齐与异步补清洗

## Goal

修复清洗值越界生成 `version_id=NULL` 垃圾行导致 FALLBACK 比例虚高的问题，并把批量补清洗改为可观测的异步任务，避免 6 分钟以上同步 HTTP 请求。

## Background

- 2026-10-21 生产补清洗后：47 台有参数车型均有 cleans；全库 cleans 49791 行，FALLBACK 8270（16.61%）。
- FALLBACK 高度集中在 4 台（26/41/48/2），但按版本查看这些车型每个版本的 cleans 大多为 RULE；`clean-stats` 被 `version_id=NULL` 的额外行拉高。
- 根因：`cleanForModel` 对 `values_json` 的每个下标都清洗并插入，`i >= versions.size()` 时写入 `version_id=null`；这些行不进入详情/切块，只污染统计。
- 批量补清洗当前同步接口约 6m34s，前端即使 15min 超时也体验差，需改为异步 job + 进度。

## Requirements

### R1 版本对齐（P0）

- `cleanForModel` 只清洗 `i < versions.size()` 的值；超出部分记录日志并跳过，不插入 `version_id=null` 的清洗行。
- 保留 `values.size() < versions.size()` 时部分版本无清洗的现状，并记录日志。

### R2 异步补清洗（P1）

- 新增 `CLEAN_BACKFILL` 任务类型，复用现有 `sparkora_car_sync_job`：异步、全局锁、逐车型锁、heartbeat/跳过/失败计数。
- 支持 `force=true` 重清洗所有有参数车型（用于清理历史 null-version 行）；默认只处理有参数无清洗。
- 接口返回 `{jobId}`，前端复用 `CarJobPanel`/`useCarSyncJob` 轮询。

### R3 收尾

- 重新执行一次 force 补清洗，验证 FALLBACK 统计下降、详情与向量不受影响。
- 文档、测试、代码推送。

## Acceptance Criteria

- [ ] 2 个版本 + 3 个 values 时只插入 2 行 cleans，不产生 null version_id。
- [ ] `POST /api/car/models/clean-jobs` 返回 jobId，异步执行并展示进度/跳过/失败。
- [ ] force 补清洗 47 台后 `fallbackPct` 明显下降（目标 <2%），详情页仍有数据。
- [ ] `mvn test`、`npm run build`、Playwright smoke 通过。
- [ ] 代码已推送到 origin。

## Out of Scope

- AI 精修剩余 FALLBACK（先通过版本对齐消除统计噪声；如仍有低比例再单开）。
- 新表/新迁移（复用现有 job 表）。
