# 修复并行子代理研究进度的实时回写

## Goal

让深度研究进度页真实反映每个子代理的完成时刻：任意子代理完成即写回其状态，不再出现「只有第一个问题耗时、其余 PENDING 一闪而过」的假象，用户在轮询期间即可看到逐 agent 独立推进。

## User Outcomes

- 进度页各 agent 卡片的 `RUNNING→DONE/FALLBACK/FAILED` 按各自真实完成时刻独立变化，而非在慢 agent 完成后集中跳变。
- 轮询期间能看到多个 agent 同时处于 `RUNNING`，而不是「1 个 RUNNING + 其余 PENDING」。
- 修复不改变搜索/汇总/自动简报的产物与顺序保证。

## Background

- 深度研究在 `DeepResearchService.doRunAsync` 中并行提交子代理：`src/main/java/com/sparkora/deep/service/DeepResearchService.java:211-220`（虚拟线程池一次性 `submit` 全部问题）。
- 但结果回写是**串行收集循环**：`DeepResearchService.java:223-248`——对每个 i 先 `updateAgent(i, RUNNING)`（`:231`），再 `futures.get(i).get(timeout)` 阻塞（`:233`）；只有轮到 i 时才写 RUNNING，且要被前一个 future 阻塞。
- 后果：agent2~N 的任务其实早已并行启动/完成，却在 `RUNNING` 之前一直显示 `PENDING`；当慢的 `future[0]` 返回后，它们在同一轮 2s 轮询窗口内从 `PENDING` 直接 `DONE`（`PENDING→DONE` 瞬变）。
- 实测证据（brief 64，project 50）：4 个 Tavily 搜索时间戳 `22:43:35 / :37.9 / :38.2 / :38.5`（延迟 3.6s/6.6s/6.9s/7.2s），均在启动后 7s 内完成；研究 `22:43:31` 启动、`22:44:04` 完成，而收集循环串行等待，导致前端观感为「第一个耗时、其余瞬过」。搜索执行本身无缺陷。
- 前端 `frontend/src/views/project/deep/ResearchProgress.vue:148` 每 2s 轮询 `/deep/status`；`:130-137` 直接采用后端 `agents[].status`（`PENDING/RUNNING/DONE/FALLBACK/FAILED`），`:63` `FINISHED` 集合驱动进度条与 `done` 事件。
- 后端 `/deep/status` 原样返回 `research_notes`：`src/main/java/com/sparkora/web/controller/DeepController.java:217`。
- `updateAgent` 为「读改写整段 JSON」：`DeepResearchService.java:275-304`（`selectById` → 改指定 agentId → `updateById`）；当前由单线程串行调用，天然无并发丢失更新。若要「完成即回写」需保证并发写安全。
- 运行互斥已存在：同一 brief 未结束时重复 `/run` 返回 409（`DeepResearchService.java:116-118`），因此同一 brief 同时只有一个研究批次在写 `research_notes`。
- 既有测试：`src/test/java/com/sparkora/deep/service/DeepResearchServiceRunTest.java`（run 前置校验/互斥）、`DeepResearchServiceSnapshotTest.java`（策略快照）。当前**没有** `doRunAsync` 级测试。

## Requirements

- R1 全部子代理在启动阶段即被标记 `RUNNING`（一次批量回写），不得等待各自收集时才置 `RUNNING`；`PENDING→DONE` 瞬变必须消除。
- R2 单个子代理完成（`DONE`/`FALLBACK`/`FAILED`）后应尽快回写其结果，不与其它子代理的完成顺序强绑定；前端轮询应能观察到多 agent 交错完成。
- R3 回写必须并发安全：多个子代理的 `research_notes` 更新不得互相覆盖（不丢 status/facts/webCount/search 字段）。同一 brief 同批次内写入需串行化或原子化。
- R4 超时与取消语义保持：单个子代理超时仍判定为 `FAILED` 并 `cancel(true)`，不产生外部继续调用；超时判定不得因改造而失效。
- R5 产物与顺序保证不变：`research_notes` 最终结构与字段（`agentId/question/status/factsJson/webCount/search`）不变；事实手册 `FactSheetService.merge` 仍在全部 agent 落定后执行；自动简报触发条件与顺序不变。
- R6 失败隔离不变：单子代理失败/超时不影响其它子代理；汇总与自动简报仍按现状继续。
- R7 不改 `/deep/status`、`/deep/run` 的请求/响应契约；`agents[].status` 值域不变。
- R8 不改外部搜索策略、provider 顺序与配额。
- R9 同步维护 `docs/spec/brief-generation.md`（研究阶段逐 agent 回写语义）与 `.trellis/spec/backend/error-handling.md`（若涉及异步回写/并发防护约定）。

## Acceptance Criteria

- [ ] AC-01 研究启动后，`research_notes` 中全部 agent 在首个子代理完成前状态即为 `RUNNING`（不再有长期挂 `PENDING` 者）。
- [ ] AC-02 子代理结果按其完成先后回写：模拟「第 2 个先完成、第 1 个后完成」时，第 2 个 agent 的 `DONE` 早于第 1 个落库（以回写顺序/时间断言）。
- [ ] AC-03 并发回写不丢字段：多个 agent 完成回写后，`research_notes` 每个 agent 的 `status/factsJson/webCount/search` 均为各自最终值，无相互覆盖。
- [ ] AC-04 单子代理超时仍写 `FAILED` 并执行 `cancel(true)`；其余 agent 正常 `DONE`。
- [ ] AC-05 失败隔离：一个 agent 抛异常时其余 agent 仍正常完成并落库。
- [ ] AC-06 产物不变：`research_notes` 字段结构与 `/deep/status` 输出契约不变；`fact_sheet` 汇总与自动简报行为不回归。
- [ ] AC-07 既有 `DeepResearchServiceRunTest` / `DeepResearchServiceSnapshotTest` 通过；`mvn test` 全绿。
- [ ] AC-08 `mvn -q -DskipTests compile` 通过；若触及前端则 `npm run build` 通过。
- [ ] AC-09 `docs/spec/brief-generation.md`（及必要的后端规范）与实际逐 agent 回写语义一致。

## Out of Scope

- 不改变搜索 provider 策略、配额、`WebSearchRouter`。
- **前端不改动**：继续使用现有 2s 轮询与 `agents[].status` 渲染；修复后各 agent 状态自然交错变化，「瞬过」假象消失。不新增耗时/进度条等展示功能。
- 不引入消息队列、持久化任务表或跨进程调度；不改为 SSE/WebSocket 推送（继续用现有 2s 轮询）。
- 不改变 `/deep/status`、`/deep/run` 接口契约与状态值域。
- 不处理 `SubAgentRunner` 内部的 LLM 抽取质量（#4）与 query 构造（#5）。

## Resolved Decisions

- 采用「完成即回写」的真实实时语义（详见 design.md 机制选型），而非仅启动时批量置 RUNNING 的最小改动。
- 回写并发安全由后端保证；前端继续 2s 轮询，不引入推送。
- 范围仅后端（用户已确认）；前端不改。
