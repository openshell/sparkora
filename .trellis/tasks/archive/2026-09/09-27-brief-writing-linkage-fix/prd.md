# 简报到写作断链修复与背景题检索补齐

## Goal

让「简报」真正成为「正文生成」的参考信息，并让 R2 已补入的「背景/来龙去脉」维度真正获得研究素材；同时补上写作链路唯一缺失的截断重试。五项均为 P0（断链 / 机制性缺陷），目标是在**不改 schema、不改 `/deep/*` 响应主结构、不改 `research_notes` 字段集**的前提下修复。

## Background（已确认事实，含证据锚点）

- **F1 简报→写作断链**：`DeepWriterService.write` 的 user prompt 仅含 `fact_sheet` + `clarify_answers`，末尾仅一句「主题与大纲参考 brief(标题候选/核心观点/大纲)」，但 **`titleCandidates`/`coreViewpoints`/`outline`/`factRisks` 均未实际注入**（`src/main/java/com/sparkora/deep/service/DeepWriterService.java:223-227`）。同一 brief 实体可直接读取这些字段（`ArticleBriefEntity.java:26-30`）。简报是唯一的结构化中间件，却在写作阶段被零引用。
- **F2 背景题被误跳过 WEB**：`kbAuthoritative` 判定（KB 命中 `title` 含 `MODEL_INFO`，或 `snippet` 含「价格区间」）即跳过 WEB 补查（`SubAgentRunner.java:98-101`）。KB query 为「主题+问题」复合语料并带锚点加权（`SubAgentRunner.java:296-303`、`KnowledgeSearchTool`），**车型锚定主题下背景题几乎必然命中该车型 MODEL_INFO → 被误判权威 → 背景题拿不到 WEB 素材**（KB 本身是车型库，不含行业战略类背景内容）。R2 的目标在此被机制性挫败。
- **F3 兜底背景题被截断**：`ensureBackgroundQuestion` 把兜底背景题 **append 到 `keyQuestions` 尾部**（`ClarifyService.java:238-244`）；`DeepResearchService` 按 `min(questions.size(), maxAgents)` **截前 N 条**（`DeepResearchService.java:119`、`205`）。宽主题（7 条 LLM 题 + 1 条兜底 = 8）时被截掉的恰是尾部，**兜底背景题最先阵亡**。
- **F4 写作无截断重试**：`DeepWriterService.write` 单轮 `aiClient.chat(system, user, 4096)`，无提额重试（`DeepWriterService.java:228`）。R4/R6 已为子代理汇总、深度简报建立「失败提额重试一次」范式（`SubAgentRunner.chat` 2048→4096；`BriefService.generateFromFactSheet` 8192→16384），**正文是全链路最长输出，却是唯一没有重试的 AI 调用**。
- **F5 webQuery 拼入否定答案**：`webQuery` 拼入全部非空锁定答案（`SubAgentRunner.java:186-196`、`lockedAnswerValues` 199-213），无否定值过滤 → 用户选的「不对比」等否定答案污染搜索 query，制造噪声。
- **F6 研究窗口截断面**：`maxAgents` 默认 6（R5，`DeepProperties`/`application.yml`/`.env.example` 三处），研究计划 keyQuestions 3~7（R2）+ 兜底 ≤1 → 最多 8；宽主题下截断会丢 ≥2 个维度。

## Requirements

- **R1 写作消费简报产物**：`DeepWriterService.write` 将同 brief 的 `titleCandidates`、`coreViewpoints`、`outline`、`factRisks` 显式拼入写作 prompt，使简报字段成为正文的明确输入（而非空话）。兼容历史 brief：字段缺失/空时行为退化为现状（不报错、不阻断）。
- **R2 kbAuthoritative 仅对参数型问题生效**：只有「事实/参数型」问题在 KB 命中车型域权威块时才允许跳过 WEB；「背景/来龙去脉型」问题不得因 KB 命中 MODEL_INFO 而跳过 WEB。复用 R2 已有信号词表（`ClarifyService.BACKGROUND_SIGNALS`）做问题类型判定。
- **R3 背景题防截断**：研究计划截断到 `maxAgents` 时，背景型问题必须保有研究窗口（不被尾部截断丢弃）。
- **R4 写作提额重试**：`DeepWriterService.write` 的 AI 调用对齐 R4/R6 范式——首次失败（截断/空/异常）提额重试一次，仅两次均失败才向上抛（由 `runBatch` 计入部分/整体失败）。
- **R5 webQuery 否定答案过滤**：构造 WEB query 时剔除语义为「放弃/无偏好」的否定性答案值（如「不对比」「无所谓」「都可以」「不限」等），正常答案继续注入。

## Acceptance Criteria

- [ ] **AC-01（R1）**：给定含 `titleCandidates`/`coreViewpoints`/`outline`/`factRisks` 的 brief，`DeepWriterService.write` 发出的 user prompt 实质包含这些字段内容（单测断言 prompt 串含标题候选、核心观点、大纲要点、风险条目）；字段为空的 brief 不抛异常且 prompt 与旧行为兼容。
- [ ] **AC-02（R2）**：背景型问题（含背景信号词）+ KB 命中车型域权威块时，WEB 检索**仍被调用**；参数型问题在同样条件下 WEB **仍被跳过**（单测断言 `WebSearchRouter` 调用/未调用）。
- [ ] **AC-03（R3）**：`keyQuestions` 数 > `maxAgents` 且含背景型问题时，实际进入研究的窗口包含背景型问题（单测断言截断后问题集合含背景题）。
- [ ] **AC-04（R4）**：`write` 首次 AI 调用失败（如截断 `AiException`）→ 提额重试一次并成功落版本；两次均失败 → 抛出使 `runBatch` 计入失败（单测断言第二次使用更高额度；首次成功路径不触发重试）。
- [ ] **AC-05（R5）**：锁定答案含否定值（如「不对比」）时，`webQuery` 不含该否定值；正常答案值仍被注入（单测断言）。
- [ ] **AC-06**：`mvn -q -DskipTests compile` 通过；`mvn test` 全绿（基线 308 用例 + 本任务新增），无回归。
- [ ] **AC-07**：不改数据库 schema（无新增 Flyway 迁移）；不改 `/deep/*` 响应主结构、`research_notes` 字段集与状态值域、`SearchTool.available()` 语义与 `toolHealth` 三键优先级。

## Out of Scope（P1/P2，另立任务）

- CRAWL4AI 正文抓取、WEB quota 按问题类型差异化分配。
- `FactSheet` 条目增加 `kind` 分类；简报注入 `research_plan.hypotheses`。
- `outline` 增 `facts`/`targetWords` 结构绑定（改简报 JSON schema，需重生成简报）。
- gap 回流二轮研究、简报后验自检、素材利用率观测。

## Open Questions

- **Q1（成本边界，待用户决策）**：R3 的落法——在既有 `maxAgents=6` 预算内优先保背景题（可能牺牲 1~2 条参数题的研究），还是放宽 `maxAgents`（多付外部检索成本、全覆盖）？
