# C7 元话语根因治理 + verifyNumbers 归一

## Goal

从**数据层**消除元话语泄漏根因（写给作者的指令 vs 给读者的素材在上下文分离），把
`MetaLeakCleaner` 降为最后防线；把 `DeepWriterService.verifyNumbers` 从子串匹配改为数值归一化比对。

## Depends On

- **C1**（prompt 资产化/分层基础）、**C2**（结构化契约）。可在二者之后并行于 C3/C4/C5/C6。

## Requirements

- 父 R9。
- 正文 prompt 只注入**素材**（claim/value）；`fact_risks[].suggestion`、铁律、黑名单归入 system
  "给作者"区并明确标记，**不混入 user 素材上下文**。
- `MetaLeakCleaner` 保留为最后防线，不再作为主机制。
- `verifyNumbers` 改数值归一化比对（千分位/单位/数量级/小数），复用 `ClaimSimilarity.numberValues` 先例，
  消除 `"1200"` 命中 `"12000"` 的漏报。

## Acceptance Criteria

- [ ] `fact_risks[].suggestion` 不再进入正文素材上下文（可断言）。
- [ ] `DeepWriterServicePromptTest`/`MetaLeakCleanerTest` 通过；真实正文抽检泄漏率下降（AC-元话语）。
- [ ] `verifyNumbers` 对 `1200` vs `12000` 用例不再漏报；单位/千分位等价不误报。
- [ ] `mvn test` 绿。

## Technical Notes（勘察，决定实际范围）

- **AC1/AC2 已由前置任务 `10-02-fix-meta-leak-in-article-body` 落地**：
  - `DeepWriterService.buildUserPrompt` 已用 `ReaderViewRules.forbiddenClaimsBlock(...)`（只抽陈述性
    `claim`、丢弃祈使句 `suggestion`）替代旧「事实风险:」整块注入；`DeepWriterServicePromptTest`
    有逐字断言（`suggestion` 原文不得进 prompt、整个 `fact_risks` JSON 不得整体注入）。
  - `MetaLeakCleaner.cleanForPersist` 已是落库前最后防线（清洗致空回退原文），有 `MetaLeakCleanerTest`。
  - → 本任务对 AC1/AC2 以「验证 + 回归锁定」确认，不重复改。
- **AC3 未完成 = 本任务唯一代码交付**：`DeepWriterService.verifyNumbers`（:496-512）仍是子串
  `contains` 匹配，`1200`⊂`12000` 漏报；`20万` vs `200000`、`33.21` vs `33.21%` 误报。
  实现改为复用同包 `ClaimSimilarity.numberValues(String...)` 的数值签名做归一化集合比对。

## Out of Scope

- FactSheet 归并算法本身、`ClaimSimilarity` 的相似度/阈值、禁词表内容策略、`MetaLeakCleaner` 正则内容。
