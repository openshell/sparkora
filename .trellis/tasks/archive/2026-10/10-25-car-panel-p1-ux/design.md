# 技术设计：车型 Tab P1 交互补齐

> 回链：`prd.md`。原则：状态口径单一真源；筛选 URL 双向同步；单车型重建复用既有任务体系（只扩展目标圈定）。

## 1. 现状核对（已读代码）

- `frontend/src/views/knowledge/CarKnowledgePanel.vue`（576 行）：筛选 refs（`keyword/networkFilter/statusFilter/sortKey`），`filtered` computed 内联状态判断；`rowStatusMeta` 局部函数（EMPTY → 复核 → PARTIAL → statusMeta）；统计条 6 项；批量栏 `v-if="selectedIds.length"` 非 sticky；结果行仅有「共 N · 显示 M」。
- `frontend/src/utils/carDisplay.js`：`statusMeta` 只含 PENDING/SYNCING/SUCCESS/FAILED；无统一状态键。
- `frontend/src/views/KnowledgeCenter.vue`：`.kc-tabs :deep(.el-tab-pane) { height:100%; overflow-y:auto; }` —— **tab pane 是滚动容器**，面板内 `position: sticky; top: 0` 即吸附内容区顶部（无需考虑上下文条偏移）。
- `CarDetail.vue`：`onSync`/`onClean` 已任务化；`useCarSyncJob` 提供 beginStart/startPoll/recoverLatest。
- 后端 `CarModelController`：`POST /models/rebuild-jobs`（ADMIN）无 body；`CarSyncJobService.createRebuildJob()` 取全部非空 goodsId（LinkedHashSet 去重、不设上限）。
- `POST /sync/backfill` 已有「可选 body `{goodsIds}` 定向」先例，可直接镜像其校验与错误语义。
- 冒烟 `frontend/tests/smoke/car-panel.spec.js` 断言统计条 `.stat` 的**下标** 0/1/2/3/5 → 新增统计项后必须同步更新。

## 2. 后端设计（单车型重建定向）

### 2.1 CarSyncJobService

```java
@Transactional
public Long createRebuildJob() { return createRebuildJob(null); }   // 旧签名委托，全库行为不变

@Transactional
public Long createRebuildJob(List<String> goodsIds) {
    List<String> targets;
    if (goodsIds != null && !goodsIds.isEmpty()) {
        // 定向：每个 goodsId 必须命中未删车型，未知 → IAE("车型不存在: x") → 400
        List<String> ids = normalizeGoodsIds(goodsIds);   // 去重/上限 100/格式校验沿用
        Long found = modelMapper.selectCount(new QueryWrapper<CarModelEntity>()
                .in("goods_id", ids));                    // @TableLogic 自动过滤已删
        if (found == null || found < ids.size()) {
            for (String gid : ids) {
                if (modelMapper.selectOne(new QueryWrapper<CarModelEntity>().eq("goods_id", gid)) == null)
                    throw new IllegalArgumentException("车型不存在: " + gid);
            }
        }
        targets = ids;
    } else {
        // 全库：维持现状（id 升序 LinkedHashSet、不设上限）
        ...
    }
    (self == null ? this : self).markStaleRunningAsFailed();
    if (hasFreshRunning()) throw new IllegalStateException("已有同步任务运行中,请稍后重试");
    return insertJobAndRun(targets, "VECTOR_REBUILD");
}
```

- 单台入口也走 `normalizeGoodsIds`（上限 100 对单台/少量选择不构成限制；全库分支保持不设上限，避免 250+ 台车型时 400）。
- job_type 不变（`VECTOR_REBUILD`），任务面板/记录无需新标签。

### 2.2 CarModelController

```java
@PostMapping("/models/rebuild-jobs")
@PreAuthorize("hasRole('ADMIN')")
public R<Map<String, Object>> createRebuildJob(
        @RequestBody(required = false) Map<String, List<String>> body) {
    List<String> goodsIds = body == null ? null : body.get("goodsIds");
    // 400/409/500 映射与现有一致
}
```

- 不传 body / `goodsIds` 为空 → 全库（回归零变化）。

### 2.3 测试

- `CarSyncJobOpsAsyncTest`：定向只圈定指定车型；未知 goodsId → IAE；null/空 → 仍全库（沿用现有断言）。
- `CarModelControllerOpsJobTest`：body `{goodsIds:[...]}` → 200 `{jobId}`；未知 id → 400；无 body → 200（全库）。

## 3. 前端设计

### 3.1 `utils/carDisplay.js`：状态单一真源

```js
/** 规范化状态键：EMPTY > REVIEW(INCOMPLETE|STALE) > PARTIAL > syncStatus。 */
export function rowStatusKey(row) {
  if (!row) return 'PENDING'
  if (row.sourceState === 'EMPTY') return 'EMPTY'
  if (row.sourceState === 'INCOMPLETE' || row.sourceState === 'STALE') return 'REVIEW'
  if (row.syncStatus === 'PARTIAL') return 'PARTIAL'
  return row.syncStatus || 'PENDING'
}
/** 键 → 标签（复用 statusMeta，补三型）。 */
export function rowStatusMeta(row) { ... }
```

