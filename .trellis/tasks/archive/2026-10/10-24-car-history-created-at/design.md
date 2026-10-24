# 设计

## 1. 数据

- V28: `ALTER TABLE sparkora_car_model ADD COLUMN source_created_at TIMESTAMP;`
- `CarModelEntity.sourceCreatedAt`。
- `GoodsInfoDto.Item.createdAt`（官网 `createdAt` 字符串，如 `2026-10-20 17:44:53`）。
- `CarModelService.parseSourceCreatedAt` 解析 `yyyy-MM-dd HH:mm:ss` / ISO；失败/空返回 null，保留旧值。
- `persistModel`/`backfillInternal` 仅在解析成功时 set。
- 新增 `CarModelService.backfillSourceCreatedAt()`：遍历车型，仅调 `goodsInfo`，按 `goods_id` 只更新 `source_created_at`；网络/解析失败逐台记录，不动其他列。Controller `POST /api/car/models/backfill-created-at`（ADMIN，同步，约 60s）。

## 2. 同步记录 UI

新增 `components/car/CarJobHistoryDrawer.vue`：
- props `modelValue`；打开时 `carApi.listJobs()`，前端取最近 50 条。
- 表格列：创建时间/类型/状态/成功/跳过/失败/操作人；点击行选中，下方用 `CarJobPanel` 展示明细（不显示重试/取消按钮）。
- 刷新按钮；空态；加载/错误三态。
- `CarKnowledgePanel` 工具栏与 `/car/sync` 各加「同步记录」按钮（VIEWER 也可查看，写操作仍由后端权限控制）。

## 3. 创建时间展示

- `carDisplay.js` 增加 `formatDate(value)` → `YYYY-MM-DD`（兼容 ISO/空格）。
- 卡片 `c-meta` 追加 `· 创建 YYYY-MM-DD`（仅非空）。
- `CarDetail` Hero 信息行显示「创建时间：YYYY-MM-DD」。

## 4. 测试

- 后端：解析格式/空值；persistModel 保留旧值；backfill 只更新 source_created_at。
- 前端：build + Playwright 冒烟（历史抽屉打开、创建时间存在）。
- 生产：回填接口、列表/详情抽查。

## 5. 回滚

- V28 只加列；代码回退保留列。同步记录 UI 独立，可单独回退。
