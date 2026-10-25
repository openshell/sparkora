# 技术设计：车型运维可观测性与 P2 收尾

> 回链：`prd.md`。原则：只加派生/展示与一个小迁移；不改同步/清洗/向量核心逻辑；质量数据复用既有只读接口。

## 1. 现状核对（已读代码）

- `sparkora_car_sync_job`（`CarSyncJobEntity`）：字段 `jobType/status/total/success/failed/skipped/failedItems/startedAt/finishedAt/heartbeatAt/cancelRequested/errorMsg/createdBy`，**无当前车型字段**。
- `CarSyncJobService.runJob`：`for (String goodsId : goodsIds)` 逐车型；锁忙 → skipped；`updateProgress()` 每台写 success/failed/skipped + `heartbeat_at`；`finish()` 终态写入计数/明细/心跳。`get(Long)` 仅 `selectById`。
- `retry(Long jobId)`：`parseFailedGoodsIds` 只取含 `error` 条目 → `createJob(goodsIds, "RETRY")`（固定全量语义）。
- `createJob(List<String>, String jobType)`（:88）：`normalizeGoodsIds` + 陈旧自愈 + `hasFreshRunning` 409 + `insertJobAndRun`——retry 改类型可直接复用，无需新守卫。
- 质量接口已存在且三角色：`GET /api/car/models/vector-stats` → `{modelCount,chunkCount,embeddedCount,missingCount,missingTopN:[{modelId,modelName,missing}]}`；`GET /api/car/models/{id}/clean-stats` → `{modelName,total,byMethod,byValueType,fallbackCount,fallbackPct}`。
- `frontend/src/api/index.js` 的 `carApi` **无** cleanStats/vectorStats 绑定。
- `CarJobPanel.vue`：运行态标题 `INCREMENTAL ? '检查更新中…' : '同步进行中…'`（其余类型共用「同步进行中…」）；进度文案「已处理 X / Y 个车型」；已渲染 `skipped`。
- `useCarSyncJob.js`：2s 轮询；`job` 为完整实体（含 heartbeatAt）；终态 `onFinished` 回调（知识中心传 `load`）。
- 车型 Tab：`CarKnowledgePanel.vue`（P1 后约 673 行）工具栏含「同步记录/刷新/同步车型/运维」；`load()` 会置 `loading=true`（骨架屏），不能直接用于运行期自动刷新。
- 最新迁移 `V28__car_model_source_created_at.sql` → 本任务新增 `V29__car_sync_job_current_model.sql`。

## 2. 后端设计

### 2.1 迁移 + 实体（V29）

```sql
-- V29__car_sync_job_current_model.sql
ALTER TABLE sparkora_car_sync_job ADD COLUMN current_goods_id VARCHAR(64);
COMMENT ON COLUMN sparkora_car_sync_job.current_goods_id IS '运行中任务当前处理的车型 goods_id(10-25 可观测);终态清空';
```

- `CarSyncJobEntity` 增加 `private String currentGoodsId;`；另加派生字段：
  `@TableField(exist = false) private String currentModelName;`（不落库，仅响应展示）。
- 无需回填；历史行 NULL 语义 = 未知/终态。

### 2.2 runJob 逐车型写当前车型

- 在「拿到车型锁、真正开始处理」之后、分支分派之前调用 `markCurrent(jobId, goodsId)`：

```java
private void markCurrent(Long jobId, String goodsId) {
    try {
        jobMapper.update(null, new UpdateWrapper<CarSyncJobEntity>()
                .eq("id", jobId).eq("status", "RUNNING")
                .set("current_goods_id", goodsId)
                .set("heartbeat_at", LocalDateTime.now()));
    } catch (Exception e) {
        log.warn("写当前车型失败(忽略) jobId={} goodsId={}: {}", jobId, goodsId, e.getMessage());
    }
}
```

- 锁忙跳过分支不调用（未真正处理）。
- `finish()` 的 UpdateWrapper 增加 `.set("current_goods_id", null)`（含成功/部分/失败/取消路径与异常 catch 后的 finish）。

