# design.md — 简报检索来源增强（B 方案：核心编排优先）

> 本文档只论证 **B 方案** 的技术设计与合理性。R1（Serper 接入）是 B 的前置基座，逻辑直白（照 `TavilySearchTool` 克隆），仅 §0.1 记录其由实测得出的硬性约束；重点在 R2 / R3 / R5 三个机制，以及 R4 / R6 为何默认关闭。

## 0.1 R1 的硬性约束（由中转实测得出，非可选）

| 约束 | 证据 | 设计后果 |
|---|---|---|
| **`apiBase` 必须可配置** | 官方 `https://google.serper.dev/search` vs 中转 `https://search.604020.xyz/serper/search` —— 中转特有的 `/serper` 路径前缀 | 新增 `sparkora.deep.serperApiBase` + `effectiveSerperApiBase()`（默认官方端点），照 `effectiveTavilyKey()` 的 property→env→字段兜底链。**不可省**，否则换端点要改代码重编 |
| **单次返回硬上限 10 条** | 实测 `num=20` 只回 10 条（`credits=1`） | R5 的 per-provider clamp 必须按 provider 分别设上限，不能假设 `maxResults` 一定被满足；`WebResultNormalizer` 现有截断逻辑已能兜住，但 `SearchMeta.attempts.resultCount` 必须记**实际**返回数而非请求数，否则预算核算失真 |
| **仅 `search` 垂直有 `organic`，`news` 垂直有 `date`/`source`** | 实测 `/serper/search` 返回键 `credits/organic/searchParameters`；`/news` 返回 `news[]`，元素含 `date/source/imageUrl/link/snippet/title` | 垂直路由见 §5.1；`/search` 路径**无 `date`**，故时效能力不能全局宣称 |
| **认证走 Header `X-API-KEY`，非 body** | 实测 `POST /serper/search` + `X-API-KEY` → 200 | 与 Tavily 的 body `api_key` 不一致，两个 `SearchTool` 实现各自认证方式不同，需在 `SearchTool` 接口注释中写明，避免后来者误以为 body 通用 |
| **地域本地化参数 `gl`/`hl` 生效** | 实测 `gl=cn&hl=zh-cn` 回显于 `searchParameters` | 中文召回质量的调优入口。实测 `gl=cn` 下命中 autohome / byd.com / 新浪财经 / news.cn，建议 `SerperSearchTool` 默认带 `gl=cn`、`hl=zh-cn`（可通过配置覆盖），否则英文默认 `hl=en` 会削弱中文召回 |
| **Tavily 侧 `apiBase` 同样硬编码** | `TavilySearchTool.java:30` `DEFAULT_API_BASE="https://api.tavily.com"`，仅包级测试构造器可注入（`:49`） | 建议同批加 `sparkora.deep.tavilyApiBase` + `effectiveTavilyApiBase()`。A 只做**单端点可配置**；**双端点编排（中转优先 + 官方兜底 + 独立超时 + 质量门）是用户 2026-10-05 指令，落在 `10-05-tavily-endpoint-priority`**（挂在 10-04 下），A 不实现 |

## 0. 根问题陈述（先讲清「为什么」）

剥离所有实现细节，当前简报外部证据的质量瓶颈是三句话：

1. **每个问题只有 1 条搜索摘要。** `webQuotaPerAgent = max(1, 8/n)`（`DeepResearchService:236` 注释明写语义是「单 provider 返回条数上限」），n=6 时每 agent 配额 = 1，再经 `Math.min(5, webQuota)` 夹一次（`SubAgentRunner:141`）→ **每问题 1 条**。这是「文章质量」被卡死的第一道闸。
2. **单 provider 短路让交叉验证形同虚设。** `WebSearchRouter.search` 首个有效命中即 `return`（`WebSearchRouter:79-84`），`WebProvider` 类注释明写这是刻意决策（避免付费 provider 无条件重复调用）。后果是 `FactSheetService` 里那条设计得很好的 `sourceCount>=2 → confidence 0.85 / type=MULTI` 通道（`FactSheetService` 聚类段）在 **WEB-only 簇上几乎永远不触发**——同一 provider 的不同 URL 虽算多源，但只有 1 个 provider 参与时，召回同 claim 的概率被单源 SERP 同质性压死，绝大多数簇落进 `0.4 + warnings「仅单一 WEB 源,待核实」`。
3. **缺口只被记录、不被补。** `validateFacts` 把被拒事实转成 `gapOf(f, reason)`（`SubAgentRunner:257-321`），`FactSheetService.merge` 把 gaps 去重收集，但没有任何环节**回头补搜**。plan 里某条 `keyQuestion` 问不到，就永久缺失。

于是 B 方案的三机制与这三个瓶颈一一对应，不做多余的事：

