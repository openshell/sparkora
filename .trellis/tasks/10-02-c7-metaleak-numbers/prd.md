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

## Out of Scope

- FactSheet 归并算法本身、禁词表内容策略。