- `CarKnowledgePanel` 的标签/筛选/计数全部改用 `rowStatusKey`；`CarDetail.vue` 的 `sourceStatusMeta` 同步改用同一函数（消除重复实现）。
- 筛选值集合：`all | SUCCESS | FAILED | SYNCING | PENDING | REVIEW | PARTIAL | EMPTY | NONE`（NONE=无封面，非状态键，单独判断）。
- 计数：`countByKey(key)`；「全部车型」= rows.length；「封面覆盖」= cover count。EMPTY 天然不进 FAILED（键唯一）。

### 3.2 统计条（数据驱动 + 可点击）

```js
const statItems = computed(() => [
  { key: 'all',      label: '全部车型', num: rows.value.length, cls: '' },
  { key: 'SUCCESS',  label: '已同步',   num: countByKey('SUCCESS'), cls: 'ok' },
  { key: 'FAILED',   label: '失败',     num: countByKey('FAILED'),  cls: 'err' },
  { key: 'REVIEW',   label: '数据待复核', num: countByKey('REVIEW'), cls: 'warn' },
  { key: 'PARTIAL',  label: '部分同步',  num: countByKey('PARTIAL'), cls: 'warn' },
  { key: 'EMPTY',    label: '官网无数据', num: countByKey('EMPTY'),  cls: 'warn' },
  { key: 'SYNCING',  label: '同步中',    num: countByKey('SYNCING'), cls: 'warn' },
  { key: 'cover',    label: '封面覆盖',  num: coverCount, sub: `/ ${rows.length}`, cls: '' }
]
```

- 渲染用原生 `<button type="button" class="stat" :class="{ active: ... }">`（默认键盘可达，避免 div+tabindex），点击行为：`all` → `statusFilter='all'`；状态键 → 该键；`cover` → `statusFilter='NONE'`。`aria-pressed` 标注选中。
- 视觉沿用现有 `.stat-num/.stat-label`，`:focus-visible` 用 `--focus-ring`；active 态加边框/底色。

### 3.3 URL 双向同步

- query 键：`kw`（string）、`net`、`status`、`sort`。空值/`all`/默认排序（`recent`）不写入 query，保持 URL 干净。
- 初始化：`const q = route.query`，逐项 `??` 默认值；`status` 非法值回退 `all`（白名单校验），`sort` 同理。
- 写回：`watch([keyword, networkFilter, statusFilter, sortKey])` → keyword 防抖 300ms（防每键 replace 刷历史），其余立即；`router.replace({ query: {...route.query, ...next} })` 前先与当前 query 比较，相同则短路（防自身回流）。
- 外部回流：`watch(() => route.query, applyFromQuery)`，比较后一致短路，避免重复 load/写回死循环（对齐 `.trellis/spec/frontend/index.md` 的「路由 query 双向同步」约定）。
- `clearFilters()` 同时重置 refs 与 `router.replace({ query: 去掉四个键 })`。
- 卸载清防抖 timer。

### 3.4 全选筛选结果

- 结果行左侧（EDITOR+ 且 `filtered.length`）：`el-checkbox`（`:indeterminate` + `:model-value`）。
  - `allFilteredSelected = filtered.length > 0 && filtered.every(r => selectedIds.includes(r.id))`
  - `someSelected = filtered.some(r => selectedIds.includes(r.id))` → indeterminate = someSelected && !allFilteredSelected
  - change(true) → `selectedIds = [...new Set([...selectedIds, ...filtered.map(r => r.id)])]`
  - change(false) → `selectedIds = []`
- 文本「全选筛选结果（N）」；与批量栏计数联动；批量栏仍 `v-if="selectedIds.length"`。

### 3.5 批量栏 sticky

```css
.bulk-bar { position: sticky; top: 0; z-index: 10; background: var(--card); /* 不透明 */ }
```

- 边框/间距不变；因 tab pane 即滚动容器，无需 top 偏移。`el-checkbox`/按钮焦点不裁剪（z-index 高于卡片 hover 阴影即可）。

### 3.6 详情页「重建向量」

- `api/index.js`：`createRebuildJob: (goodsIds) => http.post('/car/models/rebuild-jobs', goodsIds?.length ? { goodsIds } : null, { timeout: 60000 })`。
- `CarDetail.vue`：ADMIN 可见按钮（`v-if="user.isAdmin"`，`:disabled="jobBusy"`）；确认文案「只重建本车型向量（不重新采集/清洗）」→ `createRebuildJob([goodsId])` → `beginStart`/`startPoll(..., { onFinished: load })`；错误映射中文。
- 无 goodsId 时禁用/提示（与同步入口同口径）。

## 4. 兼容与风险

| 风险 | 缓解 |
|---|---|
| 统计条新增项使既有冒烟断言下标错位 | 同步更新 `car-panel.spec.js`（8 项下标 0–7） |
| URL 写回引起 watch 死循环 | 写前比对 + 外部回流短路；防抖 keyword |
| 全选语义歧义（保留非筛选已选） | PRD 明确：勾选=并集，取消=清空；UI 只显示计数 |
| 单车型重建被 100 上限误伤 | 仅定向分支 normalize；全库分支维持无上限 |
| sticky 覆盖卡片操作 | z-index 10 + 不透明背景；批量栏高度小且无 dropdown |

## 5. 回滚

纯代码回滚：后端新增 body 为可选参数、旧调用零影响；前端 revert 即回到组件内筛选/无 sticky。无 DB、无配置变更。
