# Implement — 本地与服务器未跟踪文件治理

## 1. Checklist

- [x] 1. 采集本地 `git status --short --branch` 和未跟踪目录清单。
- [x] 2. 采集服务器 `git status`、6 个目录的文件数/大小/task.json 摘要。
- [x] 3. 写入 `research/untracked-inventory.md`。
- [x] 4. 与归档记录、Git 历史和分支内容比对。
- [x] 5. 决定 `.agents/`、`.codex/` 本地 exclude。
- [x] 6. 确认 `00-join-c-cha` 已由完成态审计子任务处理。
- [x] 7. 对服务器有价值任务记录执行归档/提交。
- [x] 8. 对临时目录生成备份、SHA256，再迁移到仓库外。
- [x] 9. 重新采集本地/服务器 Git 状态，确认无未解释项。
- [x] 10. 提交治理文档并推送。

## 2. Validation

本地：

```powershell
git status --short --branch
git check-ignore -v .agents .codex
Get-Content .git/info/exclude
```

服务器只读盘点：

```powershell
ssh sparkora-dev 'cd /data/code/sparkora && git status --short --branch'
ssh sparkora-dev 'cd /data/code/sparkora && for d in .trellis/tasks/10-13-img-suggest-auto-load .trellis/tasks/10-13-img-suggest-inline-gen; do if [ -d "$d" ]; then printf "%s " "$d"; find "$d" -type f | wc -l; fi; done'
```

迁移/备份前必须额外执行绝对路径校验并记录 SHA256；禁止直接删除源目录。

## 3. Review gates

- 不提交 `.agents/`、`.codex/`。
- 不删除 `.trellis/tasks` 中的任何用户记录。
- 服务器 `.env` 不读取。
- 每个服务器目录都有分类和去向。
- 最终 `git status` 与 inventory 一致。

## 4. Rollback

- 本地 ignore 改动：编辑 `.git/info/exclude`。
- 服务器迁移：按备份清单恢复；先验证 SHA256。
- Git 记录错误：revert 对应提交。
- 绝不使用 `rm -rf`、`reset --hard` 或用 Docker prune 清理。

## 5. Output

- 完整 inventory。
- 本地 exclude 规则。
- 服务器归档/备份位置。
- 最终本地/服务器 Git 状态。

## 6. 执行记录（2026-10-21）

- `.agents/`、`.codex/` 已写入 `.git/info/exclude`，不会进入提交。
- 24 个 Windows Playwright `*-win32.png` 生成物已按显式路径清理。
- 服务器 6 个历史任务目录共 29 个普通文件已备份、完成 SHA256 前后比对并迁移到仓库外；tar 读取校验通过。
- 服务器仍保留另一个 `fix/knowledge-car-sync` 活跃任务的 tracked/untracked 改动，分类为 HOLD，已逐项记录且未移动。
- BYD 推送最终从独立干净 worktree 部署，未覆盖或依赖服务器并发工作区。
