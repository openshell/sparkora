# 深度研究素材覆盖与降级补齐

## Goal

根治「深度研究给到大模型的素材缺失、知识面不全」:让每个研究问题在 LLM 汇总降级时仍保留搜索命中正文(snippet),并让研究计划覆盖事件背景/企业战略等「面」维度,同时让降级对用户可见。

## Background（实测证据，只读）

对象:project 51 / brief 65（2026-09-26，主题「如何看待比亚迪宣布建成第2000座高速闪充站」=「最新这篇」），版本 40 正文不含「2万座」「闪充中国」，仅 1497 字。

| 事实 | 证据 |
|---|---|
| 搜索**没有丢** | brief 65 四个 agent 的 Tavily attempt 均 `ok=true resultCount=2`（`research_notes[].search.attempts`） |
| 但 3/4 agent 降级 | brief 65：agent1-3 `status=FALLBACK`、`search.fallbackReason=LLM_FALLBACK`；agent4 `DONE`（对照 brief 64 仅 1 个 FALLBACK） |
| 降级丢弃正文 | `SubAgentRunner.rawFallback`（`SubAgentRunner.java:321-337`）claim 只取 `h.title()`，**丢弃 `h.snippet()`**；而关键背景（年内 2 万座、含这 2000 座）通常写在 snippet 正文里 |
| 事实手册缺失 | brief 65 `fact_sheet` 含「2万座」计数 = **0**；同题旧 brief 64 手册第 2 条 = 「比亚迪计划2026年底前建成2万座闪充站，其中包含2000座高速闪充站」 |
| 计划无背景维度 | `ClarifyService` 计划 prompt（`ClarifyService.java:165-185`）只要求 3-4 条「具体可查」keyQuestions，无「背景/战略/长期目标」要求；brief 65 四问全为技术参数/建设速度/对比/影响 |
| query 单发无变体 | `SubAgentRunner.webQuery`（`SubAgentRunner.java:186-196`）= 主题 + 问题 + 锁定答案标签，每个问题仅一次查询 |
| 降级对用户不可见 | `ResearchProgress.vue:100-111` 仅读 `attempts[].ok===false` 的 `fallbackReason`；LLM 降级原因在 `search` 层（attempts 全部 ok=true），故进度页对 3/4 降级**不显示任何原因** |
| LLM 截断不重试 | `SubAgentRunner.chat`（`:305-318`）仅在 JSON 解析失败时重试一次；`AiClient.parseChat(requireJson=true)` 在 `finish_reason=length` 时抛 `AiException`（`AiClient.java:189-190`），**截断直接冒泡 → FALLBACK**，无任何重试 |
| KB 停用放大影响 | 全局 KB 停用，WEB 是唯一资料源；单 WEB 源置信 0.4 + 待核实 |

## Requirements

- R1(P0) LLM 汇总降级(`rawFallback`)不得丢失搜索命中正文:保留 snippet 作为该命中的证据/摘要,使降级路径仍向事实手册提供可用素材。
- R2(P1) 研究计划覆盖事件背景/企业战略/长期目标等「面」维度(与「点」型事实问题并列)。
  - 决策 A(已批准):LLM 判断为主 + 确定性信号词兜底,不强制所有主题。prompt 明确列出两类维度(事实/参数型 + 背景/来龙去脉型)并授权模型按主题取舍;后置检查——主题命中「发布/宣布/建成/战略/计划/政策/规划/里程碑」等信号词却无背景型问题时,自动补一条。范式对齐现有 `ClarifyService.normalizeQuestions`(竞品题 prompt 引导 + 信号词兜底,`ClarifyService.java:206-246`)。
- R3(P2) LLM 汇总降级原因须对用户可见(进度页展示 `LLM_FALLBACK` 等 search 层降级原因)。
- R4(P2) LLM 汇总调用因 `max_tokens` 截断时先重试/提额,再降级(降低 FALLBACK 发生率)。
- R5(已纳入，决策 D 已批准) 检索广度来自**动态数量的独立子代理**:计划按主题复杂度产出问题数(3-7),每个问题一个独立子代理各自搜索+总结,汇总时天然可跨源交叉验证;`maxAgents` 由固定上限 4 放宽为可配护栏(默认 6)。
  - 不做每子代理多 query 变体(方案 A/A+D 未选);不做固定 4 子代理。
  - 成本护栏:总检索预算仍约 8(`webQuotaPerAgent = max(1, 8/n)`),agent 增多时每 agent 减量;LLM 汇总次数随 agent 数上升,受 `maxAgents` 约束。

## Acceptance Criteria

- [ ] AC-01 构造 `rawFallback` 命中含 snippet 时,产出的事实/证据包含该 snippet 正文(非仅标题);含小数/引号/换行的 snippet 仍产出合法 JSON。
- [ ] AC-02 新研究计划至少包含一条背景/战略/长期目标型 keyQuestion(或等价 `backgroundQuestion` 字段)。
- [ ] AC-03 进度页对 `search.fallbackReason=LLM_FALLBACK` 的 agent 显示降级原因(文案明确为「汇总降级」)。
- [ ] AC-04 LLM 汇总 `finish_reason=length` 时触发一次提额/重试;仅重试仍失败才落 FALLBACK。
- [ ] AC-05 既有契约不回归:`available()` 无闩锁、`toolHealth` 三键与优先级、`/deep/*` 响应结构、`research_notes` 字段集。
- [ ] AC-06 `mvn -q -DskipTests compile` / `mvn test` 全绿;前端改动 `npm run build` 通过。
- [ ] AC-07 文档同步:`docs/spec/brief-generation.md`、`.trellis/spec/backend/ai-rag-guidelines.md`、必要时 `docs/spec/settings.md`。

## Out of Scope

- 引入 Crawl4AI 正文抓取、语义 reranker、新检索源。
- 双源并行付费聚合(BOTH 策略)。
- 项目级/用户级搜索配置。
- 搜索运行历史表、完整指标平台。

## Decisions (已批准)

- R2 背景维度:决策 A —— LLM 判断为主 + 确定性信号词兜底(不强制所有主题)。
- R5 检索广度/子代理数:决策 D —— 动态子代理数(计划产出 3-7 个独立问题),`maxAgents` 默认放宽到 6。

## Open Questions

- 无(所有阻塞性产品决策已解决)。
