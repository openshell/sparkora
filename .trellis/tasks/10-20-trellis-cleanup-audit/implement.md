# Implement — Trellis 任务树收尾与工作区治理

## 1. 执行顺序

- [x] 1. 记录起点：当前分支、HEAD、本地/服务器 `git status`、`task.py list`。
- [x] 2. 完成 `10-20-trellis-completed-audit`。
- [ ] 3. 完成 `10-20-byd-push-security-acceptance`（证据、部署和界面缺陷已完成；Token TTL、RUNNING 租约/reaper、create-job 幂等键仍为真实缺口）。
- [x] 4. 完成 `10-20-workspace-untracked-governance`，已归档到 `archive/2026-10/`。
- [x] 5. 父任务当前审计：完成态任务和未跟踪治理已收口；BYD 安全验收保留 open，没有强行归档。
- [x] 6. 产品提交 `d183ca3`、`0c2d949` 已推送；BYD 从独立干净 worktree 部署并健康检查通过，服务器并发工作区未被覆盖。
- [ ] 7. 因安全验收仍有真实缺口，父任务暂不能最终归档；已更新证据并保留后续工作。

## 2. 子任务进入门禁

每个子任务启动前必须：

- [ ] `prd.md`、`design.md`、`implement.md` 已存在且无阻塞问题。
- [ ] `implement.jsonl` / `check.jsonl` 至少各有一条真实 spec 或 research 上下文。
- [ ] 用户已明确批准最终规划摘要。
- [ ] 记录当前 HEAD，确认不会覆盖工作区无关修改。

## 3. 子任务交付物

### 完成态审计

- research 证据矩阵。
- 任务归档记录与必要验收更新。
- 对保留任务列出真实缺口。

### BYD 安全验收

- R1～R4 / AC-1～AC-8 证据矩阵。
- 后端、Agent、前端测试结果。
- 真机成功记录与未覆盖风险。
- 原有 BYD 子任务/父任务及本任务的归档顺序记录。

### 未跟踪治理

- 本地/服务器文件清单。
- 每项的分类、去向和理由。
- 本地 exclude 或迁移备份清单。
- 最终 `git status` 对比。

## 4. 验证命令

按改动范围选择：

```powershell
git status --short --branch
git diff --check
python ./.trellis/scripts/task.py list
python ./.trellis/scripts/task.py list-context <task>
python ./.trellis/scripts/task.py validate <task>
```

后端：

```powershell
$env:JAVA_HOME = 'C:\Program Files (x86)\Common Files\JDK\jdk-21'
mvn test
```

Agent：

```powershell
Set-Location agent/byd-dynasty
python -m pytest -q
Set-Location ../..
```

前端：

```powershell
Set-Location frontend
npm run build
npx playwright test
Set-Location ..
```

服务器只读验收：

```powershell
ssh sparkora-dev 'cd /data/code/sparkora && git status --short --branch && docker compose ps'
```

## 5. 风险与回滚点

| 风险 | 防护 |
| --- | --- |
| 误归档未完成任务 | 必须有证据矩阵；缺证据保持 open |
| 误删用户数据 | 禁止删除；只归档、忽略或备份迁移 |
| 提交无关文件 | 每步使用明确路径暂存并检查 `git diff --cached` |
| 测试污染真实数据 | 使用 Mock/临时数据；不改既有生产行 |
| 服务器共享工作区冲突 | 先预检，出现 tracked 改动立即停止 |
| 产品代码回归 | 全量测试、check 子代理、必要时按部署规范重建 |

## 6. 最终门禁

- [x] `task.py list` 无僵尸完成态任务；完成态审计与未跟踪治理已归档。
- [x] 本地/服务器未跟踪项均有记录；服务器并发任务项为已解释 HOLD。
- [ ] 所有归档提交已推送；BYD 安全验收未完成，父任务不能归档。
- [x] 无未说明的代码改动；本地产品修复均已分提交。
- [x] 产品代码已完成后端 1248 测试、Agent 60 测试、前端构建、Playwright 9 项定向测试、镜像部署及健康检查。

## 7. 执行记录（2026-10-21）

- `10-20-trellis-completed-audit` 已归档。
- `10-20-workspace-untracked-governance` 已完成并归档；服务器 6 个历史任务目录已备份迁移，本地工具目录已本地忽略。
- BYD 页面历史草稿 ID 与草稿数量显示问题已修复：`d183ca3`、`0c2d949`，并已推送、构建、部署和页面复验。
- `10-20-byd-push-security-acceptance` 保持 open；不得用归档掩盖 Token TTL、RUNNING 租约/reaper、create-job 幂等键和真实草稿箱人工核对缺口。