| 机制 | 对应瓶颈 | 核心动作 |
|---|---|---|
| R2 多源并行聚合 | 瓶颈 2 | 付费 provider 组并行 → 召回不同站点的同 claim → 喂饱既有 MULTI 通道 |
| R3 覆盖驱动多轮补检索 | 瓶颈 3 | Round 1 汇总后按确定性规则识别缺口 → 定向改写补搜 |
| R5 预算 / 去重 / 缓存 | 瓶颈 1 + 「大量但有质量」 | 三级调用预算 + 跨轮 URL 去重 + 批次内结果缓存 |

R4（来源分级）、R6（补抓扩展）**默认关闭、接口预留**（见 §5）。

## 1. 为什么是 B 而不是 A / C

| 选项 | 缺什么 / 多什么 | 判断 |
|---|---|---|
| **C**（仅 R1 Serper + R2 聚合） | 聚合能让 1 条变「1 条多源见证」，但仍是**问什么搜什么**——plan 问不到的东西依然缺失（瓶颈 3 未解）；参数型事实仍只有一句 snippet（`enrichContent` 只对背景题生效，`SubAgentRunner:142`）。「有规划」落空。 | 不够 |
| **A**（全量含 R4/R6） | R4 需要**持续运维的域名白名单**（站点改名/被收购即失效），是易腐数据，放代码里必然腐化；R6 全面放开 extract 是额外付费调用 + 延迟，且参数型 snippet 通常已含数值。二者都不是当前瓶颈，先做会摊薄重点并放大回归面。 | 过重 |
| **B**（R2+R3+R5 主抓手，R4/R6 留开关） | 先把「量的结构」做对：多源 → 交叉验证通道打通（**复用既有 0.85 语义，零新增置信规则**）；多轮 → 覆盖度有下限保证；预算/去重/缓存 → 「大量调用」可控可解释。R4/R6 以默认关闭的开关预留，接口与配置位一次留好，后续开启是改配置而非改架构。 | **采纳** |

B 的关键判断：**质量提升的主要来源是「多源交叉 + 覆盖兜底」，不是「更多条数」或「更聪明的排序」。** 所以设计上刻意**不放大 `maxResults`**（见 §2.3）。

## 2. 机制一：R2 多源并行聚合

### 2.1 为什么不「无条件全打」

`WebProvider` 类注释明确反对过双源聚合（理由：不让付费 provider 无条件重复调用）。用户本轮授权了额度，但**「允许大量调用」不等于「无脑全打」**，理由有三：

- **收益不均等。** `order` 里的 provider 分两类角色：
  - **primary 组**：托管/付费高质量源（Tavily、Serper）——互补性强（不同索引、不同召回算法），并行合并能真正带来跨源交叉；
  - **fallback 组**：自建/公共实例（SearxNG）——存在意义是「前两者不可用/未配置时不至于无源可用」。
  对 fallback 组也 fanout，等于把「兜底」变成「重复付费/重复限流」，收益接近零。
- **成本可预测性。** 预算治理（R5）要求调用量可预测，无条件全打会让账单随 `order` 长度线性膨胀且无法收敛。
- **延迟已经解决。** 两 provider 并行用虚拟线程（`DeepResearchService:doRunAsync` 已在用 `Executors.newVirtualThreadPerTaskExecutor()`）后延迟取 max 而非 sum，**fanout 不带来延迟惩罚**——这消除了「并行很贵/很慢」的最大顾虑，剩下的只有成本，而成本由 R5 兜。

### 2.2 设计：策略枚举 + primary/fallback 分组（评审修正 2026-10-05：SearXNG 可参与召回）

新增 `SearchStrategy { FIRST_HIT, PRIMARY_FANOUT }`（默认 `FIRST_HIT`），由 `sparkora.deep.web-fanout` 配置。

**修正（P2-5）**：原设计把 `primary = 非 SEARXNG 的已配置工具`，把唯一免费且高召回的 SearXNG 排除在主源外。
用户明确「**不介意多召回，只要控制好质量、不污染文章生成**」。故改为：
- **primary 组由配置决定**（`DEEP_WEB_PRIMARY_PROVIDERS`，默认含 `SEARXNG`），不再硬编码排除；
- SearXNG 参与召回，但**其结果须过质量门**（见 §2.5）后才进合并池；
- 同 provider 多 endpoint/多通道**不提升独立交叉计数**（沿用 §2.3 澄清与 D6）。

`WebSearchRouter.search` 在 `PRIMARY_FANOUT` 下的流程：

