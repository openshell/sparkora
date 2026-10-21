# 执行计划：知识中心车型 Tab 同步与交互整改

## 执行顺序

### A. 后端数据层

- [x] A1 新增 `src/main/resources/db/migration/V26__car_model_cover_image.sql`（`cover_image_id BIGINT` + 注释）。
- [x] A2 `GoodsInfoDto.Item` 增加 `private String img;`。
- [x] A3 `CarModelEntity` 增加 `Long coverImageId` 与非持久化 `String coverImageUrl`。
- [x] A4 `CarModelService.fillIntroImageUrls` 扩展为 `fillImageUrls`，同时填充 `coverImageUrl`；`list()`/`detail()` 调用点更新。

### B. 后端同步链路

- [x] B1 抽通用入库方法 `persistImageToLibrary(model, url, fileName, tags)`；`persistIntroImageUrl` 改为调用它。
- [x] B2 新增 `persistImages(model, item)`：封面（`img`）+ 详情图集（`introduce`）一次落库；封面为空/失败不冲写旧值。
- [x] B3 `syncOne` 与 `backfillInternal(item != null)` 改调 `persistImages`；确认无 `introduce` 但有 `img` 的车型也能写封面。
- [x] B4 新增 `markSyncFailed(goodsId, error)`（`FAILED` + `last_sync_error` 截断 500 + `updated_at`，不改 `last_sync_at`）。
- [x] B5 `CarSyncJobService.runJob` catch 分支调用 `markSyncFailed`（try/catch 包裹）；成功路径（`persistModel`/`backfillInternal`）清空 `lastSyncError`；`markSyncing` 清空。
- [x] B6 确认 `IMAGE_BACKFILL`、`INCREMENTAL` 失败路径同样回写，且不影响指纹分派。

### C. 后端测试

- [x] C1 `CarModelBackfillImagesTest` 增加：封面写入、空 `img` 保留旧封面、仅 `img` 无 `introduce` 也写封面。
- [x] C2 新增/扩展失败回写测试（`markSyncFailed` 字段与 `runJob` 调用）。
- [x] C2b `CarModelControllerSyncTest`：详情页阻塞同步失败回写 `markSyncFailed`、回写异常不覆盖原始错误、车型不存在 404 不写状态。
- [x] C3 `mvn -q -DskipTests compile` + `mvn test`（1185 passed）。

### D. 前端基础设施

- [x] D1 新增 `frontend/src/utils/carDisplay.js`：`coverUrlOf` / `parseFeatures` / `formatPrice` / `sortCars` / `statusMeta`。
- [x] D2 新增 `frontend/src/components/car/CarJobPanel.vue`（从 `CarSync.vue` 抽取任务面板）。
- [x] D3 新增 `frontend/src/components/car/CarCatalogGrid.vue`（目录网格 + 筛选/全选，纯展示）。
- [x] D4 新增 `frontend/src/components/car/CarCatalogDrawer.vue`（抽屉 + catalog 加载 + 同步/补入库）。
- [x] D5 新增 `frontend/src/composables/useCarSyncJob.js`（轮询/恢复/重试/连续失败停止）。

### E. 知识中心车型 Tab

- [x] E1 重写 `CarKnowledgePanel.vue` 工具栏：搜索/网络/状态/排序/刷新/同步车型（抽屉）/运维下拉。
- [x] E2 统计条改为：全部、已同步、失败、同步中、封面覆盖；移除 `imageApi.list` 请求。
- [x] E3 接入 `CarJobPanel` + `useCarSyncJob`；挂载时 `recoverLatest()`，创建任务后 `startPoll`。
- [x] E4 批量选择（checkbox 恒定可见，EDITOR+）、批量同步、批量删除（`Promise.allSettled` + 汇总提示）。
- [x] E5 卡片缩略图 `coverUrlOf` + `fit="contain"` + 纸色底；失败原因 tooltip；失败卡片显示「重试」。
- [x] E6 空态/骨架/错误三态与清除筛选、筛选结果数。
- [x] E7 删除确认文案补充「不删除共享图库资产」。

### F. 同步页复用

- [x] F1 `CarSync.vue` 改用 `CarCatalogGrid` + `CarJobPanel` + `useCarSyncJob`，删除重复逻辑与样式。
- [x] F2 保留 `/car/sync` 路由与「返回知识中心」；确认无导航覆盖告警。

### G. 详情页

- [x] G1 `CarDetail.vue` 增加 Hero 封面（无则首图）、图集（原图 `preview-src-list`）、卖点 chips。
- [x] G2 增加 `lastSyncError` 告警；同步后刷新详情。
- [x] G3 图片缺失/features 解析失败静默降级，不破版。

### H. E2E 与文档

- [x] H1 `frontend/tests/fixtures/index.js` 增加 `/api/car/models`、`/api/car/catalog`、`/api/car/sync/jobs` 的 mock。
- [x] H1b `frontend/tests/smoke/car-panel.spec.js`：车型 Tab 封面优先/详情图回退、统计条口径（含封面覆盖 1/2）、失败原因展示、「无封面」筛选与清除筛选。
- [x] H2 `docs/spec/knowledge/car.md`：`cover_image_id`/`coverImageUrl` 字段、同步/回填链路、接口与前端更新。
- [x] H3 `docs/spec/knowledge/center.md`：车型 Tab 新能力与运维入口。
- [x] H4 `docs/spec/image.md`：封面标签 `车型主图` 与 BYD 图片来源说明。

### I. 最终验证

- [x] I1 `mvn -q -DskipTests compile`
- [x] I2 `mvn test`（1182 passed）
- [x] I3 `cd frontend && npm run build`
- [x] I4 `cd frontend && npx playwright test tests/smoke/console-noise.spec.js`
- [ ] I5 手动：`./dev.sh restart backend` + 前端热更新 → 运维「图片补入库」→ 抽查封面/失败状态/任务恢复。
- [x] I6 对照 PRD 验收清单逐条勾选，记录未覆盖项（仅 I5 真机回填待环境；本地 5661 被产线容器占用）。

## 风险文件与回滚点

- `src/main/resources/db/migration/V26__car_model_cover_image.sql`：版本号与未合并 BYD 分支 V24/V25 的协调；迁移只加列，回滚保留列即可。
- `CarModelService.java`（717 行）：图片落库与状态回写集中改动，改动后先跑 `CarModelBackfillImagesTest`。
- `CarSyncJobService.java`：失败回写包裹 try/catch，避免影响任务终态。
- `CarKnowledgePanel.vue`（300 行）重写：保留原三态与轮询清理；新组件先落地再替换。
- `CarSync.vue` 复用改造：若回归成本高，可保留旧实现，仅知识中心使用新组件（回滚点）。
- Flyway 合并顺序：若 V24/V25 尚未合入 main，本任务 V26 先合入后可能使 BYD 分支迁移被判 ignored；合并前需协调。

## 完成定义

- PRD 验收清单全部通过或明确记录延期。
- 后端编译 + 全量测试通过；前端构建 + console-noise 冒烟通过。
- 规格文档与实现同步。
