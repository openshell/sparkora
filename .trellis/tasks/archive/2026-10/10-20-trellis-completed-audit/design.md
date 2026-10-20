# Design — Trellis 完成态审计

## 1. Evidence-first workflow

采用四列表驱动：

| Task | Claimed state | Required evidence | Decision |
| --- | --- | --- | --- |
| task id | planning/in_progress | commit/test/manual proof | archive/keep |

任何一项未满足时不执行归档。

## 2. Task-specific checks

### 00-join-c-cha

- 确认 onboarding 任务无产品交付物。
- 确认当前开发者身份、工作流、任务系统均已实际使用。
- 记录“onboarding 已完成”的人工判断。

### 10-04

- 检查 4 个 children 的 archived task.json 状态。
- 阅读父 PRD 和 check manifest。
- 运行必要的后端测试或引用子任务已验证结果。
- 核对 `docs/spec/brief-generation.md` 与检索契约。

### 10-05

- 检查 9 个 children 的 archived task.json 状态。
- 对 AC-P1～AC-P10 逐项找证据。
- 重点核对采集、域隔离、SOURCE 事实、融合可观测、文档同步。
- 如果某项只有实现没有测试/运行证据，则保持未勾选。

### 10-12

- 对照 PRD R1～R5 检查实现。
- 运行 `npm run build`。
- 运行 `npx playwright test`，记录用例数和失败情况。
- 对双 Toast、字数、localStorage、保存竞态四项分别给结论。

### 10-19

- 核对 AC-1～AC-7 与提交 `dec0bf1` / 合并提交。
- 检查现有真实推送页面、Agent 在线和成功任务记录。
- 浏览器 E2E 如未执行，明确标记；Agent 真机成功至少用页面/任务记录证明。
- prompt 质量若无法自动验收，记录“主观接受/保留”的明确决定。

## 3. Archive order

建议先归档独立轻量任务：

1. `00-join-c-cha`
2. `10-12-preview-leftover-cleanup`
3. `10-19-channel-editor-publish`
4. `10-04-brief-retrieval-sources`
5. `10-05-self-hosted-sources`

父任务最后归档，因为它们依赖子任务完成证据。

## 4. Commit boundaries

- 每项任务归档及其验收文档作为独立 commit，或按同一领域合理合并。
- 提交前用 `git diff --cached --name-only` 确认只包含目标任务。
- 不提交 `task.py archive` 之外的无关文件。

## 5. Rollback

- 归档错误通过 `git revert <commit>` 恢复。
- 若任务目录之前未跟踪，归档前先确认来源和是否应纳入版本管理。
- 不使用 `git reset --hard` 或手工删除任务目录。