1. 过闸不变：`!snapshot.webAllowed()` → `empty(REASON_DISABLED)`。
2. 取 `order` 中 `tool != null && available() && configured()` 的工具，保持 order 顺序。
3. **分组**：`primary = order ∩ 配置的 primary 集合`；`primary` 为空 → **整体回落 FIRST_HIT**（SearxNG-only 部署行为逐位不变）。
4. **并行**调用 primary 组，每个 provider 各取 `maxResults`（并行时无需分摊，各取满，靠合并后统一截断）。
5. 每个 provider 的结果独立走 `WebResultNormalizer.normalize`（保持既有「丢弃非法 URL / 规范化去重 / 截断」语义），异常隔离沿用既有 `continue` 语义并记 `Attempt(REASON_ERROR)`。
6. `WebResultNormalizer.merge(...)` 跨 provider 合并（见 §2.3）。
7. fallback 组**不参与本轮**，仅当 primary 组全部无命中时才按原短路逻辑兜底（保证不降低可用性）。

### 2.5 SearXNG 结果质量门（评审新增，P2-5）

实测 SearXNG 升级后 70 条里 bilibili（20）+ `weixin.sogou.com/link`（10）占 43%，对事实型需求不可读/不可引用
（跳转撞 `antispider`、`iframe_src=None`、无正文）。故 SearXNG 进 primary 组须配质量门，避免污染生成：

- **域名黑白名单**（可配 `DEEP_WEB_DENY_DOMAINS` / `ALLOW_DOMAINS`）：默认剔除 `bilibili.com`、`weixin.sogou.com`（跳转聚合）、已知噪声域；
- **URL 类型过滤**：非正文页（视频页 `/video/`、跳转 `link?url=`）丢弃；
- **与 Tavily 双端点质量门同构**（见 `10-05-tavily-endpoint-priority`）：空 title+content、非法 URL 丢弃；
- **默认保守**：质量门对 SearXNG 默认开，阈值保守（宁可少召回也不注入低质）；其余 provider 不受影响（零回归）。
- 质量门只影响「是否进合并池」，**不改变** `FactSheetService` 的 `url+modelName` 独立来源计数。

> 关键：`FIRST_HIT` 路径代码不动，只是被 `if (strategy == FIRST_HIT)` 包住。默认配置 → 现有部署零行为变化。

### 2.3 合并规则（`WebResultNormalizer.merge`）

- **跨源去重**：按既有 `normalizeUrl`（:74-92，小写 scheme/host、去 fragment、保留 path/query）去重。**首次出现的 provider 胜出**（order 靠前 = 优先级高）。
- **见证计数（`witnessCount`）**：同一 URL 被多个 provider 命中时累加。用途有二：(a) 可观测；(b) **不**把它当成 `sourceCount`（见下方「重要澄清」）。
- **endpoint 见证（`witnessEndpoints`，D6）**：同一 provider 的多个 endpoint（如 Tavily 官方 + 中转）命中同一 URL 时，只累加 `witnessEndpoints` 供调度/健康观测，**不得**混入 `witnessCount`，也**不得**抬升 `sourceCount`/confidence。provider 身份（`name()`）是独立来源计数的唯一粒度。
- **排序**：先按 provider 在 order 中的位次升序（稳定），provider 内保持原 rank。→ 等价于「优先级高的源的更靠前结果优先」。
- **截断**：合并排序后截到 `maxResults`。
- **`sourceId` 在 merge 之后统一分配** `W1..Wn`（现有 `normalize` 里的 `sourceId = "W" + (out.size()+1)` 逻辑上移到 merge 末尾）。这是**必须做对**的一步：`validateFacts` 用 `byId.get(sourceId)` 严格比对 URL + provider（`SubAgentRunner:257-321`），若两个 provider 都从 `W1` 起号，引用会被误判为 URL 不匹配而剔除。

**重要澄清（避免设计错误）**：`FactSheetService.distinctSources` 按 `url+modelName` 粗判去重，所以**同一 URL 被两个 provider 命中只算 1 个源，不会抬升 confidence**。多源 fanout 提升 confidence 的路径是「不同 URL、不同站点的同一 claim 被同时召回」——而不同站点召回依赖**不同措辞的 query**。所以：

> **R2 单独上线时对 MULTI 的提升有限；R2 的收益必须与 R3 的改写补检索（或后续的 query 规划）耦合才充分释放。** 这决定了实现顺序上 R2 与 R3 应同批交付、一起验收，而不是把 R2 当独立卖点。

### 2.4 契约变更（全部向后兼容）

| 位置 | 现状 | 变更 |
|---|---|---|
| `WebSearchOutcome`（:52 record） | `usedProvider` 单值 | 保留为「首个产出命中的 provider」（兼容既有前端/测试），**新增** `usedProviders: List<WebProvider>`；加字段用**兼容构造器**（本仓 record 一贯做法，如 `Note` 4 参、`WebHit` 5 参兼容构造器） |
| `SubAgentRunner.SearchMeta` | `provider` 单值字符串 | 保留，**新增** `providers: List<String>`；`attempts` 已有逐 provider 明细，前端改读 `attempts` 即可拿到完整来源分布 |
| `WebProviderOrder.strategyLabel()`（:65-68） | 只有 `TAVILY_FIRST`/`SEARXNG_FIRST` | 扩为三值，新增 `PRIMARY_FANOUT`（多源时回落到此标签）；**必须扩**，否则加 SERPER 后 order 首元素非 SEARXNG 一律显示 `TAVILY_FIRST`，运维看健康状态会被误导 |
| `toolHealth` | 无 SERPER | 新增 `SERPER` 枚举项（未配置 → `UNCONFIGURED`，与 Tavily 现状一致） |

