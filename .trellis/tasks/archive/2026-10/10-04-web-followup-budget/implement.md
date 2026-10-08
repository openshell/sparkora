# implement.md — C: 覆盖驱动多轮补检索 + 调用预算治理

> 前置 1：`10-04-serper-provider`（A）。前置 2：`10-04-web-fanout-merge`（B）。前置未完成不得开工。
> 本任务是外部搜索线的最后一个交付物；`generateFromFactSheet` 只在两轮合并后调用**一次**。

## 实施顺序

1. **`selectFollowupTargets`**：在 `DeepResearchService` 增 static 纯函数（与 `selectResearchWindow` 同构），实现三类规则 a/b/c + 聚类去重 + 截断。
2. **`WebCallBudget`**：新增值对象（per-round / per-brief / maxFollowups），挂批次上下文；实现保护性下限 `max(配置值, maxAgents × |计量 primary 组|)`（只计 Tavily/Serper）。
3. **跨轮去重**：`WebResultNormalizer.merge` 增 `seenUrls: Set<String>` 入参；Round 1 收集、Round 2 传入。
4. **批次内缓存**：新增 `WebSearchCache`（TTL，批次作用域），接进 `SubAgentRunner` 的搜索调用点；`cacheHit` 计数进 `SearchMeta`。
5. **Round 2 编排**：`doRunAsync` 在 Round 1 `factSheet.merge` 后：`selectFollowupTargets` → 每目标 1 次 fanout（`webQuery` 拼装，问题换 claim）+ ≤1 次 LLM 增量抽取 → 经 `updateAgent` 增量写回 → 再 merge → 最后 `generateFromFactSheet` 一次。Round 2 用独立 `followupTimeoutMs`。
6. **修 `extract`**：改按快照 order 遍历 + 尊重 `webAllowed`。注意 `extract` 现签名无 snapshot 参数——需传入 order/webAllowed（从快照透传，或改调用方）。
7. **契约与文档**：`SearchMeta` 增量三字段；`.env.example` 新增 4 项；`docs/spec/brief-generation.md` 补两轮流程与预算说明。
8. **测试**（见下）→ `mvn test` 全绿 + `npm run build` 通过后交 check。

## 实现期第一件核对事项

- **[核对] 不重建子代理**：确认 Round 2 不 `new` 子代理、不重跑 plan，`research_plan` 与 Round 1 notes 不被覆盖（AC-C2）。
- **[核对] 预算只计计量源**：确认 SearXNG 调用不计入 `WebCallBudget`；`per-round` 生效值 `= max(12, 6×|计量 primary|)=12`（AC-C3）。
- **[核对] `generateFromFactSheet` 仅一次**：确认在两轮 merge 之后才调用（AC-C8）。
- **[核对] extract 签名**：确认 `extract` 能拿到 order/webAllowed（当前签名不含 snapshot，须补参或由调用方透传）。

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                    # 现有 842 例全绿 + 新增
npm run build
```

## 测试清单

- `SelectFollowupTargetsTest`：三类规则各自命中（构造 fact_sheet 夹具）；聚类去重；`maxFollowups=2` 截断；**无缺口返回空**（不发起任何调用）。
- `WebCallBudgetTest`：三级上限；耗尽标记；耗尽后拒绝新调用；保护性下限 `max(12, 6×|计量 primary|)`。
- `WebResultNormalizerMergeTest`（增）：跨轮 `seenUrls` 去重；`dedupedCount` 正确。
- `WebSearchCacheTest`：批次内相同 `provider+query+vertical+maxResults` 二次命中（`cacheHit`）；批次结束全部释放。
- `WebSearchRouterExtractTest`：按快照 order 遍历（order=`SEARXNG,TAVILY` 优先 SEARXNG）；`webAllowed=false` 不发 extract；默认 order 等价。
- `DeepResearchServiceTest`（增）：Round 2 不新建子代理、不重跑 plan；Round 1 notes/plan 不被覆盖；`generateFromFactSheet` 仅一次；超时/异常降级不阻断。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| 不重建子代理 | Round 2 | 子代理数不变 |
| notes 不被覆盖 | Round 2 写回 | `search` 字段与 Round 1 内容保留 |
| 预算不掐死主流程 | n=6 + primary=2 | Round 1 的 12 次不被拒 |
| 免费源不计预算 | 含 SearXNG | budget 计数不含 SearXNG |
| generate 仅一次 | 两轮合并后 | 调用点唯一 |

## 回滚点

- 运行时：`DEEP_WEB_FOLLOWUP_MAX=0` / 预算回默认 → 回单轮研究。
- 代码：revert 编排/预算/缓存/`extract` 改动；无 DB 迁移。

## 交付物

- `selectFollowupTargets`、`WebCallBudget`、跨轮去重、批次内缓存、Round 2 编排、`extract` 缺陷修复、契约增量、spec 同步、单测全绿。
- 不包含：Serper/垂直（A）、fanout/merge（B）、R4/R6 业务逻辑、跨批次缓存。
