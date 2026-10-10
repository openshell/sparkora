# prd.md — 盖世销量源切换规范入口并启用采集

> 父任务：`10-05-self-hosted-sources`。轻量任务（配置对齐 + 启用），无产品代码改动。
> 依赖：`10-10-gasgoo-sales-channel`（C-110 采集已验证）、`10-09-cpca-gasgoo-collection`（信源基座 + V17 种子）。

## Goal

1. 把盖世「车企销量」栏目的 `list_url` 从别名 `auto-news/C-110` 切换到站内规范入口 **`https://auto.gasgoo.com/sales/C-110`**（内容逐条等价，规范/稳定）。
2. **启用采集任务**：开启采集总开关并启用已配置的乘联会、盖世信源，使定时/手动采集在面板可见。

## 背景（实测）

- `sales/C-110` 与 `auto-news/C-110` HTTP 200、各 20 条、集合完全相同、分页相同；`sales/C-110` 有正常 `<title>`（「汽车销量…」）、是站内导航「销量」指向的规范落地页，`auto-news/C-110` 无标题（疑为别名）。
- 当前源配置：源5 乘联会（cron `0 30 3 * * ?`，窗口 8-11，need_crawl4ai=true，2 栏目）、源6 盖世（cron `0 30 3 * * ?`，need_crawl4ai=false，1 栏目「车企销量」）。两源均 `enabled=false`；`.env` 未定义 `SOURCE_COLLECT_ENABLED`（取默认 false）。

## Requirements

- **R1 URL 切换**：将 `sparkora_source_channel` 中盖世「车企销量」栏目 `list_url` 改为 `https://auto.gasgoo.com/sales/C-110`。用**幂等迁移 V20**（`UPDATE ... WHERE list_url = 旧值`，显式关联），不改选择器/表结构。
- **R2 启用采集**：源5/源6 `enabled=true`；`.env` 设 `SOURCE_COLLECT_ENABLED=true`；重建容器使调度注册（`SourceScheduleService.registerAll()` 启动执行）。
- **R3 验证**：迁移后 DB `list_url` 正确且幂等；容器启动后调度注册数>0；手动采集可跑通；面板可见「下次运行时间」与采集状态。
- **R4 文档/零回归**：`docs/spec/knowledge/sources.md`、`db/migration/README.md` 同步；无产品代码改动；`mvn test` 全绿。

## Acceptance Criteria

- [x] **AC-1 URL 已切**：V20 应用后盖世栏目 `list_url = https://auto.gasgoo.com/sales/C-110`；二次执行幂等（0 行变更）。→ 生产库实测 channel 11 = `sales/C-110`；V20 以旧值为条件，重复跑 0 行。
- [x] **AC-2 采集启用**：容器启动日志显示信源调度注册（或 `registeredCount>0`）；`GET /api/sources` 两源 `enabled=true`、`nextRunAt` 非空。→ 启动日志「信源动态调度注册 2 个源」；`GET /api/sources` 两源 enabled=true、`nextRunAt=2026-10-11T03:30:00`。
- [x] **AC-3 可采集**：手动触发盖世「车企销量」采集成功（`SUCCESS`，total>0）；采出的条目均为销量稿。→ job#3 `SUCCESS 20/20`；入库 20 条（18 非 BYD）+198 切块+198 NEWS 向量+49 海报。
- [x] **AC-4 文档同步**：`sources.md`、迁移 README 登记 V20；`.env.example` 不变（总开关仍示例 false）。→ 已同步。
- [x] **AC-5 零回归**：`mvn test` 1117 全绿（无代码改动）；BYD 新闻/生成不变。→ `Tests run: 1117` BUILD SUCCESS。

## Out of Scope

- 分页/历史回填（沿用「持续增量」：只采最新一页）。
- 其他站点；工信部申报；附件解析。

## Notes

- **启用是运行态操作**（`UPDATE sparkora_source SET enabled=true` + `.env`），不作为迁移固化；用户可随时在面板停用。
- 盖世走 HTTP 通道（无 Crawl4AI 配额）；乘联会走 Crawl4AI（同 host ≤2/天），当前处于 8-11 发布窗口内。
- 迁移编号：既有最大 V19 → 本任务 **V20**。