## 3. 机制二：R3 覆盖驱动多轮补检索

### 3.1 为什么是「确定性驱动」而不是「LLM 自主多轮」

考虑过的替代方案：让 LLM 在研究过程中自主决定「再搜几次、搜什么」（agentic search）。放弃它，理由：

- **调用量不可预测**——与用户「有规划、有质量」的直接诉求相反；
- **长尾延迟**——多轮 LLM 决策串在关键路径上，`researchTimeoutMs` 兜底会频繁触发，反而降低成功率；
- **成本不可收敛**——无法给出「一次简报最多花多少钱」的上界；
- **不可单测**——确定性规则可写单测，LLM 自主决策只能端到端观测。

改为：**Round 1 现状不变 → 汇总后用确定性规则从已有产物里挑缺口 → 定向补搜。** 全部输入都来自 `fact_sheet`（已落库的 `entries`/`gaps`/`warnings`），无新增 LLM 决策。

### 3.2 两阶段数据流

```
Round 1（现状，不改）
  selectResearchWindow(questions, maxAgents)      // 纯函数、无 LLM、:180-203
    → n 个子代理并行 research(..., webQuota=max(1,8/n), snapshot)
    → 每 Note 增量落库（updateAgent，notesLocks 串行化，:390-421）
    → factSheet.merge(notes) → fact_sheet
    ↓
Gap 分析（新增，纯函数、零 LLM）
  selectFollowupTargets(fact_sheet, maxFollowups=2)  // 新增，与 selectResearchWindow 同风格
    候选规则（三条，满足任一即入选）：
      a) gaps 中 reason 属「检索类」（sourceId 未命中 / URL 不匹配 / provider 不匹配）
      b) entries 中 confidence <= 0.4（纯单 WEB 源）且 kind == "param"（参数型事实最需交叉验证）
      c) plan 的 keyQuestion 在 Round 1 产物中既无 entry 也无 gap（彻底没被回答）
    → 按「claim 关键词归属 + 背景/参数类型」聚类去重 → 截断到 maxFollowups
    ↓
Round 2（新增）
  每个目标 → 一次 PRIMARY_FANOUT 多源搜索（复用 §2 机制，query 复用 webQuery 拼装）
    → 新命中交 LLM 做**增量抽取**（每目标 ≤1 次 LLM）
    → 命中 URL 先经跨轮去重（R5，见 §4.2），已见 URL 直接丢弃，不重复注入 LLM
    → 抽取结果**追加**进对应 Note 的 factsJson；search 字段追加 attempts
  ↓
再次 factSheet.merge(notes) → 新 fact_sheet
  ↓
briefService.generateFromFactSheet(...)   // 只在最后调用一次
```

### 3.3 三个关键设计约束

1. **Round 2 不新建子代理、不重跑 plan。** 只对 ≤2 个目标各发 1 次 fanout + ≤1 次 LLM 抽取。理由：重建子代理会重复付费（`runningBriefs` 互斥只防并发，不防用户重复触发后的全量重跑）且 LLM 成本翻倍；而 **notes 增量写回机制已经存在**（`DeepResearchService.updateAgent` :390-421 显式保留既有 `search` 字段防丢更新）→ 这是最小改动点，直接复用。
2. **query 改写用确定性拼装，不调 LLM。** 复用 `SubAgentRunner.webQuery(topic, question, lockedAnswersJson)`（:207-218：主题 + 问题 + 非空锁定答案 + `isNegativeAnswer` 排除），把「问题」替换成「目标 claim」。好处：省 LLM 调用、行为可预测、`isNegativeAnswer` 等既有防护自动继承。
3. **超时独立。** 现状 `f.get(props.getResearchTimeoutMs())` 只包 Round 1 的 future（`DeepResearchService:doRunAsync` 收集器段）。Round 2 用独立可配 `followupTimeoutMs`（默认 30s），并叠加**总调用预算**保护（§4.1）。任何超时/异常 → 记 warning + 跳过该目标，**降级不阻断**（沿用 `rag_status` 四态既有降级语义）。

### 3.4 `selectFollowupTargets` 为什么是纯函数

与 `selectResearchWindow` 保持同构（static、包级可见、无副作用），因为：
- 它是**质量策略的核心**（决定补什么），必须是可单测的确定性单元；
- `run`（落 PENDING 占位）与 `doRunAsync`（执行）需要共享同一选择结果，否则状态与产物会漂移；
- 未来若要调整补检索策略（比如加来源分级信号），只改这一个纯函数，不触碰异步编排。

