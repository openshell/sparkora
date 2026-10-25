# 执行计划：车型运维可观测性与 P2 收尾

> 回链：`prd.md`（R1–R3/AC1–AC9）、`design.md`（§2 后端/§3 前端）。按序执行；⚠ 为回滚点。

## 阶段 A：后端（迁移 + 当前车型 + retry 类型）

- [ ] A1 迁移 `src/main/resources/db/migration/V29__car_sync_job_current_model.sql`：`ALTER TABLE sparkora_car_sync_job ADD COLUMN current_goods_id VARCHAR(64);` + 列注释（design §2.1）。
- [ ] A2 `CarSyncJobEntity`：加 `currentGoodsId`；加 `@TableField(exist = false) String currentModelName`（import 补 `TableField`）。
- [ ] A3 `CarSyncJobService`：
  - 新增 `markCurrent(jobId, goodsId)`（条件更新只写 RUNNING 行的 current_goods_id + heartbeat，异常仅 warn）；
  - `runJob` 循环内「拿到锁后、分支前」调用；锁忙跳过分支不调用；
  - `finish()` 的 UpdateWrapper 增加 `.set("current_goods_id", null)`；
  - `get(id)` 补派生 `currentModelName`（currentGoodsId 非空时按 goods_id 查车型名）；
  - `retry(jobId)` 按 design §2.4 映射源类型（四种保留、其余 RETRY）。
- [ ] A4 测试 `CarSyncJobOpsAsyncTest` 扩展（design §2.5）：retry 保留 VECTOR_REBUILD/CLEAN_ONE（至少两类）+ INCREMENTAL→RETRY 回归；`get` 填充 currentModelName。
- [ ] A5 验证：`mvn -q -DskipTests compile` → `mvn test` 全绿（1343+ 新增）。⚠ 回滚点：A1–A4 可独立 revert（列残留无害）。

## 阶段 B：前端（质量 / 当前车型 / 停滞 / 自动刷新 / 标题）

- [ ] B1 `api/index.js`：新增 `cleanStats(id)`、`vectorStats()` 绑定（design §3.1）。
- [ ] B2 `CarJobPanel.vue`：运行态标题映射（design §3.2）、「正在处理」行、10 分钟心跳停滞警示；保留既有计数/进度/明细/重试逻辑。
- [ ] B3 `CarKnowledgePanel.vue`：工具栏「质量」按钮 + `el-dialog`（向量对账数字 + 缺失 TopN 表，行点击跳详情、失败中文提示/重试）；任务 RUNNING 期间 10s 静默刷新（`watch(jobRunning)` + 单飞 + 卸载清理）。
- [ ] B4 `CarDetail.vue`：清洗质量区块（总数 + RULE/AI/FALLBACK + 兜底占比；空态/失败降级）；`onFinished` 同时刷新详情与清洗质量。
- [ ] B5 冒烟 `frontend/tests/smoke/car-panel.spec.js`：新增当前车型 + 停滞警示用例（mock RUNNING job）、质量弹窗用例（mock vector-stats）；确认既有 4 例不回归。
- [ ] B6 验证：`npm run build` exit 0；`npm run test:smoke` 全绿。⚠ 回滚点：B1–B5 可独立 revert。

## 阶段 C：spec 同步 + 收尾

- [ ] C1 `docs/spec/knowledge/car.md`：
  - §4 任务表字段补 `current_goods_id`（运行中当前车型、终态清空）；
  - §5 `GET /sync/jobs/{id}` 行补返回 `currentGoodsId/currentModelName` 派生字段；
  - §4.4「已知行为」更新 retry 语义（四类型保留、INCREMENTAL 仍 RETRY）；
  - §6 前端：CarKnowledgePanel 质量弹窗 + 运行期自动刷新；CarJobPanel 当前车型/停滞/标题；CarDetail 清洗质量；
  - §8 已知限制按需更新。
- [ ] C2 `docs/spec/knowledge/center.md` §4.1：补「质量可观测」「运行期自动刷新」两条（可并入现有条目）。
- [ ] C3 全量复查（trellis-check）：`mvn test` + `npm run build` + `npm run test:smoke` 三绿；AC1–AC9 逐条勾验；V29 与实体/文档三处一致；定时器清理核查。
- [ ] C4 提交（`feat(knowledge-car): ...`，含任务工件）。

## 验证命令备忘

```bash
mvn -q -DskipTests compile && mvn test          # 仓库根目录
npm run build && npm run test:smoke             # frontend/
```

## 回滚点

- A（后端+迁移）与 B（前端）各自独立提交、可分别 revert；C 文档随 B 一并提交。
- 无配置/.env 变更。
