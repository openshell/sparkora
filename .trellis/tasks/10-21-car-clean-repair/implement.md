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

- [ ] 合并/部署 `fix/car-clean-repair`；保持 `carSyncEnabled=false`。
- [ ] `clean-missing` 补 35 台；逐台记录失败。
- [ ] 验证 `/car/14`、`/car/52` 等详情页有参数；清洗数 >0；版本/参数/封面未变。
- [ ] 对 7 台 + goodsId 163 执行全量同步；11 台 EMPTY 不动。
- [ ] 复核向量/参数块：受影响车型按需重建；最终 `vector-stats` 无缺失。
- [ ] 规格文档同步：`docs/spec/knowledge/car.md`、`center.md`、`.trellis/spec/backend`。

## 风险

- `cleanForModel` AI 兜底可能在事务内耗时；本轮 35 台补清洗先测单台耗时，必要时后续异步化。
- 补清洗不重建向量会导致参数块仍是旧清洗；验证后对受影响车型重建块/向量。
- 11 台 EMPTY 的旧清洗可能保留；不做全量同步，避免数据丢失。


## 本轮代码/测试结果

- `mvn test`：1309 passed（新增清洗安全/补清洗服务测试，前端 raw 回退 E2E）。
- `npm run build`：通过；Playwright smoke：32 passed。
- 待执行：部署、35 台补清洗、8 台空壳全量同步、受影响车型向量重建。
