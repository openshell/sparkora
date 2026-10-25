# 车型运维可观测性与 P2 收尾

> 来源：10-24 知识中心车型页评审 P2 项 + 10-24/10-25 两轮任务遗留修正。P3（批量删除列车型名、/car/sync 收敛）与其余交互项（参数搜索、RAG 命名、图标区分、相对时间、空库文案、卡片键盘可达）不在本任务范围。

## Goal

让运维过程「看得见」：任务运行中能知道当前处理哪台车型、卡住时能察觉、列表随任务自动刷新；质量数据（清洗质量/向量对账）从接口变成界面；同时修正 retry 的重试语义与任务面板标题。

## Requirements

### R1 质量可观测（clean-stats / vector-stats 前端接入）
- 现状：`GET /models/vector-stats` 与 `GET /models/{id}/clean-stats` 后端已存在且三角色可用，但前端零调用，质量只能靠手工 SQL 体检。
- 要求：
  - 车型 Tab 工具栏新增「质量」入口（三角色可见），弹窗展示全库向量对账：车型数/块数/有向量块数/缺失块数与缺失 TopN（车型名 + 缺失块数，可跳详情）。
  - 详情页新增「清洗质量」区块：清洗总条数、`RULE/AI/FALLBACK` 分布与兜底占比；无清洗数据时给出明确提示；接口失败不阻塞详情主体。

### R2 任务可观测（当前车型 / 心跳停滞 / 列表自动刷新）
- 现状：任务面板只有计数与进度条，不知道当前在处理哪台；心跳停滞无任何提示；列表在任务运行期间不刷新，必须手动点「刷新」。
- 要求：
  - 任务进度接口返回运行中任务的**当前处理车型**（goodsId + 车型名），任务面板展示「正在处理：<车型名>」；任务终态后清空该字段。
  - RUNNING 且心跳超过 10 分钟未更新时，任务面板显示停滞警示（说明可能卡住及可取消/重试）。
  - 车型列表在任务 RUNNING 期间每 10 秒**静默刷新**（不出现骨架屏、不清空当前数据、失败不打断），终态与卸载时停止。

### R3 遗留修正
- **retry 保留源 job_type**：对 `VECTOR_REBUILD`/`CLEAN_ONE`/`IMAGE_BACKFILL`/`CLEAN_BACKFILL` 的失败条目重试时，按原 job_type 重跑（只处理失败车型），不再退化为全量同步；其余类型（`SELECTED`/`RETRY`/`INCREMENTAL`/`SCHEDULED`/未知）保持现有 `RETRY` 全量语义。
- **任务面板运行态标题按类型区分**：同步进行中 / 图片补入库中 / 检查更新中（INCREMENTAL）/ 补清洗中 / 重建向量中 / 清洗中 / 定时检查中。

## Constraints

- 新增 1 个 Flyway 迁移（`current_goods_id` 列，可空、无回填、无破坏），三处同步（迁移脚本 + entity + spec）。
- 不改指纹/同步/清洗/向量核心逻辑；不新增接口（仅派生字段与前端消费既有接口）。
- 权限沿用：质量数据三角色只读；任务操作仍 ADMIN/EDITOR（VIEWER 只读面板）。
- PC-only；控件走现有密度/焦点规范；轮询/定时器必须 `onBeforeUnmount` 清理。
- spec 同步：`docs/spec/knowledge/car.md`（字段/接口派生/retry 语义/前端），必要时 `center.md` §4.1。

## Acceptance Criteria

- [ ] AC1 车型 Tab「质量」弹窗展示向量对账真实数据（含缺失 TopN 可跳详情）；加载失败有中文提示；VIEWER 可用。
- [ ] AC2 详情页展示清洗质量（总数 + RULE/AI/FALLBACK + 兜底占比）；无数据/失败降级不阻塞详情。
- [ ] AC3 `GET /sync/jobs/{id}` 在 RUNNING 时返回 `currentGoodsId` 与派生 `currentModelName`；任务面板显示「正在处理」；终态后字段为空。
- [ ] AC4 RUNNING 且心跳 >10 分钟未更新时面板显示停滞警示。
- [ ] AC5 列表在任务 RUNNING 时每 10s 静默刷新（无骨架闪烁、不清空/不覆盖已有错误态），终态/卸载停止。
- [ ] AC6 retry 对四种运维任务类型保留源 job_type 且只重跑失败车型；INCREMENTAL 等仍创建 RETRY 全量任务（回归用例锁定）。
- [ ] AC7 面板运行态标题按 job_type 区分。
- [ ] AC8 `mvn test` 全绿（含新增用例）；`npm run build` exit 0；Playwright 冒烟全绿（含新增当前车型/停滞/质量弹窗断言）。
- [ ] AC9 `docs/spec/knowledge/car.md` 更新（V29 字段、jobs 响应派生字段、retry 类型映射、前端可观测条目）。

## Notes

- 评审来源：10-24 知识中心车型页审查 P2（clean-stats/vector-stats 零调用、任务面板无当前车型、心跳卡住无提示、RUNNING 列表不自动刷新）；遗留修正来自 `10-24-car-ops-async-p0` §4.4 已知行为与 `10-25-car-panel-p1-ux` 复查记录。
- 停滞阈值选 10 分钟：逐车型处理（含图片下载/向量重建）实测可能数十秒，10 分钟可避免误报；与后端 60 分钟陈旧自愈口径区分（后者是清理阈值，不是提示阈值）。
