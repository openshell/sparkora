# 车型列表图片 URL 批量查询性能修复

## Goal

消除 `GET /api/car/models` 的图片 URL N+1 查询，降低车型页初始加载耗时。

## Background

- 实测 `GET /api/car/models` 1.9–4.4s、TTFB 2.7s，响应仅 119KB。
- `CarModelService.list()` 逐台 `fillImageUrls`，每个封面/详情图都 `ImageService.publicUrl → imageMapper.selectById`；57 台约 100 次单条查询。
- `/api/car/models/14` 0.67s（详情分组 N+1，次要）；`/api/car/sync/jobs` 0.06s。

## Requirements

- 列表一次性收集全部 cover/intro asset id，用 `imageMapper.selectBatchIds` 批量取资产，再在内存拼 `coverImageUrl`/`introImageUrls`。
- 保持旧 URL 数组兼容、缺失图片跳过、封面不回退详情图。
- 不改变接口字段/前端逻辑。
- 详情接口可保持现状；本轮只修列表主瓶颈。

## Acceptance

- [ ] 单测断言列表只调用一次 `selectBatchIds`，不再逐 id 查询。
- [ ] `mvn test`、`npm run build`、Playwright smoke 通过。
- [ ] 部署后 `GET /api/car/models` 明显下降（目标 <1s），页面卡片仍正常显示封面/创建时间。
- [ ] 代码推送。

## Out of Scope

- 卡片缩略图/虚拟滚动/分页（另开 UX 任务）。
- 详情分组参数/清洗 N+1。
