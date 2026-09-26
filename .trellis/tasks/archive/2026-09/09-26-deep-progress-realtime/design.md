# 设计：并行子代理研究进度的实时回写

## 1. 范围与边界

- 只改 `com.sparkora.deep.service.DeepResearchService.doRunAsync`（收集/回写段）与 `updateAgent`（并发安全）；不改 `SubAgentRunner`、`WebSearchRouter`、`FactSheetService`、控制器契约、前端。
- `research_notes` JSON 字段与 `/deep/status` 输出契约不变。
- 汇总（`factSheet.merge`）与自动简报仍在全部 agent 落定后执行，顺序不变。

## 2. 现状缺陷

```text
submit(agent1..agentN)            // :211-220 并行启动
for i in 0..N-1:                  // :223-248 串行收集
    updateAgent(i, RUNNING)       //   到轮到 i 才置 RUNNING
    note = futures[i].get(timeout)//   被前一个 future 阻塞
    updateAgent(i, result)        //   结果在收集时才落库
```

- agent2..N 在轮到前一直 `PENDING`（虽然任务已在跑）。
- 慢的 `future[0]` 阻塞后续回写 → 其余 agent 在慢者完成后集中 `PENDING→DONE`，前端 2s 轮询看到「瞬过」。

## 3. 目标数据流

```text
1) 批量置 RUNNING（一次全量写，早于任何 submit）      → AC-01
2) submit(agent1..agentN)   并行研究（原样）
3) 为每个 agent 提交独立「收集器」任务（虚拟线程，互相独立）:
       try  r = futures[i].get(timeout);  writeAgent(i, r)          // 完成即回写 → AC-02
       catch Timeout  futures[i].cancel(true); writeAgent(i, FAILED) // 超时语义保留 → AC-04
       catch Other    writeAgent(i, FAILED)                          // 失败隔离 → AC-05
4) join 全部收集器（仅用于日志统计与「全部落定」判定）
5) 汇总 fact_sheet + 自动简报（不变）
```

关键点：**收集器彼此独立**，谁的 `future` 先完成谁先回写，天然乱序；不再有 `future[0]` 阻塞后继写入。

## 4. 并发安全（R3）

- `updateAgent` 是「读整段 JSON → 改一个 agentId → 写回」，多收集器并发调用会**丢失更新**。
- 方案：`updateAgent` 对每个 briefId 加锁。用 `ConcurrentHashMap<Long,Object> notesLocks` + `synchronized(lock)`（同一 brief 串行写；不同 brief 不互相阻塞）。
  - 同批次互斥已由 `/run` 的 `runningBriefs` 保证（同 brief 同时只有一个批次），因此 per-brief 锁足够。
  - 锁在批次结束后从 map 移除，避免无界增长。
- 备选（不采用）：全局 `synchronized updateAgent`——正确但会跨 brief 串行；per-brief 锁更精确，代价相同。

## 5. 与既有语义的兼容

| 事项 | 处理 |
|---|---|
| 超时 | 收集器内 `futures.get(timeout)` 保留；超时 `cancel(true)` 后写 `FAILED`（语义同现状） |
| 取消 | 同现状：仅超时/异常时 `cancel(true)` |
| `doneCount`/`webTotal` 日志 | 收集器用 `AtomicInteger` 累加，join 后统一打日志（内容不变） |
| `pool.shutdown()` | 全部收集器 join 后调用 |
| `research_notes` 结构 | `updateAgent` 输出的字段集合不变 |
| 前端 | 不改；`PENDING/RUNNING/DONE/FALLBACK/FAILED` 值域不变，`allDone` 判定不变 |

## 6. 取舍

| 决策 | 选择 | 理由 |
|---|---|---|
| 实时机制 | 每 agent 独立收集器 | 复用现有 `Future.get(timeout)`+`cancel` 语义，改动最小且真乱序 |
| 并发写 | per-brief 锁 | 消除丢失更新；不同 brief 并行不受影响 |
| 是否用 CompletableFuture/推送 | 否 | `orTimeout` 不中断底层任务，取消语义难对齐；推送属超范围 |
| 前端 | 不改 | 状态语义修复后自然生效（用户已确认） |

## 7. 回滚

- 纯 Java 改动，无 schema、无接口契约变更。
- 回滚点：`git revert` 本轮 commit；`research_notes` 历史数据格式不变，旧版本可读写。
- 运行中的批次不追溯；下次 `/run` 生效。

## 8. 风险

- 锁粒度/死锁：单锁、无嵌套，无死锁；锁仅覆盖短写入。
- 收集器线程耗尽：虚拟线程无固定池上限，N≤maxAgents（默认 4），无风险。
- 观测顺序变化：`research_notes` 内条目顺序仍按 agentId 固定（读改写保留原顺序），只改各条 status 的**写入时刻**，不改变数据结构。
- 测试需可控时序：设计上用可控 `SubAgentRunner` 桩（不同 sleep）驱动，避免真实网络。
