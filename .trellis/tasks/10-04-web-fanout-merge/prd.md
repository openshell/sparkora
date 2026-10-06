# prd.md — B: 多源并行聚合（PRIMARY_FANOUT + 跨源合并）

> 父任务：`10-04-brief-retrieval-sources`
> 设计论证见父任务 `design.md` §2（机制一，含为何不「无条件全打」/ 为何不放大 `maxResults` / 为何 `sourceId` 必须在合并后统一分配）。
> **前置依赖：`10-04-serper-provider` 必须先完成**（依赖声明见下）。

## Goal

把 `WebSearchRouter` 从「首个有效命中即停」升级为**可配置的 primary 组并行聚合**，让同一 query 能召回**不同来源站点**的同 claim，从而喂饱 `FactSheetService` 已有的 `sourceCount>=2 → confidence 0.85 / type=MULTI` 交叉验证通道。

用户价值：当前纯 WEB 事实簇几乎全部落进 `0.4 + warnings「仅单一 WEB 源,待核实」`；本任务让「有出处」升级为「多出处互证」，直接提升简报可信度。**默认关闭，现有部署零行为变化。**

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置：`10-04-serper-provider` 已完成**。本任务依赖其提供：`WebProvider.SERPER` 枚举、`WebSearchRouter` 构造器注册点、`toolHealth` SERPER 分支、`strategyLabel()` 三值扩展、`SearchTool.searchVertical` 签名。
  - 若前置未完成，本任务**不得开工**——否则会出现「多源聚合里只有 TAVILY 可并行」的空实现，或与 A 的 `strategyLabel` 改动冲突。
- **后继：`10-04-web-followup-budget` 依赖本任务的 `PRIMARY_FANOUT` 与 `WebResultNormalizer.merge`**（C 的 Round 2 补检索复用 fanout 与合并逻辑）。C 另需本任务确立的**跨轮 `seenUrls` 传入点**。

## Requirements

- **B-R1 策略枚举**：新增 `SearchStrategy { FIRST_HIT, PRIMARY_FANOUT }`，由 `sparkora.deep.web-fanout` 配置，**默认 `FIRST_HIT`**。`FIRST_HIT` 路径代码不动，仅被条件包裹。
- **B-R2 primary / fallback 分组（评审修正 2026-10-05：SearXNG 可参与召回）**：`PRIMARY_FANOUT` 下，`primary = order ∩ 配置集合 DEEP_WEB_PRIMARY_PROVIDERS`（**默认含 SEARXNG**）；`primary` 为空 → **整体回落 `FIRST_HIT`**（SearxNG-only 部署逐位不变）。`primary` 组并行调用（虚拟线程，与 `DeepResearchService.doRunAsync` 现有 `newVirtualThreadPerTaskExecutor()` 同款）；`fallback` = order 中**不在 primary 集**的已配置源，仅当 `primary` 全部无命中时按原短路逻辑兜底。
  - **理由**：用户 2026-10-05「不介意多召回，只要控制好质量、不污染生成」。SearXNG 免费、无限、高召回（70 条），排除在 primary 外与召回优先目标不符；改为**配置参与 + 质量门**（B-R2a）。
- **B-R2a SearXNG 结果质量门**：SearXNG 进 primary 组时须过滤低质（域名黑名单 `DEEP_WEB_DENY_DOMAINS` 默认含 `bilibili.com`/`weixin.sogou.com`；非正文页如 `/video/`、`link?url=` 丢弃；空 title+content/非法 URL 丢弃），详见父任务 `design.md` §2.5。**只影响是否进合并池，不提升独立交叉计数。**
- **B-R3 跨源合并**：`WebResultNormalizer.merge(...)`：
  - 按既有 `normalizeUrl`（`:74-92`）跨源去重，**首次出现的 provider 胜出**（order 靠前 = 优先级高）。
  - 累加 `witnessCount`（同一 URL 被多源命中）。**明确：`witnessCount` 不等于 `sourceCount`**，不参与 confidence 计算。
  - 排序：provider 在 order 中的位次升序（稳定），provider 内保持原 rank。
  - **`maxResults` 不放大**——合并排序后截断到与单源调用相同的 `maxResults`。**理由**：避免 LLM 上下文膨胀导致抽取遗漏反增；质量靠多源择优而非堆条数。
  - **`sourceId` 在 merge 之后统一分配 `W1..Wn`**（把现有 `normalize` 内的 `sourceId = "W" + (out.size()+1)` 逻辑上移）。**这是正确性关键**：`validateFacts` 用 `byId.get(sourceId)` 严格比对 URL + provider（`SubAgentRunner:257-321`），若两 provider 都从 `W1` 起号，引用会被误判为 URL 不匹配而剔除。
