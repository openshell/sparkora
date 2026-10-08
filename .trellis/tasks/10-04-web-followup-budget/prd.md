# prd.md — C: 覆盖驱动多轮补检索 + 调用预算治理

> 父任务：`10-04-brief-retrieval-sources`
> 设计论证见父任务 `design.md` §3（机制二：为何确定性驱动而非 LLM 自主多轮）、§4（机制三：预算/去重/缓存）、§5.4（顺带修 `extract` 既有缺陷）。
> **前置依赖：`10-04-web-fanout-merge` 必须先完成**（依赖声明见下）。这是本任务的**最后一个**交付物。

## Goal

Round 1 汇总后**主动识别未覆盖/低置信的关键问题并定向补检索**，把「缺口只被记录」变成「缺口被填补」；同时用三级调用预算 + 跨轮去重 + 批次内缓存，保证「允许大量调用」在成本与质量上都**可控、可解释**。

用户价值：plan 里某条 `keyQuestion` 问不到、或参数型事实只有单一 WEB 源（`confidence=0.4`）时，本任务把它们捞回来补证，直接提升简报的覆盖率与可信度比例。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置 1：`10-04-serper-provider` 已完成** —— 需其 `SearchTool.searchVertical` 签名与 SERPER provider 可用（补检索同样要走多源才有交叉价值）。
- **前置 2：`10-04-web-fanout-merge` 已完成** —— Round 2 每次补检索**复用其 `PRIMARY_FANOUT` 与 `WebResultNormalizer.merge`**；跨轮 `seenUrls` 由其合并逻辑接收入口。
- **前置未完成不得开工**：否则 Round 2 退化为单源补检索，既无交叉价值，又白白消耗预算（每次调用都计费）。
- **契约约定**：本任务新增的 `SearchMeta` 字段（`dedupedCount`/`cacheHit`/`budgetExhausted`）为**增量**，不得改动 B 已确立的 `providers`/`attempts` 语义。

## Requirements

- **C-R1 缺口识别（纯函数、零 LLM）**：新增 `selectFollowupTargets(factSheet, maxFollowups)`，与 `selectResearchWindow`（`DeepResearchService:180-203`）**同构**：static、包级可见、无副作用、可单测。三类候选规则（满足任一即入选）：
  - a) `gaps` 中 reason 属检索类（sourceId 未命中 / URL 不匹配 / provider 不匹配）；
  - b) `entries` 中 `confidence <= 0.4` 且 `kind == "param"`（参数型事实最需交叉验证）；
  - c) plan 的 `keyQuestion` 在 Round 1 产物中**既无 entry 也无 gap**（彻底没被回答）。
  - 按 claim 关键词归属 + 背景/参数类型聚类去重 → 截断到 `maxFollowups`。
  - **为何零 LLM**：调用量/延迟/成本均可预测、可单测，符合用户「有规划、有质量」的诉求；放弃 LLM 自主多轮的理由见父任务 `design.md` §3.1。
- **C-R2 Round 2 执行**：对每个目标发 **1 次 `PRIMARY_FANOUT`**（复用 B）+ **≤1 次 LLM 增量抽取**。
  - **不新建子代理、不重跑 plan** —— 复用 `DeepResearchService.updateAgent`（`:390-421`，已显式保留既有 `search` 字段防丢更新）的 notes 增量写回。**理由**：重建子代理会重复付费且 LLM 成本翻倍。
  - query 复用 `SubAgentRunner.webQuery(topic, question, lockedAnswersJson)`（`:207-218`）的确定性拼装，把「问题」替换为「目标 claim」；自动继承 `isNegativeAnswer` 防护。
  - 补检索目标默认走 `web` 垂直（参数型事实通常非时效问题）。
