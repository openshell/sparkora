# design.md — C: 覆盖驱动多轮补检索 + 调用预算治理

> 父任务：`../10-04-brief-retrieval-sources`。机制论证见父 `design.md` §3（机制二：为何确定性驱动）、§4（机制三：预算/去重/缓存）、§5.4（`extract` 缺陷修法）。
> 前置：`../10-04-serper-provider`（A）、`../10-04-web-fanout-merge`（B）。C 是外部搜索线的**最后一个**交付物。

## 1. 边界

| 层 | 文件 | 改动 |
|---|---|---|
| 缺口识别 | `deep/service/DeepResearchService.java` | 新增 static `selectFollowupTargets(factSheet, maxFollowups)`（纯函数） |
| 编排 | `deep/service/DeepResearchService.java` | `doRunAsync` 在 Round 1 merge 后加 Round 2（复用 notes 增量写回） |
| 预算 | `deep/service/WebCallBudget.java`（新增） | 三级预算值对象，挂批次上下文 |
| 去重 | `deep/search/WebResultNormalizer.java` | `merge` 增批次级 `seenUrls: Set<String>` 入参（跨轮去重） |
| 缓存 | `deep/search/WebSearchCache.java`（新增） | 进程内 `ConcurrentHashMap`，TTL，批次作用域 |
| 修复 | `deep/search/WebSearchRouter.java` | `extract` 改按快照 order + 尊重 `webAllowed` |
| 契约 | `SubAgentRunner.SearchMeta` | 增量 `dedupedCount` / `cacheHit` / `budgetExhausted` |
| 文档 | `docs/spec/brief-generation.md`、`.env.example` | 两轮流程与预算字段 |

**不做**：Serper/垂直（A）、fanout/merge（B，只复用）、R4/R6 业务逻辑、跨批次持久缓存、LLM 自主多轮、query 语义改写。

## 2. `selectFollowupTargets`（纯函数）

与 `selectResearchWindow`（`DeepResearchService:180-203`）**同构**：`static`、包级可见、无副作用、可单测。输入全部来自 `fact_sheet`（已落库 `entries`/`gaps`/`warnings`），无新增 LLM 决策。三类候选（满足任一即入选）：
- a) `gaps` 中 reason 属检索类（sourceId 未命中 / URL 不匹配 / provider 不匹配）；
- b) `entries` 中 `confidence <= 0.4` 且 `kind == "param"`（参数型事实最需交叉验证）；
- c) plan 的 `keyQuestion` 在 Round 1 产物中**既无 entry 也无 gap**（彻底没被回答）。

按「claim 关键词归属 + 背景/参数类型」聚类去重 → 截断到 `maxFollowups`。

**为何零 LLM**：调用量/延迟/成本可预测、可单测，符合「有规划、有质量」；放弃 LLM 自主多轮的理由见父 §3.1。

## 3. Round 2 两阶段数据流

```
Round 1（现状不改）
  selectResearchWindow → n 子代理并行 → 每 Note 增量落库 → factSheet.merge(notes)
Gap 分析（纯函数）
  selectFollowupTargets(factSheet, maxFollowups=2) → 目标集合
Round 2
  每目标 → 1 次 PRIMARY_FANOUT（复用 B；query 由 webQuery 确定性拼装，问题替换为目标 claim）
    → 命中先过跨轮 seenUrls 去重（§4.2）
    → ≤1 次 LLM 增量抽取 → 追加进对应 Note 的 factsJson；search 字段追加 attempts
再次 factSheet.merge(notes)
briefService.generateFromFactSheet(...)   # 只在最后调用一次
```

**约束**：Round 2 **不新建子代理、不重跑 plan**。复用 `DeepResearchService.updateAgent`（`:390-421`，已显式保留既有 `search` 字段防丢更新）。Round 2 用独立可配 `followupTimeoutMs`（默认 30s）；任何超时/异常 → warning + 跳过该目标，**降级不阻断**（沿用 `rag_status` 四态语义）。

