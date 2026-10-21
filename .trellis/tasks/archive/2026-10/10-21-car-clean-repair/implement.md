# 实施计划：车型参数清洗完整性与存量补清洗

## A. 后端清洗安全

- [x] `CarCleanService.cleanForModel`：params 为空/versions 为空时不 delete、返回 0。
- [x] 校验 `values_json` 与版本数；异常记录日志，不静默。
- [x] `syncOne` 清洗后检查 `CleanStats.total()==0` → PARTIAL + note。
- [x] `backfillInternal` 图片补入库检查 params/cleans；缺失时 PARTIAL，不写完整 SUCCESS/lastSyncAt。

## B. 补清洗接口

- [x] 新增 `CarCleanRepairService`（cleanOne/cleanMissing）；注入 `CarCleanService` 代理。
- [x] 统计 params/cleans，只处理 params>0 && cleans=0；失败汇总不中断。
- [x] Controller：`POST /api/car/models/{id}/clean`、`POST /api/car/models/clean-missing`（ADMIN）。
- [x] 返回 before/after 与失败明细；不触版本/参数/图片/向量。

## C. 前端

- [x] `CarDetail.vue`：`displayRows` raw 回退 + RAW 类型 + 提示。
- [x] 详情页「重新清洗」按钮（ADMIN/EDITOR）。
- [x] `CarKnowledgePanel` 运维「补全清洗参数」+ 二次确认。
- [x] `api/index.js` cleanOne/cleanMissing，超时 300s。
- [x] E2E mock：cleans 空、params 非空 → 表格有行。

## D. 测试

- [x] `CarCleanServiceSafetyTest` / `CarCleanRepairServiceTest`。
- [x] `CarModelServiceSafetyTest` 清洗为空→PARTIAL；图片回填缺清洗→PARTIAL。
- [x] 控制器契约测试。
- [x] `mvn -q -DskipTests compile`、`mvn test`、`npm run build`、Playwright smoke。

## E. 生产修复与验证

- [x] 合并/部署 `fix/car-clean-repair`；保持 `carSyncEnabled=false`。
- [x] `clean-missing` 补 35 台；逐台记录失败。
- [x] 验证 `/car/14`、`/car/52` 等详情页有参数；清洗数 >0；版本/参数/封面未变。
- [x] 对 7 台 + goodsId 163 执行全量同步；11 台 EMPTY 不动。
- [x] 复核向量/参数块：受影响车型按需重建；最终 `vector-stats` 无缺失。
- [x] 规格文档同步：`docs/spec/knowledge/car.md`、`center.md`、`.trellis/spec/backend`。

## 风险

- `cleanForModel` AI 兜底可能在事务内耗时；本轮 35 台补清洗先测单台耗时，必要时后续异步化。
- 补清洗不重建向量会导致参数块仍是旧清洗；验证后对受影响车型重建块/向量。
- 11 台 EMPTY 的旧清洗可能保留；不做全量同步，避免数据丢失。


## 本轮代码/测试结果

- `mvn test`：1309 passed（新增清洗安全/补清洗服务测试，前端 raw 回退 E2E）。
- `npm run build`：通过；Playwright smoke：32 passed。
- 待执行：部署、35 台补清洗、8 台空壳全量同步、受影响车型向量重建。


## 生产修复结果（2026-10-21 17:22–18:05）

- 部署：`fix/car-clean-repair` 合并 `52ea34a`；后端/前端重建 healthy；`carSyncEnabled=false`。
- 单台验证：`/models/14/clean` → 202 params，0→1010 cleans，全部 RULE，约 11s。
- 批量补清洗：首次 AI 兜底版本单模型约 15min 未完成，已中止；改为批量规则优先（不调用 AI，规则未命中 FALLBACK）后重新部署。
- `POST /models/clean-missing`：`total=57, cleaned=34, skipped=23, failed=0`，耗时约 6m34s；加上手动清理的 id14，全部 **47 台有参数车型均有清洗结果**，`params_but_no_cleans=0`。
- 全量同步 8 台空壳（job 11）：成功 4（10055/10053/10030/163，其中 163 大汉EV 由 0 版本补到 3 版本/17 组/258 参数）；4 台官网参数为空（10040/10036/10050/10042）标 `INCOMPLETE`，无法补数据，未伪造成功。
- `/car/14` 生产验证：16 个参数组、202 行表格，无原始回退提示，console error 0。
- 向量重建：57/57 成功，`chunkCount=952, embeddedCount=952, missingCount=0`（补清洗前 412）。
- 剩余空壳 10 台：6 台官网 `EMPTY` 无参数 + 4 台官网参数为空的 `INCOMPLETE`；均无官方数据可补。
