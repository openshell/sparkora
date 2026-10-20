# PRD — 本地与服务器未跟踪文件治理

## Goal

让本地和开发服务器工作区中的未跟踪内容都有明确分类、责任和去向，同时不删除用户任务记录、不暴露密钥、不破坏共享环境。

## Background

### 本地

```text
?? .agents/
?? .codex/
?? .trellis/tasks/00-join-c-cha/
```

其中：

- `.agents/`、`.codex/` 是本机 Codex/Trellis 工具与代理配置，当前未纳入 Git。
- `00-join-c-cha/` 是 onboarding 任务，由完成态审计子任务处理。

### 开发服务器

`/data/code/sparkora` 当前有 6 个历史未跟踪任务目录：

```text
.trellis/tasks/10-13-img-suggest-auto-load/
.trellis/tasks/10-13-img-suggest-inline-gen/
.trellis/tasks/10-13-img-suggest-speedup/
.trellis/tasks/10-13-img-suggest-top1-adopt/
.trellis/tasks/10-15-car-image-backfill/
.trellis/tasks/10-15-img-drawer-ia/
```

这些目录归属和内容尚未审计，不能直接删除。

## Requirements

### R1 统一分类

每个未跟踪对象必须归入以下类别：

1. 应提交的 Trellis 任务记录。
2. 应归档的历史任务记录。
3. 本机工具/个人配置，应本地忽略。
4. 可迁移的临时草稿，需备份后在仓库外保存。
5. 归属不明，需保留并记录等待进一步确认。

### R2 本地工具目录

- `.agents/`、`.codex/` 默认视为本机工具配置，不提交到团队仓库。
- 使用 `.git/info/exclude` 做本地忽略，不修改共享 `.gitignore`，除非审计证明团队需要共享规则。
- 忽略前必须确认目录中不含应共享的任务、规格或凭据。

### R3 本地任务目录

- `00-join-c-cha` 由 `10-20-trellis-completed-audit` 完成归档并提交。
- 本任务不重复创建或删除该任务目录。
- 若任务记录不应进入仓库，必须记录明确理由。

### R4 服务器任务目录

- 先只读盘点目录内容、task.json 状态、时间和文件数。
- 与本地归档、Git 历史和分支内容比对。
- 有价值且未提交的记录：归档或提交。
- 纯本机临时记录：生成 SHA256 清单和备份归档，再迁移到仓库外明确目录。
- 任何移动前解析绝对路径并确认源/目标都在允许范围。
- 禁止 `rm -rf`、`git reset --hard`、`docker system prune`。

### R5 可复核记录

在 `research/untracked-inventory.md` 中记录：

- 对象路径。
- 文件数量、大小、最后修改时间。
- 分类、判断依据、目标位置。
- 备份路径和校验值（若迁移）。
- 最终 Git 状态。

## Acceptance Criteria

- [x] AC-1 本地与服务器每个未跟踪对象均有分类和去向。
- [x] AC-2 `.agents/`、`.codex/` 已按本机策略排除或明确保留，不进入提交。
- [x] AC-3 `00-join-c-cha` 由完成态审计任务处理，本任务不重复操作。
- [x] AC-4 服务器 6 个目录均已审计，没有直接删除用户数据。
- [x] AC-5 需要迁移的内容已备份、生成校验记录，并移动到仓库外。
- [x] AC-6 本地最终 `git status` 只剩已解释/已忽略项。
- [x] AC-7 服务器最终 `git status` 无无法解释的 tracked 改动或未跟踪任务。
- [x] AC-8 未读取或输出 `.env`、Token、数据库密码和第三方密钥。

## Out of Scope

- 删除 `.env`、Docker 数据卷、媒体暂存和用户草稿。
- 修改产品代码或部署配置。
- 强制推送或修改 Git 历史。
- 把个人本机工具配置提交到共享仓库。

## Dependencies

- `00-join-c-cha` 的处理依赖 `10-20-trellis-completed-audit`。
- 服务器目录治理不依赖产品代码改动，可在只读审计后进行。
