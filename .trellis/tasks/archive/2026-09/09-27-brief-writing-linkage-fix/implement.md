# 简报到写作断链修复与背景题检索补齐 — 实施计划

> 5 项独立，按 R1→R5→R2→R3→R4 顺序（先低风险纯拼接，后改判定/窗口/重试）；每步可独立编译。

## 前置检查

- [ ] `git status` 干净基线（当前 dirty 仅本任务目录）
- [ ] `mvn -q -DskipTests compile` 通过（改动前基线）
- [ ] `mvn test` 基线 **424** 用例全绿

## Step 1: R1 写作消费简报产物（DeepWriterService）

- [ ] 新增 `appendBriefSection(sb,label,json,asArray)` 私有助手（空/`[]`/`{}` 跳过，解析失败原样追加，try/catch 仅 warn）
- [ ] `write()` user prompt 在 clarify_answers 后、末尾指引句前注入：标题候选/核心观点/大纲/事实风险
- 验证：`mvn -q -DskipTests compile`；新增 `DeepWriterServicePromptTest`（AC-01）

## Step 2: R5 webQuery 否定答案过滤（SubAgentRunner）

- [ ] 新增 `NEGATIVE_ANSWER_VALUES` 常量 + `static isNegativeAnswer`
- [ ] `webQuery` 注入循环 `continue` 过滤否定值
- 验证：扩展 `SubAgentRunnerTest`（AC-05）

## Step 3: R2 kbAuthoritative 限参数型（SubAgentRunner + ClarifyService）

- [ ] `ClarifyService` 新增包级静态 `isBackgroundQuestion`（BACKGROUND_TERMS ∪ BACKGROUND_SIGNALS）
- [ ] `SubAgentRunner.research` 的 `kbAuthoritative` 改为 `kbParamAuthoritative = !isBackgroundQuestion && (原判定)`
- 验证：扩展 `SubAgentRunnerTest`（AC-02，断言 `webRouter.search` 调用/未调用）；扩展 `ClarifyServiceTest`

## Step 4: R3 研究窗口保背景题（DeepResearchService）

- [ ] 新增包级静态 `selectResearchWindow(questions, maxAgents)`（背景优先 + 原序稳定 + 预算内补足）
- [ ] `run()` 与 `doRunAsync()` 两处共用：`idx = selectResearchWindow(...)`，按 `idx` 取 `questions`/`toolHints`（索引对齐）
- [ ] `maxAgents` 保持默认 6（Option A；用户已确认）
- 验证：新增 `DeepResearchServiceWindowTest`（AC-03）；回归 `DeepResearchServiceRunTest`/`ProgressTest`

## Step 5: R4 写作提额重试（AiClient + DeepWriterService）

- [ ] `AiClient.ChatResult` 增 `finishReason`（保留 3 参构造器）+ `parseChat` 始终捕获 finish_reason
- [ ] `DeepWriterService.write` 首次 4096 → 截断/异常提额 8192 重试一次 → 仍截断抛 AiException
- 验证：新增 `DeepWriterServiceRetryTest`（断言第二次 maxTokens=8192；首次成功不重试；两次截断抛）；回归 `DeepWriterServiceBatchTest`

## Step 6: 全量验证

- [ ] `mvn -q -DskipTests compile` / `mvn test`（424 + 新增全绿）
- [ ] `npm run build`（预期零前端改动，仅回归）
- [ ] 人工核对 AC-07：无 Flyway 迁移、`/deep/*` 响应结构未变、`research_notes` 字段集未变

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| R1 | 简报字段解析失败引入异常 | 全程 try/catch+warn；空字段退化为旧 prompt |
| R5 | 误过滤有效答案 | 仅精确匹配否定词表 + 「不对比/不需要」前缀；测试锁 |
| R2 | 参数题仍跳过、背景题多付 WEB 成本 | 判定 = 背景题才放行 WEB；测试断言两分支 |
| R3 | 窗口选择与 toolHints 错位 | 索引对齐 + 原序 sort；窗口单测 |
| R4 | 截断判定误报/重复落库 | 仅包裹 AI 调用，insert 一次；测试断言 |

## 提交约定

- `fix(deep): 简报字段注入写作 prompt + 写作截断提额重试`
- `fix(deep): 背景题不被 KB 权威误跳过 WEB + 研究窗口保背景题 + webQuery 否定答案过滤`
- （如需）`docs(spec): 简报到写作链路契约同步`

## start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] Q1（R3 预算）用户已确认（默认 Option A）
- [ ] 用户已确认最终规划摘要
