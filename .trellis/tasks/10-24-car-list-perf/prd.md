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

- [x] 单测断言列表只调用一次 `selectBatchIds`，不再逐 id 查询。
- [x] `mvn test`、`npm run build`、Playwright smoke 通过。
- [x] 部署后 `GET /api/car/models` 明显下降（目标 <1s），页面卡片仍正常显示封面/创建时间。
- [x] 代码推送。

## Out of Scope

- 卡片缩略图/虚拟滚动/分页（另开 UX 任务）。
- 详情分组参数/清洗 N+1。


## 生产验收（2026-10-24）

- 部署 `merge: 车型列表图片 URL 批量查询`（`5748a1f`）。
- `GET /api/car/models` 直接 curl：冷启动 1.8–2.1s，预热后 **0.79s**（修复前 1.9–4.4s）。
- 浏览器预热后：API 1.4s，首卡可见约 2.5s（修复前 API 2.7–7.2s、首卡约 8s）。
- 46 封面 / 46 创建时间仍正常；浏览器 console error 0。
- `mvn test` 1313 passed；`npm run build` 通过；Playwright smoke 33 passed。
- 残留（未在本任务范围）：详情接口约 0.77s（分组 N+1）；46 张封面约 9MB、11 张详情回退大图，滚动/解码仍可能卡；需缩略图/虚拟滚动另开 UX 任务。
