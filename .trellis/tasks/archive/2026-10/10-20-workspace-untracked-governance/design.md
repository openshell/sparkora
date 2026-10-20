# Design — 本地与服务器未跟踪文件治理

## 1. Inventory schema

`research/untracked-inventory.md` 使用以下字段：

```text
path | type | files | size | modified | classification | evidence | action | destination/checksum
```

分类枚举：

```text
TRACK   -> 提交到仓库
ARCHIVE -> 移入 Trellis archive 并提交
LOCAL   -> .git/info/exclude
MIGRATE -> 备份后移出仓库
HOLD    -> 保留待确认
```

## 2. Local policy

### .agents / .codex

现状：未跟踪、无 Git 历史、属于本机工具/代理配置。

推荐动作：

```text
.git/info/exclude
```

理由：

- 不污染团队仓库。
- 不修改共享 `.gitignore` 的行为。
- 换机器时由各自初始化。

### 00-join-c-cha

由 `10-20-trellis-completed-audit` 处理。若归档后应保留 onboarding 历史，则随该子任务提交；否则记录明确理由。

## 3. Server policy

服务器是共享开发环境，默认只读盘点：

1. 使用 `find`/PowerShell 等价只读命令读取目录结构与 task.json。
2. 用 `sha256sum` 生成清单。
3. 与本地归档和 Git 历史比对。
4. 对有价值记录：
   - 若 task.json 已 completed：归档并提交。
   - 若仍 open：保留并记录缺口。
5. 对临时记录：
   - 创建仓库外备份目录，例如 `/data/code/sparkora-task-archive/2026-10-20/`。
   - 生成 tar.gz 和 SHA256。
   - 验证备份内容后再移动源目录。
   - 不执行递归删除。

## 4. Safety

- 远程命令使用绝对路径并先输出 `realpath` 校验。
- 不读取 `.env`。
- 不把服务器路径、Token 或凭据写入任务记录。
- 共享工作区出现 tracked 改动时停止并报告。

## 5. Commit boundaries

- 本地 `.git/info/exclude` 不提交。
- 任务档案或治理文档单独 commit。
- 服务器备份目录不进入 Git。
- 归档/迁移清单在 `research/untracked-inventory.md` 中可复核。

## 6. Rollback

- 本地 ignore 错误：编辑 `.git/info/exclude` 移除对应项。
- 归档提交错误：`git revert`。
- 服务器迁移错误：使用备份 tar 和 SHA256 清单恢复到原路径。
- 禁止用删除命令“重新开始”。