## 4. 机制三：R5 预算 / 去重 / 缓存

### 4.1 三级调用预算

`WebCallBudget` 值对象，挂在 research 批次上下文（随批次释放）：

| 级别 | 默认 | 作用 |
|---|---|---|
| per-provider | `maxResults`（不变） | 单次调用**请求**条数上限；**实际**返回数另记（Serper 实测请求 20 只得 10，见 §0.1） |
| per-round | `webCallBudgetPerRound`（**12**） | Round 1 阶段**计量源**调用次数封顶（不含免费 SearXNG），防止 n 或计量 primary 组变大时失控 |
| per-brief | `maxTotalWebCalls`（**20**） | 整个简报的调用总量上限，Round 1 + Round 2 共享 |
| maxFollowups | **2** | Round 2 补检索目标数上限 |

超限行为：**立即停止发起新调用，记 `budgetExhausted=true` 到 `SearchMeta` 与 warnings，已获得的证据照常进入事实手册**。不做「报错中断」——研究是增强，降级优先。

> **预算只计「计量/限流」provider（评审修正 2026-10-05）**：`webCallBudgetPerRound`/`maxTotalWebCalls` 只对**付费/限流**源
> （Tavily、Serper）计数；**免费本地源（SearXNG）不计入预算**。理由：SearXNG 无额度成本，把它计入会让「加 SearXNG 进 primary」
> 变相抬升预算阈值、与「不限额度」意图冲突。这样 primary 组含 SearXNG 后，计量源仍为 Tavily+Serper 两个，下述 12/20 推导继续成立。
>
> **数值来源（Q2 用户决策：保守档）**。推导：`n=6` × 计量 primary 2 个（Tavily、Serper）→ Round 1 需 **12** 次；Round 2 补 2 目标 × 2 = 4 次；合计 16，`maxTotalWebCalls=20` 留约 25% 余量。
>
> **`webCallBudgetPerRound` 由 8 修正为 12**：初版提案 8 是按「Round 1 约 6 次」估的，但 fanout 开启后 Round 1 实际是 `6 × 2 = 12` 次——若用 8，Round 1 会在 n=6 时提前耗尽并触发 `budgetExhausted`，**主抓手 R2 反而被自己的预算掐死**。该值必须 ≥ `maxAgents × |计量 primary 组|`，故定为 12。
>
> 保护性下限：`webCallBudgetPerRound` 生效时取 `max(配置值, maxAgents × |计量 primary 组|)`（**只算计量源，不含 SearXNG**），避免用户调小 `DEEP_MAX_AGENTS` 或增删 provider 时再次出现「预算掐死主流程」。此项列入 `implement.md` 实现期校准项。
>
> 调参依据：`budgetExhausted` / `cacheHit` / `dedupedCount` 三个观测项（§4.4）上线后即可判断 20 是否偏紧或偏松。

### 4.2 跨轮去重（比缓存更重要）

- 按 `normalizeUrl` 在**跨轮次**范围去重：Round 2 命中 Round 1 已注入过的 URL → 直接丢弃。
- 收益有三：不重复注入 LLM（省 token）、不让同源重复计为「多来源」（防止虚高 `sourceCount`）、不让同一 URL 在 facts 里出现两次污染聚类。
- 落点：`WebResultNormalizer` 增加一个批次级 `seenUrls: Set<String>`，由 Round 2 合并时传入。

### 4.3 缓存：为什么只做「批次内」

- **做**进程内 `ConcurrentHashMap` 缓存，key = `provider + "|" + normalizeUrl(query) + "|" + maxResults`，TTL 可配（默认 10min）。
- **作用域严格限定在单次 research 批次内**（挂在快照/批次上下文，随批次结束释放）→ 天然不跨用户、不跨请求留数据，符合 `SecurityUtil` 隔离要求。
- **不做跨批次持久缓存**，理由：(a) 搜索结果时效性强，跨批次命中反而可能返回陈旧证据，直接损害「质量」目标；(b) 引入持久缓存就要加表 + 迁移 + 失效策略（+ 运维面），复杂度收益不匹配；(c) 批次内去重（§4.2）已经消除了主要浪费源——Round 2 与 Round 1 的 query 高度重叠，正是批次内缓存的主战场。
- 缓存对用户无感（不暴露开关），但**必须有** `cacheHit` 计数进 `SearchMeta.attempts` 可观测，否则「大量调用」的账单异常无法归因。

### 4.4 可观测

复用既有 `SearchMeta.attempts`（每项 `{provider, resultCount, latencyMs, fallbackReason, ok}`），增量补三个字段：`dedupedCount`、`cacheHit`、`budgetExhausted`。`toolHealth` 除 SERPER 枚举外**不动**。

## 5. R4 / R6：为什么默认关闭、只留开关

> **本节论证已被实测证据部分修正**（见 §5.0），保留修订痕迹以便追溯。

