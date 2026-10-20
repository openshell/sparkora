# Design — Trellis 任务树收尾与工作区治理

## 1. Architecture

本任务采用父任务协调、三个独立子任务执行的结构：

```text
10-20-trellis-cleanup-audit
├── 10-20-trellis-completed-audit
├── 10-20-byd-push-security-acceptance
└── 10-20-workspace-untracked-governance
```

父任务不直接修改产品代码，负责：

- 固定清理口径与安全边界。
- 审核三个子任务证据。
- 执行最终 `task.py list`、Git 状态和部署状态验收。
- 自身完成后归档。

## 2. Decision Matrix

| 对象 | 判断 | 动作 |
| --- | --- | --- |
| 子任务全部归档且父级 AC 有证据 | 完成 | 更新证据、归档 |
| 实现存在但测试或真机证据缺失 | 未完成 | 保持 open，记录缺口 |
| 任务仅为 onboarding/管理性质 | 不再需要 | 记录完成并归档 |
| 本机 Codex 工具目录 | 非团队共享产物 | `.git/info/exclude` |
| 历史 Trellis 任务记录 | 可能含审计价值 | 审计后归档/提交 |
| 纯临时任务草稿 | 无仓库价值 | 备份后迁移到仓库外 |
| 服务器共享目录中的未知记录 | 归属不明 | 不删除，先盘点并比对 |

## 3. Evidence Contract

每个待归档任务必须有可复核链接：

```text
task.json / prd.md / implement.md / check.jsonl
+ 实现提交或明确“无代码”理由
+ 验证命令与结果
+ 未覆盖项或人工验收结论
```

“归档”只改变任务状态和位置，不能替代验收证据。

## 4. Compatibility and Safety

- `.git/info/exclude` 是本地文件，不进入提交。
- 归档操作使用 `task.py archive`，不手工删除任务目录。
- 服务器文件移动前必须解析绝对路径、确认仍在 `/data/code/sparkora` 或明确备份目录内。
- 不读取或输出服务器 `.env`。
- 不在生产数据行上做测试；真实联调只使用新建测试数据或只读检查。
- 若发现产品缺陷，沿用现有测试、Trellis check、提交和部署流程。

## 5. Commit Boundaries

建议提交拆分：

1. `chore(task): audit and archive completed Trellis tasks`
2. `chore(task): close BYD push security acceptance`
3. `chore(workspace): document untracked task governance`
4. `chore(task): archive trellis cleanup audit`

如单个子任务包含多个独立归档，也可按任务分别提交，但不得把无关脏文件混入。

## 6. Rollback

- Trellis 归档错误：使用 `git revert` 恢复对应提交，任务目录随提交恢复。
- 文档/忽略规则错误：revert 对应提交；`.git/info/exclude` 可手工回退。
- 服务器迁移错误：从备份清单对应位置恢复，禁止使用递归删除。
- 产品代码若经批准修改，按容器部署指南保留部署前提交并在失败时回滚。
