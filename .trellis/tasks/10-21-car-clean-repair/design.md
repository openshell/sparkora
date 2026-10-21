# 技术设计：车型参数清洗完整性与存量补清洗

## 1. 数据与现状

- `car_param`：原始参数，`values_json` 为全版本值数组，`param_value` 为第一个版本值。
- `car_param_clean`：清洗结果，按 `model_id + version_id + param_id`；详情页按第一个版本查询。
- 当前 35 台有 `car_param` 但 `car_param_clean=0`；`CarChunkService` 因此跳过参数分组块。
- 详情页 `CarDetail.vue` 直接使用 `g.cleans`，无 raw 回退。

## 2. 后端

### 2.1 `CarCleanService.cleanForModel` 安全护栏

- 先读取 versions/params；若 params 为空，直接返回 `CleanStats(0)`，**不执行 delete**。
- 若 versions 为空但 params 非空：仍可按 `version_id=null` 清洗全局值？为保持详情页可查，必须有版本才能查 cleans；对无版本车型跳过并返回 0，避免无效数据。
- 保留当前“先删后插”的幂等语义；调用方保证参数非空。
- 可选：两阶段（事务外计算 + 短事务替换）留到 P1；本轮先加护栏与覆盖检查。

### 2.2 补清洗服务/接口

新增 `CarCleanRepairService`（或 `CarModelService` 方法）：

```java
CleanRepairSummary cleanOne(Long modelId);
CleanRepairSummary cleanMissing();
```

- `cleanOne`：校验车型存在；统计 params/cleans；若 params=0 则跳过；调用注入的 `CarCleanService.cleanForModel`（代理，事务生效）；返回 before/after counts。
- `cleanMissing`：遍历全部未删除车型，筛选 `paramCount>0 && cleanCount==0`，逐台调用；失败收集 `{modelId,name,error}`，不中断其余车型。
- 不重新请求官网；不改版本/参数/图片/向量。

接口（ADMIN）：

- `POST /api/car/models/{id}/clean` → `{modelId, params, cleansBefore, cleansAfter, stats}`
- `POST /api/car/models/clean-missing` → `{total, cleaned, skipped, failed, failures[]}`

### 2.3 同步成功口径

- `syncOne` 在 `cleanService.cleanForModel` 后检查 `stats.total()`：
  - `0` → note「清洗结果为空」；最终 `PARTIAL`（即使图片/向量成功）。
  - 正常 → 保持现有 `SUCCESS`/`PARTIAL` 逻辑。
- `backfillInternal` 图片补入库不再单独把“无参数/无清洗”行标成完整 SUCCESS：检查该车型 params/cleans；若缺失，写 `PARTIAL` + note「仅图片已补，参数/清洗未同步」，保留旧 `last_sync_at`。
- 这样可防止旧 job 9 的误标再次发生。

## 3. 前端

### 3.1 详情页 raw 回退

`CarDetail.vue`：

- 新增 `displayRows(g)`：`g.cleans.length ? g.cleans : g.params.map(toRawRow)`。
- `toRawRow(p)`：`paramKey = p.paramName`、`paramValue = p.paramValue`、`valueType = 'RAW'`、`raw = true`；如 `valuesJson` 可解析且首值空，回退首个非空值。
- 表格 `:data="displayRows(g)"`；类型列对 RAW 显示「原始」，并在分组标题/页面顶部提示「当前为原始参数，尚未清洗」。
- `cleanValue` 对 RAW 直接展示 `paramValue`（或 `valuesJson` 解析值），不拼单位。
- 清洗缺失不再空白；无 params 时才空态。

### 3.2 运维入口

- `CarKnowledgePanel` 运维下拉增加「补全清洗参数」；二次确认文案说明“只基于库内参数重跑清洗，不重新同步官网”。
- 详情页在 raw 回退时显示「重新清洗」按钮（ADMIN/EDITOR），调用单车型接口后 reload。
- `api/index.js` 增加 `cleanOne(id)` / `cleanMissing()`，超时 300s（补清洗可能调用 AI 兜底）；返回后刷新。

## 4. 测试

后端：

- `CarCleanServiceSafetyTest`：参数为空不删除旧 cleans；正常参数生成 cleans；版本为空跳过。
- `CarCleanRepairServiceTest`：只处理 params>0/cleans=0；失败不阻断；幂等；不触版本/参数/图片/向量。
- `CarModelServiceSafetyTest`：`CleanStats.total=0` → PARTIAL；图片回填缺清洗 → PARTIAL 且不前进 lastSyncAt。
- 控制器契约测试：ADMIN 接口、404/400/409 语义。

前端：

- `CarDetail` raw 回退冒烟：mock detail `cleans=[]`、`params` 非空，断言表格行显示、RAW 标签。
- `CarKnowledgePanel` 运维入口存在（可选）。

## 5. 数据修复与灰度

1. 部署代码（保持 `carSyncEnabled=false`）。
2. 调 `POST /api/car/models/clean-missing`；预期 35 台，逐台记录。
3. 验证 `/car/14`、`/car/52` 等原空白页；清洗数 >0、详情表格有值。
4. 对 8 台空壳（7 台 + goodsId 163）调现有 `/api/car/sync/jobs` 全量同步；11 台 EMPTY 不调。
5. 复核版本/参数/封面/向量未被补清洗改变；向量重建只在这些车型的清洗变化后按需执行（参数块依赖清洗，可在补清洗后对受影响车型重建向量）。
6. 恢复自动同步仍保持关闭，观察后再开。

## 6. 回滚

- 补清洗只写派生表 `car_param_clean`；回滚代码不回滚数据；如需恢复旧清洗，按 model 备份/重跑。
- 新接口可保留；迁移无新表/列。