### 5.0 修订：R4 的「域名白名单易腐化」论证不成立，改为「垂直路由依赖」

原论证：R4 需要持续运维的域名白名单，是易腐数据，故默认 off。**实测推翻**：Serper `/news` 垂直每条结果直接返回 `source`（发布方名，如「新浪网」）与 `date`（相对时间，如「2小时前」），**无需自建域名表**。

但换来的约束更精确：**时效/来源维度仅在 `/news` 垂直可得**——`/serper/search` 的 `organic[]` 只有 `link/position/snippet/title`，**没有 `date`**。所以 R4 的真实形态是：

| 子能力 | 数据来源 | 可得性 | 建议 |
|---|---|---|---|
| 时效性（新鲜度衰减） | `/news` 的 `date` | **仅 news 垂直** | 可实现，但前提是**时间敏感问题路由到 `/news`**（见下） |
| 来源权威度 | `/news` 的 `source` | 仅 news 垂直；且 `source` 是**发布方名**（新浪网），不是可判权威的域名 | 收益不确定，仍倾向默认 off |

结论调整：R4 **时效性子项**从「默认 off」上调为「可选实现，成本极低（解析 `date` 字符串）」；**权威度子项维持默认 off**（`source` 是媒体名而非域名，做不出可靠的权威分档，等于回到域名白名单的老问题）。

### 5.1 垂直路由（新增，随 R1 一并实现）

由于 `/news` 与 `/search` 的信息量差异显著，`SearchTool` 需扩展为支持垂直：

- `searchVertical(query, vertical, maxResults)`，`vertical ∈ {web, news}`；默认 `web`，保持现状行为。
- **路由规则**：由问题的时效性信号决定——复用 `ResearchPlannerService.isBackgroundQuestion` 的词表思路，新增时效信号识别（命中「最新/近期/现在/今年/当前/动态/发布」等）。时效题 → `news`，其余 → `web`。
- **但 R3 的补检索目标（`confidence<=0.4` 的 param 类 entry）默认走 `web`**，因为参数型事实（价格/配置）通常不是时效问题。
- 收益：时效题拿到 `date`+`source`，直接喂 R4 时效子项与 `SearchMeta` 的可观测字段。
- 成本：每次 news 调用 `credits=1`（与 search 同价，实测），无额外费用。

### 5.2 R4 权威度子项：维持默认 off

开启后按来源分档（`authoritative` / `general` / `ugc`），影响注入排序与 `FactSheetService` 的 confidence 微调。

**维持默认 off 的理由**（修订后）：`/news` 的 `source` 是**媒体名**而非域名，要把它映射到权威分档仍需一张**人工维护的名称→分档表**（新浪网/新华网/汽车之家/懂车帝…），与原方案的域名白名单**是同一个易腐问题**，只是换了 key 形态。故不因新证据而改变结论。

分档表设计为**外部配置**而非硬编码，后续开启为纯配置动作。

### 5.3 R6 补抓扩展（`sparkora.deep.web-extract-policy=background-only`）

- 现状：`enrichContent` 仅对背景题取 top 1–2 URL 调 `extract`（`SubAgentRunner:142` + :409-442）。
- 扩展档位：`off` / `background-only`（现状默认） / `param-cross`（参数题中「Round 2 交叉验证目标」也补抓）。
- **不做「全部补抓」**：extract 是额外付费调用 + 延迟，而参数型 snippet 通常已含数值；只在**需要交叉验证**时补抓性价比最高。`param-cross` 正是精准命中 R3 的补检索目标，天然与 B 方案主线一致。
- **实测依据**：官方 Tavily `/extract` 返回 `raw_content` 正常（首个中转实测拿到 2381 字符）；`DEEP_TAVILY_API_BASE_URL` 中转为**可连通但间歇性慢失败（恒定约 16s）**，本任务 extract 仍走官方端点；双端点编排见 `10-05-tavily-endpoint-priority`。

### 5.4 顺带修既有缺陷（默认配置下行为等价）

`WebSearchRouter.extract`（:110-124）有两个不一致：
1. 按 `WebProvider.values()` **枚举声明序**遍历，而非快照 `order`（与 `search` 的策略序不一致）；
2. **不受 `webAllowed` 约束**——WEB 全局关闭时仍会发起付费 extract。

改为按快照 order 遍历 + 尊重 `webAllowed`。默认配置（order=`TAVILY,SEARXNG`）下行为等价，属纯 bug 修复，但因触及付费路径必须配单测。

## 6. 兼容、迁移、灰度