## 4. 预算 / 去重 / 缓存

### 4.1 三级预算（`WebCallBudget`）

| 级别 | 默认 | 作用 |
|---|---|---|
| per-provider | `maxResults`（不变） | 单次调用请求条数上限；实际返回数另记 |
| per-round | `webCallBudgetPerRound` = **12** | Round 1 阶段**计量源**调用次数封顶 |
| per-brief | `maxTotalWebCalls` = **20** | Round 1 + Round 2 共享 |
| `maxFollowups` | **2** | Round 2 目标数 |

- **只计计量源（Tavily/Serper）；免费 SearXNG 不计入**。这样 primary 含 SearXNG 后计量源仍 2 个，`6×2=12` 推导成立（父 §4.1）。
- **保护性下限**：`webCallBudgetPerRound` 生效值取 `max(配置值, maxAgents × |计量 primary 组|)`，避免调小 `DEEP_MAX_AGENTS` 或增删 provider 后预算掐死主流程（AC-C3）。
- 超限：立即停止发起新调用，记 `budgetExhausted=true`，已获证据照常入册（不报错中断）。

### 4.2 跨轮去重（比缓存更重要）

按 `normalizeUrl` 在**跨轮次**范围去重；Round 2 命中 Round 1 已注入 URL → 直接丢弃。落点：`WebResultNormalizer.merge` 增批次级 `seenUrls: Set<String>` 入参。收益：不重复注入 LLM（省 token）、不让同源重复计为多来源（防虚高 `sourceCount`）、不污染聚类。

### 4.3 批次内缓存

- 进程内 `ConcurrentHashMap`，key = `provider | normalizeUrl(query) | vertical | maxResults`，TTL 默认 10min。
- **作用域严格限定单次 research 批次**（挂快照/批次上下文，随批次释放）→ 不跨用户、不跨请求。
- **不做跨批次持久缓存**：搜索结果时效性强，跨批次命中会返回陈旧证据、反噬质量目标；且需加表 + 迁移 + 失效策略，复杂度收益不匹配。
- **必须有** `cacheHit` 计数进 `SearchMeta`，否则账单异常无法归因。

## 5. `extract` 既有缺陷修复（C-R7）

`WebSearchRouter.extract`（`:135-149`）现按 `WebProvider.values()` **枚举声明序**遍历且**不受 `webAllowed` 约束**（WEB 全局关闭时仍会发起付费 extract）。改为按快照 order 遍历 + 尊重 `webAllowed`。默认 order（`TAVILY,SEARXNG`）下行为等价，属纯 bug 修复，但触及付费路径**必须配单测**。

## 6. 契约与配置

- `SearchMeta` 增量 `dedupedCount` / `cacheHit` / `budgetExhausted`（不得改动 B 的 `providers`/`attempts` 语义）。
- `.env.example` 新增 `DEEP_WEB_FOLLOWUP_MAX` / `DEEP_WEB_CALL_BUDGET` / `DEEP_WEB_CALL_BUDGET_PER_ROUND` / `DEEP_FOLLOWUP_TIMEOUT_MS`。
- 运行时 `sparkora_setting` **不放开** followup/budget（成本风险）。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 预算设低 → 覆盖度反降 | 保护性下限；`budgetExhausted` 观测调参 |
| Round 2 追加 factsJson 与 Round 1 重复 → 簇膨胀 | 跨轮 URL 去重（§4.2）+ `ClaimSimilarity.sameClaim` 既有聚类兜底 |
| Round 2 覆盖 Round 1 notes | 复用 `updateAgent` 增量写回（已保留 `search`），AC-C2 断言 |
| extract 改动误伤付费路径 | 默认 order 下等价 + 单测 |

## 8. 回滚

不设 followup/budget（默认 off / 上限值）即回到单轮研究；`extract` 修复默认配置下行为等价。无 DB 迁移。
