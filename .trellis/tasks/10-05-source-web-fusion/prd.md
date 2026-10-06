# prd.md — 外部搜索与本地信源融合

> 父任务：`10-05-self-hosted-sources`（需求集与设计论证持有者）
> 依赖：[`10-05-source-domain-retrieval`](../10-05-source-domain-retrieval/prd.md)（本地信源进检索）+ [`10-04-brief-retrieval-sources`](../10-04-brief-retrieval-sources/prd.md)（外部搜索 provider 契约）。
> 现状依据：`docs/spec/brief-generation.md` §4–§5、`src/main/java/com/sparkora/deep/service/FactSheetService.java`。

## Goal

在事实手册层把**本地自建信源**（全文、可控、可追溯）与**外部搜索**（实时、广覆盖但摘要级/受限流）融合为互补证据：本地优先、外部补缺，跨源同 URL 去重，明确本地 vs 外部的置信与时效规则，避免同源多通道被误判为独立交叉。

用户价值：这是「自建信源 + 外部搜索」组合的收口——只有融合质量提升，前四个子任务才产生最终写作价值。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置**：`10-05-source-domain-retrieval`（本地信源事实可从检索链路取得）+ `10-04-brief-retrieval-sources` 的 provider/`usedProviders`/`SearchMeta` 契约。
- **后继**：无（父任务集成验收在此收口）。

## Requirements

- **F-R1 本地信源事实识别（评审修正：明确 SOURCE 类型与白名单突破）**：`FactSheetService`/`SubAgentRunner` 能识别来自本地信源（NEWS 域 `sourceType=user-source`/`byd-news`）的 fact（与纯 KB 通用知识、纯 WEB 区分）。**关键约束**：`SubAgentRunner.validateFacts`（`src/main/java/com/sparkora/deep/service/SubAgentRunner.java:268-271`）现硬白名单仅 `KB|WEB`，其他类型直接转 gap；且 `KnowledgeSearchTool` 统一 `name()="KB"`+`SearchHit.kb(...)`，本地信源事实会被误当 KB（0.9）。**必须**：① 白名单扩为 `KB|WEB|SOURCE`（**核验语义**：SOURCE 无 url/sourceId 时直接接受同 KB；带 url/sourceId 时按 WEB 严格核验，杜绝自造 URL 绕校验）；② `SearchHit`/事实 `source.type` 新增 `SOURCE`；③ `KnowledgeSearchTool`（或检索链路）能输出 SOURCE 类型而非一律 KB。保持向后兼容旧 `KB|WEB|MULTI`。依赖 E 的 `Citation.sourceType` 字段贯通（无该字段 F 拿不到来源类型）。
- **F-R2 本地优先与外部补缺**：同一 claim 同时有本地信源与外部 WEB 时，**本地信源优先**（本地为权威正文），外部降为 `alternatives`/补充见证并写 warnings（照现有「KB 胜 WEB」范式扩展）；本地无覆盖时外部照常补缺。
- **F-R3 跨源同 URL 去重**：本地信源与外部搜索命中同一 URL（如外部搜到 BYD 官网/工信部原文）时去重，不重复计入 `sourceCount`、不重复注入 LLM；保留一次、标注双通道见证。
- **F-R4 置信与时效分层（评审修正：权威分档）**：本地信源置信按**权威档**（信源注册表 `authorityTier`）：官方/政务（BYD 官方、工信部、乘联会）0.9、行业媒体（盖世等）0.7、论坛/自媒体/公众号 0.5；**默认不启用分档**（全部走一个保守档）以保证零回归。与外部单源 0.4 / 多源交叉 0.85 的关系见父设计 §2.6；本地信源带 `publishDate` 时参与时效判断；**同源多通道（如 BYD 新闻本地 + 外部 Tavily 搜到同一 BYD 新闻）不得计为独立交叉**（`distinctSources` 仍按 `url+modelName` 去重，同 provider 多 endpoint 亦不提升计数）。
- **F-R5 融合可观测**：`SearchMeta`/`fact_sheet` 增量暴露「本地来源占比 / 外部来源占比 / 同 URL 去重数 / 各档置信」；前端 `FactSheetSummary`/`CitationList` 能展示本地 vs 外部徽标（增量，旧前端不报错）。
- **F-R6 契约兼容**：与 `10-04-brief-retrieval-sources` 的 `usedProvider`→`usedProviders`、`SearchMeta.provider`→`providers`、`attempts` 增量字段保持同一契约，不各自另立。
- **F-R8 交叉计数的来源标志透传（评审新增）**：判定「某来源是否可计独立交叉」的 `crossCounted` 须随事实透传到 `FactSheetService`（`FactSheetService` 现只注入 `ObjectMapper`，无法访问信源注册表）。推荐在 `source` 上以布尔字段透传（与 `sourceType` 同一条链），`distinctSources` 计算前剔除 `crossCounted=false` 的来源。**不新增 `FactSheetService` 对注册表的依赖。**
- **F-R7 配置与文档**：融合策略开关（默认保证零回归）、置信档配置进 `.env.example`；同步 `docs/spec/brief-generation.md` §5、`docs/spec/retrieval.md` 引用契约。

## Acceptance Criteria

- [ ] **AC-F1 本地优先**：同 claim 本地信源 + 外部 WEB 同时存在时，本地胜出、外部进 `alternatives` 并告警；纯本地/纯外部条目置信规则正确。
- [ ] **AC-F2 同 URL 去重**：本地与外部命中同一 URL 时 `sourceCount` 不虚高、LLM 不重复注入。
- [ ] **AC-F3 独立交叉判定**：同源多通道不被计为 `MULTI` 独立交叉（构造「本地 BYD 新闻 + 外部搜到同篇」反例单测）；同 provider 多 endpoint 亦不提升计数；**盖世 `ranking`（`crossCounted=false`）不与乘联会构成独立交叉，而 `gasgoo-announce` 可与乘联会构成独立交叉**（两反例单测）。
- [ ] **AC-F7 SOURCE 类型可用**：自建信源事实以 `SOURCE` 类型进入 `fact_sheet`——`validateFacts` **不拒绝**、**不误标 KB（0.9）**；权威分档按信源档位取置信（官方 0.9/行业 0.7/自媒体 0.5），默认不分档时零回归。构造「产出 SOURCE」与「误标 KB」两反例单测。
- [ ] **AC-F4 可观测**：融合前后可读出本地/外部来源占比与去重数；前端徽标正确展示。
- [ ] **AC-F5 零回归**：未启用任何自建信源时，`fact_sheet` 与现有行为逐位等价；`mvn test` 全绿 + `npm run build` 通过。
- [ ] **AC-F6 降级不阻断**：融合规则异常/字段缺失时降级为现有行为，不阻断研究。

## Out of Scope

- 外部搜索 provider 接入/fanout/补检索（→ `10-04-brief-retrieval-sources`）。
- 采集/入库/切块（→ 前置子任务）。
- LLM 语义融合/摘要压缩（复用确定性规则优先原则）。
- 人工裁决界面。

## Notes

- 现有 `FactSheetService.distinctSources` 以 `url+modelName` 判独立来源（见 `docs/spec/brief-generation.md` §5）；同 provider 多 endpoint 不得提高交叉数的既有约束在此延续。
- 本任务必须等 `10-04-brief-retrieval-sources` 至少 A（Serper provider 契约）落地后再进入实现，避免契约分叉；但可先做设计。