- **C-R3 三级调用预算**：`WebCallBudget` 值对象挂批次上下文（随批次释放）。
  - `per-provider` = 请求 `maxResults`（不变）；
  - `per-round` = `webCallBudgetPerRound`，**默认 12**，生效值取 `max(配置值, maxAgents × |计量 primary 组|)` 作保护性下限（**只计付费/限流源 Tavily/Serper，不含免费 SearXNG**）；
  - `per-brief` = `maxTotalWebCalls`，**默认 20**（Round 1 + Round 2 共享）；
  - `maxFollowups` = **2**。
  - **为何 per-round 是 12 不是 8**：fanout 开启后 Round 1 实际是 `n × |计量 primary| = 6 × 2 = 12` 次（SearXNG 免费不计）；若用 8，Round 1 会在 n=6 时提前 `budgetExhausted`，**主抓手 R2 反被自己的预算掐死**。
  - 超限行为：立即停止发起新调用，记 `budgetExhausted=true`，**已获证据照常进入事实手册**；不做「报错中断」。
- **C-R4 跨轮去重**：按 `normalizeUrl` 在**跨轮次**范围去重，Round 2 命中 Round 1 已注入的 URL 直接丢弃。收益：不重复注入 LLM（省 token）、不让同源重复计为多来源（防虚高 `sourceCount`）、不污染聚类。落点：`WebResultNormalizer.merge` 增加批次级 `seenUrls: Set<String>` 入参。
- **C-R5 批次内缓存**：进程内 `ConcurrentHashMap`，key = `provider + "|" + normalizeUrl(query) + "|" + vertical + "|" + maxResults`，TTL 默认 10min，**作用域严格限定单次 research 批次**（随批次释放）。
  - **不做跨批次持久缓存**：搜索结果时效性强，跨批次命中会返回陈旧证据，**直接反噬质量目标**；且需加表 + 迁移 + 失效策略，复杂度收益不匹配。批次内去重（C-R4）已消除主要浪费源——Round 2 与 Round 1 的 query 高度重叠，正是批次内缓存主战场。
  - **必须有** `cacheHit` 计数进 `SearchMeta`，否则账单异常无法归因。
- **C-R6 超时与降级**：Round 2 用独立可配 `followupTimeoutMs`（默认 30s）。现状 `f.get(researchTimeoutMs)` 只包 Round 1 的 future（`DeepResearchService.doRunAsync` 收集器段）。任何超时/异常 → 记 warning + 跳过该目标，**降级不阻断**（沿用 `rag_status` 四态既有降级语义）。
- **C-R7 顺带修 `extract` 既有缺陷**：`WebSearchRouter.extract`（`:110-124`）现按 `WebProvider.values()` **枚举声明序**遍历（与 `search` 的策略序不一致），且**不受 `webAllowed` 约束**（WEB 全局关闭时仍会发起付费 extract）→ 改为按快照 order 遍历 + 尊重 `webAllowed`。默认配置（order=`TAVILY,SEARXNG`）下行为等价，但因触及付费路径**必须配单测**。
- **C-R8 可观测与文档**：`SearchMeta` 增量 `dedupedCount`/`cacheHit`/`budgetExhausted`；`.env.example` 新增 `DEEP_WEB_FOLLOWUP_MAX` / `DEEP_WEB_CALL_BUDGET` / `DEEP_WEB_CALL_BUDGET_PER_ROUND` / `DEEP_FOLLOWUP_TIMEOUT_MS`；`docs/spec/brief-generation.md` 补两轮研究流程与预算说明。

## Acceptance Criteria

