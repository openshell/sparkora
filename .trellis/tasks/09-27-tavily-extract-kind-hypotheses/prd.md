# Tavily 正文补抓 + 事实手册分类 + 简报假设注入

## Goal

提升「研究素材 → 简报 → 正文」的信息深度与组织度（P1 质量增强），三项独立可验收：

1. **正文补抓**：背景题的 WEB 命中获取**正文片段**（而非仅摘要），充实行业背景/战略/政策等「面」型素材。
2. **事实手册分类**：`fact_sheet` 条目带 `kind`（param/background），写作与简报 prompt 可按类分组呈现、分规则约束。
3. **简报假设注入**：简报生成注入 `research_plan.hypotheses`，使 `coreViewpoints` 显式回应「假设被证实/推翻」，观点具备论证结构。

## Background（已确认事实，含证据锚点）

- **F1 · snippet 双层截断**：进入 LLM 上下文与降级产物的文本被 `SubAgentRunner.snippet()` 截到 **200 字**（`src/main/java/com/sparkora/deep/service/SubAgentRunner.java:380`）；写作阶段再对条目 snippet 截 200（`DeepWriterService.java:204-207`）。`WebResultNormalizer` 只做 URL 治理、不改 snippet（`WebResultNormalizer.java:39-56`）。
- **F2 · Tavily 只取摘要、无正文**：`TavilySearchTool` 固定 `search_depth=basic`、不带 `include_raw_content`，取 `content` 作片段（`TavilySearchTool.java:64-76`）；`SearchTool.SearchHit` 只有单一 `snippet` 字段（`SearchTool.java:34-44`）。而 Tavily `content` 默认已含最多 3 段 ×500 字（见 `research/tavily-api-capabilities.md`）——**素材薄的成因一半是自截断**。
- **F3 · 背景题判定已就绪**：`ClarifyService.isBackgroundQuestion(question)`（词表 `BACKGROUND_TERMS ∪ BACKGROUND_SIGNALS`，`ClarifyService.java:229-233`）已于 P0 用于「KB 权威不误跳 WEB」（`SubAgentRunner.java:104`），R1/R3 直接复用，不新增词表。
- **F4 · 手册条目无分类、且丢失问题关联**：`fact_sheet` entry 无 `kind`（`FactSheetService.java:138-154`）；`merge` 从 notes 展开 facts 时只按 claim 聚类，未保留「该 fact 来自哪个研究问题」（`FactSheetService.java:39-53`），故无法判断参数型/背景型。
- **F5 · hypotheses 丢在 research_plan 里**：`research_plan` 含 `hypotheses`（`ArticleBriefEntity.java:39`），但简报 `buildDeepBriefUserPrompt` 只传 topic + answers + fact_sheet（`BriefService.java:151-158`），**假设从不进入简报生成**。
- **F6 · 写作逐条平铺**：写作 prompt 按手册条目顺序平铺，不区分参数事实与背景素材（`DeepWriterService.java:197-209`）。

## Requirements

- **R1 · Tavily 正文获取（工具层，机制 B）**：`SearchTool` 增 `default extract(urls, query)` 能力（默认空，Tavily 覆写为 `POST /extract`）；`SubAgentRunner` 背景题且 WEB 命中时，对 top 1–2 URL 调 `extract` 取 `raw_content` 作正文，工具层**截断**（上限可配，默认约 2000 字）；抽取失败/空（`failed_results`/空 `results`）/Tavily 不可用 → `content=null` **降级回摘要**，不抛异常、不丢失既有命中。
- **R2 · 正文独立载体字段**：`SearchTool.SearchHit` 与 `WebResultNormalizer.WebHit` 新增独立正文载体（不复用 `snippet` 语义——引用/预览仍用 `snippet`）；保留旧构造器（向后兼容）；`SubAgentRunner.rawFallback` 降级产物同步携带正文且 JSON 转义完整；研究笔记 facts 结构为**增量嵌套字段**（不改 `research_notes` 顶层字段集与状态值域）。
- **R3 · 注入按问题类型分档**：背景型问题注入正文（上限 `maxContentChars`），参数型问题不注入或极少注入；判定复用 F3。
- **R4 · 手册条目 `kind` 分类**：`fact_sheet` entry 增增量字段 `kind`（`param`/`background`），取值继承**产出该 fact 的研究问题类型**（F3 判定）；无问题关联信息或缺省 → `param` 兜底；历史 `fact_sheet`（无 kind）消费方不得报错。
- **R5 · 写作按 kind 分组**：写作 prompt 将「参数事实（逐字引用）」与「背景素材（叙事用，不得据此新增数值）」分组呈现；缺 kind 时退化为现有平铺行为。
- **R6 · 简报注入 hypotheses**：简报生成 user prompt 注入 `research_plan.hypotheses`，system prompt 要求 `coreViewpoints` 回应假设是否被手册证实/推翻；`research_plan` 缺失或无 hypotheses 时**兼容退化**（不报错）。

## Acceptance Criteria

- [ ] **AC-01（R1）**：Tavily `/extract` 单测（mock HTTP）——正文非空且 ≤ 配置上限；`failed_results`/空 `results`/异常 → 降级回摘要且不抛；Tavily 不可用或非背景题 → 不触发 `extract`、行为不回归。
- [ ] **AC-02（R1/R2）**：`SearchHit`/`WebHit` 新字段与旧构造器兼容（既有测试不改仍通过）；`rawFallback` 输出含正文且可被 JSON 解析；研究笔记 facts 顶层结构不变（仅嵌套增量）。
- [ ] **AC-03（R3）**：背景题事实生成 prompt 含正文片段、参数题不含（或受更小上限约束）；正文超限被截断。
- [ ] **AC-04（R4）**：同一问题下产出的 fact 聚成条目后 `kind` 与问题类型一致（背景题→background，参数题→param）；无问题信号 → `param`；未带 kind 的历史条目被消费时不抛异常。
- [ ] **AC-05（R5）**：写作 prompt 出现按 kind 分组的两段（参数组/背景组），条目归属正确；全无 kind 时与旧 prompt 等价。
- [ ] **AC-06（R6）**：简报 prompt 含 `research_plan.hypotheses` 内容，且 system prompt 含「观点须回应假设」要求；`research_plan` 为 null/无 hypotheses 时不报错、行为退化为现状。
- [ ] **AC-07**：`mvn -q -DskipTests compile` 通过；`mvn test` 全绿（基线 308 用例 + 本任务新增）。
- [ ] **AC-08（红线）**：不改数据库 schema（无新增 Flyway 迁移）；不改 `/deep/*` 响应主结构、`research_notes` 顶层字段集/状态值域、`SearchTool.available()` 语义与 `toolHealth` 三键优先级；不新增密钥；`webCount`/`search.resultCount` 口径不变。

## Out of Scope（另立任务）

- CRAWL4AI 接入（Tavily 已覆盖正文能力，见 `research/tavily-api-capabilities.md`）。
- WEB quota 按问题类型差异化分配（P1-7）。
- `outline` 增 `facts`/`targetWords` 结构绑定（P1-10，需改简报 JSON schema）。
- gap 回流二轮研究、简报后验自检、素材利用率观测。
- 前端 `FactSheetSummary` 按 kind 分组展示（可作后续小任务）。

## Open Questions

- （无。Q1 正文获取机制已确认为 **B：`search` + 按需 `/extract`**，见 R1。）
