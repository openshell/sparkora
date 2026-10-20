# PRD — Trellis 完成态审计、验收与归档

## Goal

用可复核证据收口 `00-join-c-cha`、`10-04-brief-retrieval-sources`、`10-05-self-hosted-sources`、`10-12-preview-leftover-cleanup`、`10-19-channel-editor-publish`，让任务状态重新反映真实完成度。

## Background

### 00-join-c-cha

由 `trellis init` 生成，属于 onboarding 任务；当前开发者已长期参与项目、规划和部署，任务本身通常不再需要单独实施。

### 10-04-brief-retrieval-sources

父任务仍为 planning，4 个子任务均已归档：

- `10-04-serper-provider`
- `10-04-web-fanout-merge`
- `10-04-web-followup-budget`
- `10-05-tavily-endpoint-priority`

父任务承担跨子任务最终集成评审，不能仅因子任务归档而自动关闭。

### 10-05-self-hosted-sources

父任务仍为 planning，9 个子任务均已归档。PRD 中 AC-P1～AC-P10 尚未勾选，需要逐项核对证据。

### 10-12-preview-leftover-cleanup

任务为 in_progress，存在实现提交 `ba6968b`。PRD 要求前端构建和 44 条 Playwright 用例证据，并包含少量真机交互验收。

### 10-19-channel-editor-publish

任务为 in_progress，PRD AC-1～AC-7 已勾选。验收记录写明仍有浏览器端到端、Agent 真机推送和 prompt 主观质量需要证据化或明确接受。

## Requirements

### R1 证据矩阵

为每项任务记录：

- 当前状态与创建/完成时间。
- 实现提交和关键文件。
- 验收标准、测试命令和结果。
- 已覆盖项、未覆盖项、人工判断项。
- 处理结论：归档；或保持 open 并列出缺口。

证据矩阵写入 `research/evidence-matrix.md`。

### R2 完成态判断

- `00`：确认 onboarding 价值已实现后归档。
- `10-04`：核对全部子任务归档、父 PRD 的最终集成要求、相关测试和文档。
- `10-05`：逐项核对 AC-P1～AC-P10；没有证据的项保持未完成，不得批量勾选。
- `10-12`：运行前端构建和 Playwright；核对 R1～R5 实现；真机项无证据时如实记录。
- `10-19`：核对 AC-1～AC-7、实现提交、当前 BYD 真机成功记录；主观 prompt 项需给出接受或保留理由。

### R3 归档规则

- 仅在证据满足任务验收时执行 `task.py archive`。
- 父任务归档前确认所有 children 已归档。
- 归档提交不得混入 `.agents/`、`.codex/`、服务器草稿或无关任务文件。
- 若发现产品缺陷，另建/记录修复项，不在本任务偷偷改业务代码。

## Acceptance Criteria

- [ ] AC-1 五项任务均有证据矩阵和处理结论。
- [ ] AC-2 `00-join-c-cha` 已完成并归档，或记录继续保留的具体理由。
- [ ] AC-3 `10-04`、`10-05` 的父级验收已按证据核对，未用子任务归档代替父级验收。
- [ ] AC-4 `10-12` 至少提供 `npm run build` 与 Playwright 结果；真机项有结论。
- [ ] AC-5 `10-19` 的浏览器 E2E、Agent 真机推送和 prompt 质量项均有证据或明确接受记录。
- [ ] AC-6 所有满足条件的任务均已归档；不满足条件的任务保留并列出缺口。
- [ ] AC-7 归档/验收文档变更已提交并推送。
- [ ] AC-8 本任务未删除任何任务记录或用户数据。

## Out of Scope

- 为关闭任务而放宽验收标准。
- 删除历史归档。
- 修改与本审计无关的产品代码。
- 替其他负责人臆造验收证据。

## Dependencies

- 无前置子任务。
- 若发现需要产品修复，交由对应负责人/后续任务处理；本任务只记录阻塞。
