# 实施清单

- [x] `CarCleanService` 版本上限循环，越界值跳过并日志。
- [x] `CarCleanRepairService` 增加 listTargetGoodsIds / cleanOneByGoodsId。
- [x] `CarSyncJobService` 增加 CLEAN_BACKFILL（创建 + runJob 分支 + 计数）。
- [x] Controller 增加 `POST /models/clean-jobs`。
- [x] 前端 API/运维入口/任务类型/结束文案。
- [x] 测试：版本越界、补清洗 job。
- [x] `mvn test`、`npm run build`、Playwright smoke、deploy。
- [x] force 补清洗 47 台，验证 FALLBACK/详情/向量。
- [x] 文档 + 推送 + 归档任务。


## 生产结果（2026-10-24 13:33–13:40）

- 部署 `merge: 清洗版本对齐与异步补清洗`（`ed59228`）；后台 healthy，`carSyncEnabled=false`。
- `POST /api/car/models/clean-jobs?force=true` → job 12：47 台，success=47 / failed=0 / skipped=0，约 6 分钟；异步进度正常。
- 清理效果：cleans 49791 → 41528；FALLBACK 8270（16.61%）→ **23（0.06%）**；剩余集中在 54（16）/23（3）/49（2）/27（2），无需 AI 精修。
- `/car/14`：16 组 / 202 行，全部 RULE；向量 `952/952, missing=0` 未变。
- 版本对齐未影响首版本展示/切块。
