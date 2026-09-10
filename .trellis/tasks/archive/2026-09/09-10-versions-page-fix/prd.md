# 版本页下一步按钮缺失与显示问题修复

## Goal

修复主题创作深度生成链路的三个显示/交互问题：版本页无「下一步」入口、版本头部显示 undefined·undefined、字数统计为空。根因均在**深度单版生成链路（DeepWriterService / DeepController）与多版本链路（VersionService）的字段/状态不对齐**。

## 根因（实测项目 34 + DB/接口验证）

| # | 现象 | 根因 |
|---|---|---|
| 1 | 版本页无下一步按钮 | `DeepController.generate → DeepWriterService.write` 落版本后**不推项目状态机**（停在 READY）、不设 `current_version_id`；前端 `StepVersions` next-row 仅在 `VERSIONS_READY` 显示「再生成其他风格」，步骤导航 `maxReachableStepOf(READY)=1` 锁定预览步 → 用户无任何进预览的入口 |
| 2 | undefined·undefined | 版本头部/汇总条/对比选项渲染 `v.versionLabel`/`v.styleTag`（DB 为 NULL）→ 显示 `null·null`/`undefined` 形态；header「当前：undefined·undefined」来自 `currentVersionLabel` |
| 3 | 字数统计为空 | `word_count` NULL（同上，深度链路漏填） |

**数据证据**：`sparkora_article_version` 中 id 16/21/22/23/24（S9 深度链路产物）的 `version_label/style_tag/word_count/title` 全 NULL；FAST 时代产物（id ≤20）字段齐全。`DeepWriterService.write` 自 S9(8432093) 起就未填这 4 个字段，2026-09-09 模式收敛（FAST 封死、深度成唯一路径）后该缺陷成为主路径必现。

## Requirements

### R1 深度生成对齐多版本链路语义（后端）
- `DeepWriterService.write` 落版本时补齐：`title`（AI 正文首个 Markdown H1，缺失回退 project.topic）、`version_label`（本项目版本序 A/B/C…按现有版本数续编）、`style_tag`（风格名，由调用方传入或按 stylePrompt 来源解析；拿不到风格名时回退「深度」）、`word_count`（contentMd.length，与 VersionService 口径一致）。
- 生成成功后推进状态机（对齐 `VersionService.generate` 语义）：`READY → VERSIONS_READY`、`current_version_id` 首版设为当前（追加生成不覆盖用户已选的 current）、`last_version_error` 不动。状态推进放在 `DeepController.generate` 成功分支（service 保持纯写作职责亦可，实现时定，以最小侵入为准）。
- 不改仿写链路（`generateVersions` 例外放行路径已正确）。

### R2 前端显示防御（frontend）
- `StepVersions.vue`：`versionLabel/styleTag/wordCount` 为空时的兜底显示（`v.versionLabel || '—'` 等）；`currentVersionLabel` 对 null 字段兜底（如 `styleTag || '深度'`、无 label 时显示「第 N 版」或风格名）。
- `StepVersions.vue` next-row：`VERSIONS_READY` 或「存在版本且状态 READY」时显示「进入预览 →」按钮（深度单版生成后状态仍 READY 也能走下一步；修复后 R1 已推 VERSIONS_READY，此条为防御 + 兼容存量 READY 项目）。
- 头部「当前：」meta 与 chips 的 null 值不再出现 undefined/null 字样。

### R3 存量数据修复（幂等 SQL，进 schema.sql 或一次性执行）
- 已存在的 NULL 字段版本行回填：`version_label` 按项目内创建序补 A/B/C…、`style_tag` 补「深度」、`word_count` 按 `length(content_md)` 回填、`title` 空时按 `left(content_md 首行/项目 topic)` 回填。
- 写进 `schema.sql` 幂等段（启动自动执行）。

## Acceptance Criteria

- [ ] AC1 深度生成正文后：项目状态 READY → VERSIONS_READY，`current_version_id` 指向新版本；版本页 next-row 出现「进入预览 →」且步骤导航解锁预览步。
- [ ] AC2 新生成的深度版本 `version_label/style_tag/word_count/title` 全部非空；版本页头部显示「共 N 版 · 当前：A·深度」类正常文案，无 undefined/null 字样。
- [ ] AC3 存量 NULL 版本行（id 16/21/22/23/24）启动回填后显示正常。
- [ ] AC4 追加生成（第二次深度生成）不覆盖用户手动设置的 currentVersionId；首生成设默认当前。
- [ ] AC5 仿写链路（generateVersions）行为不变（回归）；FAST 410 封死语义不变。
- [ ] AC6 `mvn -q -DskipTests compile` 与 `npm run build` 通过。

## Out of Scope

- 相似度/去图等仿写逻辑；StepBrief 深度面板流程；状态机常量结构（仅后端推进行为修正）。