### 2.3 get() 补派生名称

```java
public CarSyncJobEntity get(Long id) {
    CarSyncJobEntity job = jobMapper.selectById(id);
    if (job != null && job.getCurrentGoodsId() != null && !job.getCurrentGoodsId().isBlank()) {
        CarModelEntity m = modelMapper.selectOne(
                new QueryWrapper<CarModelEntity>().eq("goods_id", job.getCurrentGoodsId()));
        if (m != null) job.setCurrentModelName(m.getName());
    }
    return job;
}
```

- 仅按需一次单行查询（2s 轮询，成本可忽略）；列表接口不补名称（历史/恢复时靠随后 `get` 轮询补上）。

### 2.4 retry 保留源类型

```java
String srcType = job.getJobType();
String retryType = switch (srcType == null ? "" : srcType) {
    case "VECTOR_REBUILD", "CLEAN_ONE", "IMAGE_BACKFILL", "CLEAN_BACKFILL" -> srcType;
    default -> "RETRY";   // SELECTED/RETRY/INCREMENTAL/SCHEDULED/未知 → 全量同步语义
};
return createJob(goodsIds, retryType);
```

- 四种类型的分派分支均按 `goodsIds` 逐台执行，天然支持「只重跑失败车型」。
- `INCREMENTAL` 刻意不保留：重试应只对失败车型做全量收敛，而不是再走一次增量比对（写入 spec 已知行为）。

### 2.5 测试（`CarSyncJobOpsAsyncTest` 扩展）

- `retry_重建向量任务_保留源类型`：失败明细含 error → 创建的 job `job_type=VECTOR_REBUILD`，目标仅失败 goodsId。
- `retry_单车型清洗_保留源类型`、`retry_图片补入库_保留源类型`（可选一条覆盖 CLEAN_BACKFILL）。
- `retry_增量任务_退化为RETRY全量`（回归锁定）。
- `get_运行中补当前车型名`：job RUNNING + currentGoodsId，model 查询命中 → `currentModelName` 填充；无 current 时为 null。
- 沿用既有 mock 风格（mock mapper / 捕获 UpdateWrapper/插入实体断言）。

## 3. 前端设计

### 3.1 api 绑定

```js
cleanStats: (id) => http.get(`/car/models/${id}/clean-stats`),
vectorStats: () => http.get('/car/models/vector-stats'),
```

### 3.2 CarJobPanel：当前车型 / 停滞 / 标题

- props 不变（`job` 已含新字段）。
- 运行中新增一行：`正在处理：{{ job.currentModelName || currentGoodsId || '—' }}`（无值不渲染该行）。
- 停滞判定（纯计算，随 2s 轮询刷新重算）：

```js
const HEARTBEAT_STALE_MS = 10 * 60 * 1000
const heartbeatStalled = computed(() => {
  if (props.job?.status !== 'RUNNING') return false
  const t = Date.parse(props.job?.heartbeatAt || props.job?.startedAt || '')
  return Number.isFinite(t) && Date.now() - t > HEARTBEAT_STALE_MS
})
```

- 停滞时在进度条下显示警示：`el-alert type="warning"` 或带 warning 色的文本「心跳超过 10 分钟未更新，任务可能停滞；可取消或等待后重试」。
- 运行态标题映射（`jobTitle`）：

```js
const RUNNING_TITLES = {
  INCREMENTAL: '检查更新中…', IMAGE_BACKFILL: '图片补入库中…', CLEAN_BACKFILL: '补清洗中…',
  VECTOR_REBUILD: '重建向量中…', CLEAN_ONE: '清洗中…', SCHEDULED: '定时检查中…'
}
// 默认：'同步进行中…'
```

### 3.3 CarKnowledgePanel：质量弹窗 + 运行期静默刷新

- **质量弹窗**（三角色）：工具栏新增「质量」按钮（`CircleCheck`/`DataAnalysis` 图标），`el-dialog` 内容：
  - 顶部四个数字：车型数 / 块总数 / 已嵌入块数 / 缺失块数（缺失 >0 用 err 色）。
  - 缺失 TopN 表格（车型名、缺失块数；行点击 `router.push('/car/'+modelId)` 并关闭弹窗）。
  - 打开时请求 `carApi.vectorStats()`；失败显示中文错误 + 重试按钮；加载骨架。
