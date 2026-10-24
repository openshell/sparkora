# 同步记录与车型创建时间

## Goal

让车型页能看到历史同步记录；采集官网 `goodsInfo.createdAt` 并在卡片/详情展示为「创建时间」（**不命名为发布时间**）。

## Background

- 后端 `/api/car/sync/jobs` 已有历史任务，前端只用 `recoverLatest` 恢复最近一条，没有历史入口。
- 官网 `goodsInfo` 无显式 `releaseDate`；用户确认使用 `createdAt`（官网数据创建时间），UI 文案固定为「创建时间」。
- 现有 `car_model.created_at` 是系统审计字段，不能复用；需新增 `source_created_at` 独立列。

## Requirements

### R1 同步记录（P0）

- 知识中心车型 Tab 与 `/car/sync` 增加「同步记录」入口。
- 抽屉展示最近任务：类型、状态、成功/跳过/失败、开始/结束时间、操作人；可选中查看明细（复用 `CarJobPanel`）。
- 支持刷新；不改变现有轮询/恢复逻辑。

### R2 官网创建时间（P0）

- `GoodsInfoDto.Item` 接收 `createdAt`；新增 `car_model.source_created_at`（TIMESTAMP，V28）。
- 全量同步、轻回填写 `sourceCreatedAt`；只非空覆盖，不覆盖系统审计 `created_at`。
- 卡片显示「创建 YYYY-MM-DD」（有值才显示）；详情显示「创建时间：YYYY-MM-DD」。
- 存量 57 台通过一次性只读官网 `goodsInfo` 的 backfill 接口补齐（不触版本/参数/清洗/向量）。

### R3 收尾

- 部署、回填、生产页面验证；测试、文档、推送。

## Acceptance Criteria

- [x] 知识中心/`/car/sync` 可打开同步记录抽屉，看到历史任务及明细。
- [x] 详情页与卡片显示官网 `createdAt` 的「创建时间」；空值不显示占位错误。
- [x] 全量同步/轻回填不会用 null 覆盖已有 `source_created_at`。
- [x] 一次性回填后 57 台中有官网 createdAt 的车型均可显示。
- [x] `mvn test`、`npm run build`、Playwright smoke 通过；代码已推送。

## Out of Scope

- 把 `createdAt` 称为「上市时间/发布时间」。
- 同步历史分页/导出（当前列表前端截取最近 50 条）。
- 官网没有 createdAt 的车型人工补录。
