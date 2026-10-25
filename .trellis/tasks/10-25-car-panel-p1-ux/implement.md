# 执行计划：车型 Tab P1 交互补齐

> 回链：`prd.md`（需求/AC）、`design.md`（技术设计）。按序执行；每步含验证；⚠ 为回滚点。

## 阶段 A：后端单车型重建定向

- [ ] A1 `CarSyncJobService`：`createRebuildJob()` 改为委托 `createRebuildJob(null)`；新增 `createRebuildJob(List<String> goodsIds)`——非空 → `normalizeGoodsIds` 去重/校验 + 逐个 `goods_id` 存在性校验（未知 → IAE「车型不存在: x」），目标=指定列表；null/空 → 现有全库分支（id 升序、LinkedHashSet、不设上限）逐字保留。javadoc 补定向语义（design §2.1）。
- [ ] A2 `CarModelController`：`POST /models/rebuild-jobs` 加 `@RequestBody(required=false) Map<String, List<String>> body`，取 `body.get("goodsIds")` 传 service；400/409/500 映射不变；javadoc 补 body 契约（design §2.2）。
- [ ] A3 测试：
  - `CarSyncJobOpsAsyncTest`：定向只圈定指定车型（任务目标断言）；未知 goodsId → IAE；null/空列表仍全库（回归）。
  - `CarModelControllerOpsJobTest`：`{goodsIds:[...]}` → 200 `{jobId}`；未知 id → 400；无 body → 200（全库）。
- [ ] A4 验证：`mvn -q -DskipTests compile` → `mvn test` 全绿（1337+ 新增）。⚠ 回滚点：A1–A3 为可独立 revert 的后端改动。

## 阶段 B：前端交互补齐

- [ ] B1 `utils/carDisplay.js`：新增 `rowStatusKey(row)`（EMPTY > REVIEW > PARTIAL > syncStatus）与 `rowStatusMeta(row)`（键 → label/type，含 数据待复核/部分同步/官网无数据）；`statusMeta` 保留。
- [ ] B2 `CarKnowledgePanel.vue`：
  - 状态下拉补 `REVIEW`（数据待复核）/`PARTIAL`（部分同步）；`filtered` 改按 `rowStatusKey` 判定（`NONE` 单独判定）；`countByKey` 替换 `countBy`；`rowStatusMeta` 改用 utils 版。
  - 统计条数据驱动（8 项：全部/已同步/失败/数据待复核/部分同步/官网无数据/同步中/封面覆盖），`<button type="button" class="stat">` + active + aria-pressed，点击切筛选（all 清状态、cover→NONE）。
  - URL 持久化（design §3.3）：挂载读取 `kw/net/status/sort`（白名单兜底）、写回 `router.replace`（keyword 防抖 300ms、写前比对、外部回流短路）、清除筛选同步清 query、卸载清 timer。
  - 「全选筛选结果」checkbox（EDITOR+，design §3.4）：all/indeterminate 状态、勾选并集、取消清空。
  - 批量栏 sticky CSS（design §3.5，不透明背景 + z-index）。
- [ ] B3 `api/index.js` + `CarDetail.vue`：`createRebuildJob(goodsIds)` 支持定向 body；详情页 ADMIN 新增「重建向量」按钮（任务化 + 确认文案 + 无 goodsId 拦截，design §3.6）；`sourceStatusMeta` 改用 utils `rowStatusMeta`。
- [ ] B4 冒烟更新：`frontend/tests/smoke/car-panel.spec.js` 统计条下标改 8 项；新增 1 例覆盖 URL 持久化 + 「数据待复核/部分同步」筛选（复用现有 route mock 模式，追加一行 `sourceState:'INCOMPLETE'` 或 `syncStatus:'PARTIAL'` 行）。
- [ ] B5 验证：`npm run build` exit 0；`npm run test:smoke`（car-panel 与既有 33 例）全绿。⚠ 回滚点：B1–B4 为可独立 revert 的前端改动。

## 阶段 C：spec 同步 + 收尾

- [ ] C1 `docs/spec/knowledge/car.md`：
  - §4.4：`VECTOR_REBUILD` 条目补「可选 body `{goodsIds}` 定向单台/少量重建（不传=全库）」；
  - §5：`rebuild-jobs` 行补 body 契约（含未知 id 400、无 body 回归）；
  - §6：`CarKnowledgePanel` 补「状态筛选/统计 8 项同源、URL query 持久化（kw/net/status/sort）、全选筛选结果、批量栏 sticky」；`CarDetail` 补 ADMIN「重建向量」任务化入口。
- [ ] C2 `docs/spec/knowledge/center.md` §4.1：补「状态口径同源」「筛选 URL 契约」「全选筛选结果」「批量栏 sticky」「详情页单车型重建入口」条目（可合并为 2–3 条）。
- [ ] C3 全量复查（trellis-check）：`mvn test` + `npm run build` + `npm run test:smoke` 三绿；AC1–AC7 逐条勾验；spec 与实现一致性；grep 确认无重复状态判定残留。
- [ ] C4 提交（中文 commit，`feat(knowledge-car): ...`），含任务工件。

## 验证命令备忘

```bash
mvn -q -DskipTests compile && mvn test          # 仓库根目录
npm run build && npm run test:smoke             # frontend/
```

## 回滚点

- A（后端）与 B（前端）各自独立提交、可分别 revert；C 文档随 B 一并提交。
- 无 DB 迁移、无配置/.env 变更。
