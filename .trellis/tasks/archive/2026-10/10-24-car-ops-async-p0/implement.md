# 执行计划：车型运维 P0

> 回链：`prd.md`（需求/AC）、`design.md`（技术设计）。按序执行；每步含验证命令；⚠ 处为回滚点（git 语义：后端/前端/文档各一个自然提交粒度，最终一并 commit）。

## 阶段 A：后端任务体系扩展（先行——前端契约依赖它）

- [ ] A1 `CarSyncJobService`：新增 `createRebuildJob()` / `createCleanOneJob(String)` / 重载 `createBackfillJob(boolean, List<String> goodsIds)`（旧签名委托）；`runJob` 分派新增 `VECTOR_REBUILD` / `CLEAN_ONE` 两分支（语义见 design §2.1：向量失败→error 明细可重试；无切块→skipped+note；清洗 0 条→skipped+note）。类头 javadoc 的 job_type 清单同步补两行。
  - 注意：两个新分支**不调** `markSyncFailed`（向量/清洗域不写车型同步状态）；`normalizeGoodsIds` 沿用做单台校验。
- [ ] A2 `CarModelService`：新增 `rebuildOneByGoodsId(String goodsId)`（goods_id 解析 → `docService.rebuildForModel`；车型不存在抛 IAE）；`rebuildAll()` 不动。
- [ ] A3 `CarModelController`：新增 `POST /models/rebuild-jobs`（ADMIN）、`POST /models/{id}/clean-jobs`（ADMIN/EDITOR）；`POST /sync/backfill` 加可选 body `{goodsIds}`（design §2.3；不传 body 行为不变）。错误映射 400/409/500 对齐 `createCleanJob` 既有写法。
- [ ] A4 后端单测：新建 `CarSyncJobOpsAsyncTest`（或并入既有 `CarSyncJobServiceConcurrencyTest` 风格）覆盖：
  - VECTOR_REBUILD：failed>0 → failed 明细含 error 且 `parseFailedGoodsIds` 可重试；total==0 → skipped+note；正常 → success。
  - CLEAN_ONE：cleansAfter>0 → success+note；0 → skipped+note；目标车型不存在 → IAE。
  - createBackfillJob 定向：指定 goodsIds 只圈定目标（含未知 id → IAE「车型不存在」）；null/空列表走旧过滤分支（回归）。
- [ ] A5 验证：`mvn -q -DskipTests compile` 零错误 → `mvn test` 全绿（510+ 用例含新增）。⚠ 回滚点：A1–A4 为一个可独立 revert 的后端提交。

## 阶段 B：前端入口迁移

- [ ] B1 `api/index.js`：`carApi` 新增 `createRebuildJob`/`createCleanOneJob`，改 `createBackfillJob(full, goodsIds)`（design §3.1）；**删除** `syncOne`/`cleanOne`/`rebuildAll`/`cleanMissing` 死绑定。
- [ ] B2 `useCarSyncJob.js` `toastFinished` + `CarJobPanel.vue`/`CarJobHistoryDrawer.vue` 的 jobType/type 标签：加 `VECTOR_REBUILD`/`CLEAN_ONE`（design §3.2/3.3）。
- [ ] B3 `CarKnowledgePanel.vue`：「重建全部向量」迁移异步任务（删 `rebuildingAll` 阻塞逻辑）；批量栏加「补选中图片」；EMPTY 拦截提示与卡片 tooltip 文案闭环（design §3.4）。
- [ ] B4 `CarDetail.vue`：接入 `useCarSyncJob` + `CarJobPanel`；「同步」→ `createJob([goodsId])`、「重新清洗」→ `createCleanOneJob(id)`；挂载 `recoverLatest`；删阻塞式 `onSync`/`onClean` 旧逻辑（design §3.5）。
- [ ] B5 验证：`npm run build` exit 0；grep 确认 `syncOne|cleanOne|rebuildAll|cleanMissing` 在 `frontend/src` 零引用。⚠ 回滚点：B1–B4 为一个可独立 revert 的前端提交。

## 阶段 C：spec 同步 + 收尾

- [ ] C1 `docs/spec/knowledge/car.md`：
  - §4 头部 javadoc 口径的 job_type 清单 + §4 任务段补 `VECTOR_REBUILD`/`CLEAN_ONE`（allowAi 语义差异：CLEAN_ONE=true / CLEAN_BACKFILL=false；向量失败不写车型 syncStatus）；
  - §5 接口表新增 `POST /models/rebuild-jobs`、`POST /models/{id}/clean-jobs` 两行 + `backfill` 行补 body `{goodsIds}` 定向语义；`rebuild-all`/`{id}/sync`/`{id}/clean` 三行标注「保留兜底，前端已迁移任务体系」；
  - §8 已知限制删除/更新「异步化待后续」条目。
- [ ] C2 `docs/spec/knowledge/center.md` §4.1：运维下拉「重建全部向量」改述异步任务；批量栏「补选中图片」契约；EMPTY 引导闭环；详情页同步/清洗任务化 + 内嵌任务面板契约。
- [ ] C3 全量复查（trellis-check 口径）：`mvn test` + `npm run build` 双绿；对照 AC1–AC7 逐条勾验；grep 确认旧绑定零残留。
- [ ] C4 提交（中文 commit，scope 参照项目风格，如 `feat(knowledge-car): 车型运维P0——重建向量/详情页同步清洗任务化+定向backfill`）。

## 验证命令备忘

```bash
mvn -q -DskipTests compile        # 阶段 A 每步后
mvn test                          # A5 / C3
npm run build                     # B5 / C3（frontend/ 目录）
```

## 回滚点

- A（后端）与 B（前端）分别独立提交，可分别 revert；C 文档随 B 一并提交。
- 无 DB 迁移、无配置变更、无 .env 新增项。