- **静默刷新**：

```js
let refreshTimer = null
let refreshing = false
const silentRefresh = async () => {
  if (refreshing) return
  refreshing = true
  try {
    const res = await carApi.list()
    if (res.code === 0 && Array.isArray(res.data)) rows.value = res.data   // 不置 loading、不动 error
  } catch { /* 静默：下次再试 */ }
  finally { refreshing = false }
}
watch(jobRunning, (running) => {
  if (running) {
    if (!refreshTimer) refreshTimer = setInterval(silentRefresh, 10000)
  } else {
    clearInterval(refreshTimer); refreshTimer = null
  }
}, { immediate: true })
onBeforeUnmount(() => { clearInterval(refreshTimer); stopPoll() })
```

- 静默刷新只替换 `rows`，筛选/选择/统计 computed 自动跟随；`selectedIds` 中已不存在的 id 无需主动清（批量操作按 `selectedRows` 解析，缺失行自然忽略）。
- 终态由 `onFinished: load` 正常刷新（保留骨架语义）。

### 3.4 CarDetail：清洗质量区块

- 挂载时请求 `carApi.cleanStats(id)`（与 `load()` 并行），失败静默/内联提示，不影响详情主体。
- 展示位置：参数折叠区之前、版本选择之后（或 sellpoint 之后），一行紧凑卡片：
  - `清洗质量` + 总数 + 三个 chip：`规则 RULE N` / `AI N` / `兜底 FALLBACK N`；`fallbackPct > 0` 时展示「兜底占比 X%」（>20% 用 warning 色）。
  - `total === 0` → 「暂无清洗数据（可先同步或重新清洗）」；REQUESTING 骨架用 `el-skeleton` 小行。
- 任务终态 `onFinished` 的 `load` 后同时刷新 cleanStats（抽 `refreshAll = () => { load(); loadCleanStats() }` 供 onFinished 使用）。

### 3.5 冒烟（`frontend/tests/smoke/car-panel.spec.js`）

- 新增 1 例：mock `GET /api/car/sync/jobs/9`（RUNNING，`currentGoodsId/currentModelName`，`heartbeatAt` 为 30 分钟前）→ 断言面板「正在处理」「重建向量中…」或对应类型标题 + 停滞警示；再 mock `getJob` 终态或直接断言标题映射（可选第二类型）。
- 新增 1 例：mock `GET /api/car/models/vector-stats` → 点击「质量」→ 断言数字与缺失 TopN 行。
- 更新既有用例仅在受影响处（统计条/工具栏按钮新增不改变既有 locator）。

## 4. 兼容与风险

| 风险 | 缓解 |
|---|---|
| 新增列需容器重建执行迁移 | Flyway 自动执行 V29（幂等 DDL），部署时确认 `flyway_schema_history` 出现 V29 |
| `get()` 每 2s 多一次车型查询 | 仅当 `currentGoodsId` 非空；单行主键索引外的 goods_id 查询（现有同步链路同查询模式），可接受 |
| 静默刷新与「刷新」按钮/终态 load 并发 | `refreshing` 单飞；静默分支不置 loading/error；终态回调仍走 load |
| 10 分钟停滞阈值误报 | 阈值远高于单车型正常耗时（数十秒），只在真停滞时提示；工具提示说明可取消 |
| retry 保留 CLEAN_ONE 后，失败多台时任务面板标题仍「清洗中」 | 语义正确（逐台清洗），spec 注明 |
| 质量弹窗对 VIEWER 可见但任务操作仍受限 | 只读接口三角色；无写按钮 |

## 5. 回滚

- 后端：revert 后 V29 列残留但无代码引用（可空列，无破坏；后续可单独写 DROP 迁移）。
- 前端：revert 回到无质量弹窗/无自动刷新；retry 回到全量语义。
- 无配置/.env 变更。