- **B-R4 契约扩展（全部向后兼容）**：
  - `WebSearchOutcome` 保留 `usedProvider`（首个产出命中的 provider）+ 新增 `usedProviders: List<WebProvider>`，用兼容构造器（沿用本仓 record 惯例）。
  - `SubAgentRunner.SearchMeta` 保留 `provider` + 新增 `providers: List<String>`；`attempts` 已有逐 provider 明细，前端改读 `attempts`。
- **B-R5 可观测**：`attempts` 每项记 `{provider, resultCount(实际), latencyMs, fallbackReason, ok, witnessTotal}`，其中 `witnessTotal` = 该 provider 命中中已有其他 provider 见证的数量，用于直接观察交叉验证收益。
- **B-R6 配置面**：`DeepProperties.webFanout`（默认 `first_hit`）；`.env.example` 新增 `DEEP_WEB_FANOUT`。**运行时 `sparkora_setting` 不放开该开关**（直接决定成本，运营误开即账单失控）。

## Acceptance Criteria

- [ ] **AC-B1 零回归**：不设新配置时 `FIRST_HIT` 行为与现状**逐位等价**；`mvn test` 全绿 + `npm run build` 通过。
- [ ] **AC-B2 分组正确**：`PRIMARY_FANOUT` 下 primary 组按 `DEEP_WEB_PRIMARY_PROVIDERS` 正确选取（**默认含 SearXNG**；可从配置移除）；`primary` 为空时回落 `FIRST_HIT`；fallback 组在 primary 全空时仍能兜底产出结果。
- [ ] **AC-B8 质量门**：SearXNG 结果中的 `bilibili.com`/`weixin.sogou.com`/`/video/`/`link?url=` 被过滤，不进合并池；过滤后仍保留正文页结果；`sourceCount` 不受质量门影响。
- [ ] **AC-B3 异常隔离**：一个 provider 抛异常/超时/空结果，其他 provider 仍正常产出；异常 provider 记 `Attempt(ok=false, REASON_ERROR)`，**不记 `e.getMessage()`**（沿用 `WebSearchRouter:70-75` 防密钥泄漏写法）。
- [ ] **AC-B4 sourceId 正确性（重点）**：构造「两个 provider 各返回结果」的单测，断言合并后 `sourceId` 全局唯一且 LLM 引用任一 `sourceId` 都能通过 `validateFacts` 的 URL+provider 严格比对——**必须包含反例**（同一 URL 被两源命中、同一 title 不同 URL）。
- [ ] **AC-B5 合并语义**：跨源去重生效（重复 URL 只出现一次）；`witnessCount` 正确累加；order 位次排序稳定；`maxResults` 未被放大（构造 2 provider × 10 结果，断言输出仍为 `maxResults` 条）。
- [ ] **AC-B6 契约增量**：`usedProviders`/`providers` 为新增字段，`usedProvider`/`provider` 旧字段名与语义不变；`strategyLabel()` 在 fanout 下返回 `PRIMARY_FANOUT`；旧前端不读新增字段不报错。
- [ ] **AC-B7 交叉验证可观测**：同一项目以 `FIRST_HIT` vs `PRIMARY_FANOUT` 各跑一次，`fact_sheet` 中 `MULTI` entry 占比或 `sourceCount>=2` 簇数应上升；`attempts[].witnessTotal` 与 `SearchMeta.providers` 能完整列出参与 provider。

## Out of Scope

- 补检索轮次、预算/去重/缓存 → `10-04-web-followup-budget`。
- R4a 来源权威度分档、R4b 时效性计算（字段已由 A 取到，本任务不消费）。
- 跨批次持久缓存（父任务 `design.md` §4.3 已论证不做）。
- `maxResults` 放大、query 改写、LLM 自主多轮决策。

## Notes

- **本任务单独上线对 `MULTI` 的提升有限**（父任务 `design.md` §2.3「重要澄清」）：`FactSheetService.distinctSources` 按 `url+modelName` 去重，同一 URL 被两源命中只算 1 源。多源真正抬升 confidence 依赖「不同 URL 的同 claim 同时召回」，这需要不同措辞的 query——由 C 的补检索提供。**故 B 不得作为独立卖点验收，AC-B7 是观测性验证而非强断言。**