- **无 DB 迁移**：全部为进程内状态（预算/去重/缓存均在批次上下文）。
- **默认全关 = 零回归**：`webFanout=FIRST_HIT`、`webFollowup=off`、`webSourceGrading=off`、`webExtractPolicy=background-only`、`maxTotalWebCalls=20`、`webCallBudgetPerRound=12`。现有部署不设任何新配置即可保持逐位等价行为。
- **配置面**：`DeepProperties` 新增字段全部带默认值；`.env.example` 同步新增 `SERPER_API_BASE_URL` / `SERPER_API_KEY` / `DEEP_SERPER_API_BASE_URL` / `DEEP_SERPER_API_KEY` + `DEEP_TAVILY_API_BASE_URL`（对称）+ `DEEP_WEB_FANOUT` / `DEEP_WEB_FOLLOWUP_MAX` / `DEEP_WEB_CALL_BUDGET` / `DEEP_WEB_CALL_BUDGET_PER_ROUND` / `DEEP_WEB_EXTRACT_POLICY` / `DEEP_WEB_VERTICAL_NEWS`。
  - **配置键命名约定**：URL 类配置一律以 `_BASE_URL` 结尾（对齐既有 `SEARXNG_BASE_URL`）。`.env` 中曾误用 `SERPER_API_BASE`，导致该 URL 被 `secret-guard` 插件（`.opencode/plugins/secret-guard.js:12` 的 `NON_SECRET_KEY_RE` 只排除 `_URL`/`_HOST`/`_BASE_URL` 等后缀）误判为凭据并告警——已改正为 `SERPER_API_BASE_URL`。
- **运行时开关（`sparkora_setting`）只放开 provider order**（沿用现状），**不放开 fanout / followup / budget**。理由：这三项直接决定成本，运营误开会造成账单失控；而 `settingService` 的 provider order 已被现有运维流程使用，收窄面最小化风险。
- **灰度顺序**：`webFanout=primary_fanout` 单开 → 观察 `attempts` 的多源命中率与 `fact_sheet` 的 `MULTI` 占比 → 再 `webFollowupMax=2` 单开 → 观察总调用量与 `fact_sheet` 覆盖率提升。
- **回滚**：全部开关回默认即恢复现状；无数据迁移需回滚。

## 7. 关键权衡汇总

| 决策点 | 选择 | 放弃的替代 | 原因 |
|---|---|---|---|
| 多源触发条件 | 配置化 `PRIMARY_FANOUT`，仅 primary 组并行 | 无条件全打全部 provider | fallback 组（自建源）fanout 收益≈零却重复计费；成本需可预测 |
| fanout 后条数 | **不放大** `maxResults`，合并后截断到同值 | 翻倍条数换质量 | 避免 LLM 上下文膨胀；质量靠多源择优而非堆量 |
| 补检索驱动 | 确定性 gap/低置信驱动（零 LLM 决策） | LLM 自主多轮 agentic search | 调用量/延迟/成本均可预测、可单测，符合「有规划」 |
| Round 2 载体 | 复用同一 Note 增量写回 | 重建子代理 / 重跑 plan | 避免重复付费与 LLM 成本翻倍；复用 `updateAgent` 已有机制 |
| query 改写 | 确定性拼装（复用 `webQuery`） | LLM 改写 | 省 LLM 调用、行为可预测、继承 `isNegativeAnswer` 防护 |
| 缓存作用域 | 批次内进程内 TTL | 跨批次持久缓存 | 时效性会返回陈旧证据（反噬质量）；持久化需迁移+失效策略 |
| 去重 | 跨轮次按规范化 URL | 仅批内 | Round 2 与 Round 1 query 高度重叠，跨轮去重是主要节省点 |
| R4 权威度分档 | 维持默认 off 的配置开关 | 本轮实现并默认开 | `/news` 的 `source` 是媒体名非域名，做分档仍需人工维护的名称→分档表，与原域名白名单是同一易腐问题（§5.2） |
| R4 时效性 | 可选实现（解析 `/news` 的 `date`） | 宣称全局时效能力 | `/serper/search` 的 `organic[]` **无 `date`**，仅 `/news` 垂直可得（§5.0） |
| 垂直路由 | `web`/`news` 两垂直，时效题路由 news | 全程单一 `/search` | 两垂直信息量差异显著；news 与 search 同价（`credits=1`），无额外费用（§5.1） |
| Serper 地域参数 | 默认 `gl=cn`/`hl=zh-cn`（可配） | 用 Serper 英文默认 | 实测 `gl=cn` 命中 autohome/byd.com/新浪财经/news.cn；英文默认会削弱中文召回 |
| R6 | 默认 off 的配置开关 | 本轮实现并默认开 | extract 额外付费；Tavily 中转间歇性慢失败（恒定约 16s），extract 仍走官方端点（§5.3） |
| `extract` provider 序 | 改为快照 order + 尊重 `webAllowed` | 保持枚举序 | 修既有不一致（付费路径）；默认配置下等价 |
| 运行时开关 | 只放开 provider order | 同时放开 fanout/followup/budget | 直接决定成本，运营误开即账单失控 |