- [x] **AC-C1 缺口识别可单测**：`selectFollowupTargets` 三类候选规则各自命中（构造 fact_sheet 夹具）；聚类去重生效；`maxFollowups=2` 截断生效；不传缺口时返回空列表（不得发起任何额外调用）。→ `SelectFollowupTargetsTest`(10) 覆盖三类规则/聚类去重/截断/无缺口空。
- [x] **AC-C2 Round 2 增量写回**：补检索结果并入 `fact_sheet`；`research_plan` 与 Round 1 已落地的 notes 内容不被覆盖（保留 `search` 字段）；补检索不新建子代理、不重跑 plan（断言子代理数不变）。→ `DeepResearchServiceFollowupTest.Round2_不新建子代理_不覆盖Rround1_且generate仅一次`（`research` 恰 2 次、`notes.size()==2`、每条保留 `search`）。
- [x] **AC-C3 预算保护**：`maxTotalWebCalls=20` 生效（构造耗尽场景，超限后拒绝新调用并置 `budgetExhausted`）；`per-round` 生效值为 `max(配置值, maxAgents × |计量 primary|)`（SearXNG 不计）；调小 `DEEP_MAX_AGENTS` 或增删 provider 时保护性下限仍生效。→ `WebCallBudgetTest`(8)（per-brief 耗尽+标记 / 保护性下限 / SearXNG 不计 / Round 2 槽）。
- [x] **AC-C4 跨轮去重**：Round 2 命中 Round 1 已见 URL 时不注入 LLM、不计入 `sourceCount`；`dedupedCount` 计数正确。→ `WebResultNormalizerMergeTest.跨轮去重_已见URL丢弃并计数` + `seenUrls为null_旧行为等价`；`WebBatchContext` Round1 只收集 / Round2 去重。
- [x] **AC-C5 缓存**：批次内相同 `provider+query+vertical+maxResults` 第二次命中缓存（`cacheHit` 可见）；批次结束后缓存全部释放（不留跨批次数据）。→ `WebSearchCacheTest`(6)（key 维度/规范化/TTL/`release()`/空不缓存）；`cacheHit` 经 `SubAgentRunner` 进 `SearchMeta`，`doRunAsync` finally `release()`。
- [x] **AC-C6 降级不阻断**：补检索超时/异常/空结果 → warning + 跳过，不中断研究；`budgetExhausted` 场景下已获证据照常进入 `fact_sheet` 并正常生成简报；`webAllowed=false` 时 Round 2 完全不发起（含 `extract`）。→ `DeepResearchServiceFollowupTest`（`Round2超时_降级不阻断`/`webAllowed关闭_Round2不发起`/`followupMax为0_不发起Round2`）；router 超限 `continue` 且 Round 1 merge + generate 照常。
- [x] **AC-C7 extract 缺陷已修**：按快照 order 遍历（构造 order=`SEARXNG,TAVILY` 断言优先走 SEARXNG）；`webAllowed=false` 时不发 extract 请求；默认 order 下行为等价。→ `WebSearchRouterExtractTest`(4)（快照 order 优先 SEARXNG + `verify(tavily, never())`；`webAllowed=false` 零请求；默认等价；2 参兼容）。
- [x] **AC-C8 契约增量**：`SearchMeta` 新增三字段为增量，B 确立的 `providers`/`attempts` 语义未变；`generateFromFactSheet` 只调用一次（在两轮合并之后）。→ `SubAgentRunner` 7/8 参兼容构造器；`DeepResearchServiceFollowupTest` 断言 `generateFromFactSheet` `times(1)`、`merge` 两次。
- [x] **AC-C9 回归**：`mvn test` 全绿（含新增单测）+ `npm run build` 通过；`docs/spec/brief-generation.md` 已补两轮流程与预算字段级说明。→ `mvn test` 1014/0/0/0；`npm run build` ✓ 40.33s；docs/spec 与 `.trellis/spec/backend/ai-rag-guidelines.md` 已同步。

## Out of Scope

- Serper provider 接入与垂直路由 → `10-04-serper-provider`。
- 多源并行聚合与跨源合并实现 → `10-04-web-fanout-merge`（本任务只**复用**）。
- R4a 来源权威度分档、R4b 时效性计算、R6 补抓扩展（`param-cross` 档位）的业务逻辑——仅留配置位，默认 off / `background-only`。
- 跨批次持久缓存；LLM 自主多轮 agentic search；query 语义改写（LLM）；`maxResults` 放大。
- 运行时 `sparkora_setting` 放开 followup / budget 开关（成本风险，见父任务 `design.md` §6）。

## Notes

- 预算数值由用户决策（父任务 `prd.md` Q2，保守档 20/12/2）。上线后按 `budgetExhausted`/`cacheHit`/`dedupedCount` 三个观测项调参。
- R6 的 `param-cross` 档位（参数题交叉验证目标也补抓 extract）**是本任务预留的天然延伸**——补检索目标已具备「需要交叉验证」语义，后续开启只需补一个策略分支，无需改数据流。
