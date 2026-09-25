# 实施计划：并行子代理研究进度的实时回写

## 前置

- 任务：`.trellis/tasks/09-26-deep-progress-realtime`。
- 验证命令：`mvn -q -DskipTests compile`、`mvn test`。前端不改，无需 `npm run build`。

## 有序清单

1. **`DeepResearchService.doRunAsync` 重构（`:198-249`）**
   - 在 submit 前，一次性把全部 N 个 agent 置 `RUNNING`（构造完整 notes 列表写一次 `research_notes`），替代循环内逐个 `updateAgent(i, RUNNING)`。
   - submit 全部子代理（保持 `:211-220` 并行提交逻辑）。
   - 为每个 i 提交独立收集器任务到同一虚拟线程池：
     - `r = futures.get(i).get(props.getResearchTimeoutMs(), MILLISECONDS)` → `writeAgent(i, r.status/factsJson/webCount/search)`。
     - `TimeoutException/Exception` → `futures.get(i).cancel(true)` + `writeAgent(i, FAILED + gaps)`。
     - 计数用 `AtomicInteger doneCount` / `AtomicInteger webTotal`。
   - join 全部收集器（`collector.get()`，无超时，因内部已带超时）→ `pool.shutdown()` → 打既有完成日志。
   - 保留随后的 `factSheet.merge` 与自动简报段（`:250-267`）不变。

2. **`updateAgent` 并发安全（`:275-304`）**
   - 新增 `private final Map<Long,Object> notesLocks = new ConcurrentHashMap<>();`
   - `updateAgent` 内以 `notesLocks.computeIfAbsent(briefId, k -> new Object())` 为锁，`synchronized` 包裹「读-改-写」。
   - 批次结束（doRunAsync finally 或 runAsync finally）移除该 briefId 的锁，避免 map 无限增长。
   - 抽出 `writeAgent(briefId, agentId, status, factsJson, webCount, search)` 供收集器调用（内部复用 `updateAgent` 逻辑）。
   - 保留「读改写不丢 search 字段」的既有行为。

3. **补单测**
   - 新增 `DeepResearchServiceProgressTest`（可控 `SubAgentRunner` 桩，不同 sleep 模拟乱序完成）：
     - AC-01：启动后首个子代理完成前，全部 agent 为 `RUNNING`。
     - AC-02：agent2 先完成（短 sleep）时，其 `DONE` 先于 agent1 落库（记录 `updateById` 调用顺序 / 时序）。
     - AC-03：并发回写后每个 agent 字段完整、无覆盖。
     - AC-04：一个 agent 超时（sleep > timeout）→ `FAILED` + `cancel` 被调用；其余 `DONE`。
     - AC-05：一个 agent 抛异常 → `FAILED`，其余 `DONE`。
     - AC-06：`research_notes` 字段结构不变；`factSheet.merge` 被调用一次（顺序在全部落定后）。
   - 复用 Mockito 桩 `ArticleBriefMapper`（内存维护 research_notes）+ `SubAgentRunner` 桩。
   - 不触碰生产数据（quality-guidelines 红线）。

4. **文档同步**
   - `docs/spec/brief-generation.md:23` 研究阶段说明：补充「全部 agent 启动即置 RUNNING；各 agent 完成即独立回写（乱序）」。
   - `.trellis/spec/backend/error-handling.md`：若适用，补「异步逐 agent 回写的并发防护（per-brief 锁）」条目。

5. **验证**
   - `mvn -q -DskipTests compile` → 通过。
   - `mvn test` → 全绿（含新增 `DeepResearchServiceProgressTest`；既有 `DeepResearchServiceRunTest`/`SnapshotTest` 不回归）。

6. **提交**
   - 单 commit：`fix(deep): 子代理研究进度改为完成即回写并保证并发写安全`。
   - 不提交真实 `.env`；无 schema 变更。

## 风险文件 / 回滚点

- `DeepResearchService.java`：唯一生产改动点；测试覆盖乱序/超时/失败/并发。
- 回滚：`git revert` 单 commit；无契约与数据结构变更。

## 完成前检查

- [ ] 启动阶段批量置 RUNNING 在 submit 之前完成。
- [ ] 收集器互相独立，无 `future[0]` 阻塞后继写。
- [ ] `updateAgent` per-brief 锁生效，且批次结束清理锁。
- [ ] 超时 `cancel(true)` 与 `FAILED` 语义保留。
- [ ] `fact_sheet` 汇总仍在全部落定后执行一次。
- [ ] 未修改前端、`WebSearchRouter`、`SubAgentRunner`、控制器契约。
