# PRD — Trellis 任务树收尾与工作区治理

## Goal

在不删除用户任务记录、不修改生产数据、不破坏已部署功能的前提下，审计并收口长期停留的 Trellis 任务，完成 BYD 推送安全验收，并治理本地与开发服务器中含义不清的未跟踪文件。

最终目标是让 `task.py list`、本地 `git status` 和服务器 `git status` 都能真实反映剩余工作，而不是用隐藏、删除或强制归档制造“看起来干净”的假象。

## Background

已确认事实（2026-10-20）：

- 当前分支为 `feature/byd-dynasty-miniapp-push`，本地与远端均为 `4f1d967`。
- 本地 tracked 改动仅 `.trellis/tasks/10-16-byd-dynasty-local-push/task.json`，内容是补记 `10-20-miniapp-media-push` 子任务。
- 本地未跟踪：`.agents/`、`.codex/`、`.trellis/tasks/00-join-c-cha/`。
- `10-04-brief-retrieval-sources` 的 4 个子任务均已归档，父任务仍为 planning。
- `10-05-self-hosted-sources` 的 9 个子任务均已归档，父任务仍为 planning，父级 AC 尚未全部勾选。
- `10-12-preview-leftover-cleanup` 仍为 in_progress，已有实现提交 `ba6968b`。
- `10-19-channel-editor-publish` 仍为 in_progress，AC-1～AC-7 已勾选，但仍有部分真机/主观验收事项需要证据化。
- `10-16-byd-dynasty-local-push` 仍有 `10-16-integration-security-validation` 子任务处于 planning。
- 开发服务器当前提交 `4f1d967`，存在 6 个历史未跟踪任务目录。

## Requirements

### R1 统一审计口径

每一项任务必须按以下三类之一处理：

1. 有完整证据：补齐验收记录并归档。
2. 有明确缺口：保持 open，记录缺口、负责人和下一步。
3. 属于本机工具或临时草稿：采用本地忽略、归档或迁移，不能静默删除。

不得仅根据任务标题、最后修改时间或“看起来做完”修改为完成。

### R2 完成态任务审计

由子任务 `10-20-trellis-completed-audit` 审计并处理：

- `00-join-c-cha`
- `10-04-brief-retrieval-sources`
- `10-05-self-hosted-sources`
- `10-12-preview-leftover-cleanup`
- `10-19-channel-editor-publish`

父任务必须核对子任务归档状态和父级验收；in_progress 任务必须先核对实现证据和验证命令。

### R3 BYD 推送安全验收

由子任务 `10-20-byd-push-security-acceptance` 完成原有 `10-16-integration-security-validation` 的 R1～R4、AC-1～AC-8，并将证据落到任务记录。

正常真机推送至少成功一次，已有 2026-10-20 18:35:14 的成功记录可作为候选证据，但必须核对其覆盖范围，不能替代所有异常场景验证。

### R4 未跟踪文件治理

由子任务 `10-20-workspace-untracked-governance` 处理本地和服务器未跟踪内容：

- 本机工具目录优先使用 `.git/info/exclude`，不提交个人工具配置。
- 有价值的 Trellis 任务记录必须归档或提交，不得删除。
- 纯临时草稿经审计后可迁移到仓库外备份目录；任何移动前必须生成清单和备份。
- 开发服务器共享环境不得执行 `git reset --hard`、`docker system prune` 或删除数据卷。

### R5 提交与部署边界

- 每个子任务独立提交、推送，提交信息说明任务归档或治理范围。
- 只涉及 Trellis/文档的改动不重建镜像。
- 若安全验收发现产品代码缺陷，必须另行执行对应测试、构建和部署，并在完成后更新验收记录。
- 不把 `.env`、Token、数据库密码或其他密钥写入仓库或回复。

## Acceptance Criteria

- [ ] AC-1 三个子任务均按真实证据完成；无证据的任务保持 open 并记录缺口。
- [ ] AC-2 `00`、`10-04`、`10-05`、`10-12`、`10-19` 分别完成归档或明确保留。
- [ ] AC-3 BYD 安全验收矩阵覆盖原任务 R1～R4 与 AC-1～AC-8。
- [ ] AC-4 BYD 原有验收子任务和父任务按正确顺序归档，`10-20-miniapp-media-push` 父子关系记录已提交。
- [ ] AC-5 本地与服务器未跟踪文件均有明确去向；无用户数据被删除。
- [ ] AC-6 本地和服务器 `git status` 中剩余项均有可解释状态。
- [ ] AC-7 最终 `task.py list` 只保留真实开放任务，不残留纯管理性僵尸任务。
- [ ] AC-8 所有改动已提交并推送；运行时无变更时明确说明无需重建镜像。

## Out of Scope

- 删除 Trellis 历史任务、数据库、MMKV、媒体暂存或 Docker 数据卷。
- 为“清空 backlog”而降低验收标准。
- 重写历史提交、force push、`git reset --hard`。
- 与本次收尾无关的产品功能重构。

## Dependencies

- 子任务之间无强制先后关系，可独立规划。
- 父任务最终集成评审必须在三个子任务完成后执行。
- 若 BYD 安全验收发现产品代码问题，该子任务完成前不得归档 `10-16-byd-dynasty-local-push`。