## 8. 本方案自身的已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| R2 单独上线对 MULTI 提升有限（§2.3 澄清） | 收益感知弱 | R2 与 R3 同批交付、合并验收，不把 R2 当独立卖点 |
| 多源后 snippet 总量上升 → LLM 抽取遗漏增多 | 事实召回率可能不升反降 | `maxResults` 不放大（总注入量基本不变）；`subagent-system.st` 已按 sourceId 索引，格式不变；A/B 观察 `fact_sheet` entry 数 |
| Round 2 追加 factsJson 可能与 Round 1 事实重复 | 聚类簇膨胀、confidence 虚高 | 跨轮 URL 去重（§4.2）+ `ClaimSimilarity.sameClaim` 既有聚类兜底 |
| 多源 fanout 触发 rate limit | 部分 provider 空结果 | 异常隔离沿用既有 `continue`；primary 全空时 fallback 组兜底；`lastCallOk` 健康展示 |
| 预算上限设得过低 | 覆盖度反而下降 | 数值由用户确认（Q2，取保守档 20/12/2），并保留 `budgetExhausted` 观测项便于调参；`per-round` 取 `max(配置值, maxAgents × |primary|)` 保护性下限 |
| Serper 单次实际返回少于请求（实测 20→10） | 预算核算失真 | `attempts.resultCount` 必须记**实际**返回数；per-provider clamp 按 provider 分别设上限 |
| 中转端点不稳定（实测 Tavily 中转间歇性慢失败，恒定约 16s；Serper 中转目前 10/10 稳定） | 主搜索源整体不可用 | 沿用既有降级：`available()`/`configured()` 门控 + `lastCallOk` 健康展示 + primary 全空时 fallback 组兜底；`apiBase` 可配置以便快速切换官方端点；Tavily 专有「中转优先/官方兜底/独立超时/质量门」见 `10-05-tavily-endpoint-priority` |
| 中转失败慢于本仓超时（Tavily 中转 16.2s vs readTimeout 15s） | 表现为 `REASON_ERROR` 而非 HTTP 状态，排查易误判 | `Attempt` 已只记 `e.getClass().getSimpleName()` 不记 message（防密钥泄漏）；需在运维文档写明「中转类故障常表现为超时而非状态码」 |

## 9. 验证策略

**单测（新增）**
- `WebResultNormalizer.merge`：跨源去重 / `witnessCount` 累加 / 合并后 `sourceId` 全局唯一且不与 URL 冲突 / 截断到 `maxResults` / order 优先级排序。
- `WebSearchRouter`（PRIMARY_FANOUT）：primary 组正确选取 / primary 为空回落 FIRST_HIT / 单 provider 异常不影响其他 / `UNCONFIGURED` 跳过 / fallback 组兜底路径。
- `WebSearchRouter.extract`（改后）：按 order 遍历 / `webAllowed=false` 时不发请求。
- `selectFollowupTargets`：三类候选规则命中 / 聚类去重 / `maxFollowups` 截断 / 无缺口时返回空。
- `WebCallBudget`：三级上限 / 耗尽标记 / 耗尽后拒绝新调用。
- `DeepProperties`：`effectiveSerperKey()` 兜底链 / 新字段默认值 / `strategyLabel()` 三值。

**契约测试**：`toolHealth` 含 `SERPER`（未配置 → `UNCONFIGURED`）；`research` 启动返回体含新增 `providers` / `followups` 增量字段，旧字段不变。

**回归**：`mvn test`（现有 842 例必须全绿）+ `npm run build`（前端若消费 `toolHealth` / `SearchMeta`）。

## 10. 任务拆分（已确认，2026-10-05）

本方案含 4 个可独立验收的交付物，已按父+子拆分：

- **父任务** `brief-retrieval-sources`（当前任务）：持有需求集、任务地图、跨子任务验收、最终集成评审；**不作为实现目标**。
- **子任务 A `serper-provider`**：R1 + `WebProvider`/`WebProviderOrder`/`strategyLabel`/`toolHealth` 契约扩展。独立可验收（默认配置下现有行为零变化）。
- **子任务 B `web-fanout-merge`**：R2 多源并行聚合 + `merge` + 契约扩展。依赖 A。
- **子任务 C `web-followup-budget`**：R3 覆盖驱动多轮补检索 + R5 预算/去重/缓存。依赖 A、B（复用 fanout 与 Round 1 产物）。同时在 C 内顺带修 `extract` 的 provider 序 + `webAllowed` 缺陷（属 B/C 共用的搜索层一致性修复）。
- **子任务 D `tavily-endpoint-priority`**（用户 2026-10-05 指令，独立于 B/C）：Tavily 双端点——中转优先 + 官方兜底 + 独立超时 + 质量门；provider 名恒 `TAVILY`（不提升独立来源计数）；不限额度。依赖 A（`apiBase` 可配置）。

依赖关系写进各子任务 `prd.md` / `implement.md`，不依赖树形位置隐含。
