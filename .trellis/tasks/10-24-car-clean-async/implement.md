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
