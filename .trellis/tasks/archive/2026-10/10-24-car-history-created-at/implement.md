# 实施清单

- [x] V28 + entity + GoodsInfoDto.createdAt。
- [x] parse/persist/backfillSourceCreatedAt + Controller。
- [x] CarJobHistoryDrawer + 知识中心/`/car/sync` 入口。
- [x] 卡片/详情「创建时间」。
- [x] 测试/文档/build。
- [x] 部署 + 回填 + 生产验证 + 推送。


## 生产验收（2026-10-24 14:0x）

- V28 已应用；`POST /api/car/models/backfill-created-at`：57 台，updated=46 / skipped=11 / failed=0，耗时 23.5s。
- 46 台有官网 createdAt 的车型已写入 `source_created_at`；11 台官网 EMPTY 无 createdAt，UI 不显示创建时间。
- `/car/14` 详情显示「创建时间：2026-07-31」；知识中心卡片显示「创建 2026-07-31」。
- 同步记录抽屉可打开，生产 12 条历史任务（含 CLEAN_BACKFILL），console error 0。
- `mvn test` 1312 passed；`npm run build` 通过；Playwright smoke 33 passed。
