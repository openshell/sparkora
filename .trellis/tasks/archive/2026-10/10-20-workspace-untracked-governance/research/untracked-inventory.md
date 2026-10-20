# 本地与服务器未跟踪文件盘点

> 任务：`10-20-workspace-untracked-governance`
> 盘点日期：2026-10-21
> 原则：保留用户记录与并发工作；迁移前备份并校验；不读取 `.env`、Token 或凭据。

## 1. 本地对象

| 路径 | 文件数 | 大小 | 分类 | 判断与处理 |
|---|---:|---:|---|---|
| `.agents/` | 46 | 243,200 bytes | LOCAL | 本机 Codex/Trellis agent、skill、hook 配置；未发现形似私钥、Token、口令或 API Key 的内容；保留在本机，加入 `.git/info/exclude`，不提交。 |
| `.codex/` | 8 | 90,027 bytes | LOCAL | 本机 Codex 配置与 hook；未发现形似凭据的内容；保留在本机，加入 `.git/info/exclude`，不提交。 |
| `.trellis/tasks/00-join-c-cha/` | 2 | 变更前为活动任务 | ARCHIVE | 已由 `10-20-trellis-completed-audit` 完成 onboarding 证据并归档到 `.trellis/tasks/archive/2026-10/00-join-c-cha/`。 |
| `frontend/tests/visual/**/*-win32.png` | 24 | Playwright 生成物 | TEMP | 全量视觉测试在 Windows 上生成的未跟踪基线，不属于产品修复；验证后按显式文件路径清理，不提交。 |

`.git/info/exclude` 已加入：

```text
.agents/
.codex/
```

验证：

```text
.git/info/exclude:7:.agents/	.agents
.git/info/exclude:8:.codex/	.codex
```

## 2. 开发服务器原始对象

盘点对象位于 `/data/code/sparkora`。这些任务目录没有出现在本地归档或当前 Git 历史中，属于服务器本地任务记录，不能按临时文件直接删除。

| 路径 | 文件数 | 大小 | 最后修改 | 状态 | 分类 | 去向 |
|---|---:|---:|---|---|---|---|
| `.trellis/tasks/10-13-img-suggest-auto-load/` | 4 | 3,611 bytes | 2026-10-19 23:58:31 | planning | MIGRATE | 仓库外备份并迁移 |
| `.trellis/tasks/10-13-img-suggest-inline-gen/` | 5 | 10,598 bytes | 2026-10-19 23:58:31 | planning | MIGRATE | 仓库外备份并迁移 |
| `.trellis/tasks/10-13-img-suggest-speedup/` | 4 | 3,858 bytes | 2026-10-19 23:58:31 | planning（父任务） | MIGRATE | 仓库外备份并迁移 |
| `.trellis/tasks/10-13-img-suggest-top1-adopt/` | 4 | 3,995 bytes | 2026-10-19 23:58:31 | planning | MIGRATE | 仓库外备份并迁移 |
| `.trellis/tasks/10-15-car-image-backfill/` | 6 | 22,749 bytes | 2026-10-19 23:58:31 | in_progress | MIGRATE | 保留原始状态，仓库外备份并迁移，未伪造成 completed |
| `.trellis/tasks/10-15-img-drawer-ia/` | 6 | 10,824 bytes | 2026-10-19 23:58:31 | planning | MIGRATE | 仓库外备份并迁移 |

说明：

- 上述目录合计 29 个文件。
- 迁移不删除记录，也不把 `in_progress` 改成 `completed`；原始 `task.json` 和全部规划文件保持原样。
- 由于服务器当前被另一个正在进行的 `fix/knowledge-car-sync` 工作占用，不能切换或清理该 checkout；将历史本地任务记录迁移到仓库外是风险最小的治理方式。
- 迁移后的记录仍可从仓库外目录和 tar 备份恢复。

## 3. 服务器备份与校验

备份目录：

```text
/data/code/sparkora-task-archive/2026-10-21/
```

生成物：

```text
trellis-server-task-records.tar.gz
trellis-server-task-records.tar.gz.sha256
trellis-server-task-records.list
files-before.sha256
migrated/.trellis/tasks/...
```

校验值：

```text
archive_sha256=6c5d060bbb64a3109825bcbf82aaa184c99ebd631a2894ef16c1bf2a3c6f576e
archive_tar_entries=35
migrated_regular_files=29
```

校验步骤：

1. 迁移前校验 6 个源目录的绝对路径均位于 `/data/code/sparkora/.trellis/tasks/`。
2. 对 29 个文件生成 SHA256 清单。
3. 生成 tar.gz 和 tar 文件列表。
4. 对 tar.gz 生成 SHA256。
5. 使用 `mv` 迁移到 `/data/code/sparkora-task-archive/2026-10-21/migrated/` 的对应路径。
6. 迁移后重新生成 SHA256 清单，diff files-before.sha256 files-after.sha256 返回 0，29 个文件的校验值一致。tar -tzf 读取校验通过。

恢复方式（只在需要时由人工执行，不在本次任务自动恢复）：

```bash
cd /data/code/sparkora-task-archive/2026-10-21/migrated
mv .trellis/tasks/<task-name> /data/code/sparkora/.trellis/tasks/
```

## 4. 服务器并发工作保留项

盘点期间，服务器 checkout 已由其他工作切换到 `fix/knowledge-car-sync@491c8c9`，并出现与 BYD 推送无关的 tracked/untracked 改动。它们不是本次治理对象，全部保留，不移动、不覆盖、不提交：

- tracked：`docs/spec/image.md`、`docs/spec/knowledge/car.md`、`docs/spec/knowledge/center.md`、`frontend/src/views/CarDetail.vue`、`frontend/src/views/CarSync.vue`、`frontend/src/views/knowledge/CarKnowledgePanel.vue`、`frontend/tests/fixtures/index.js`、`src/main/java/com/sparkora/car/**`、`src/test/java/com/sparkora/car/**`。
- untracked：`.trellis/tasks/10-21-knowledge-car-sync-ux/`、`frontend/src/components/car/`、`frontend/src/composables/useCarSyncJob.js`、`frontend/src/utils/carDisplay.js`、`src/main/resources/db/migration/V24__project_channel.sql`、`V25__byd_dynasty_media.sql`、`V26__car_model_cover_image.sql`。

分类为 HOLD：属于另一个活跃任务和并发工作树。最终服务器状态允许保留这些可解释项；本次 BYD 部署应从独立、干净的目标提交 worktree 执行，避免污染或依赖这些改动。

## 5. 最终结果

- 本地 `.agents/`、`.codex/` 不再出现在 `git status` 中。
- 本地 24 个 Windows 视觉快照已按显式路径清理；治理文档提交后本地只应保留本次已提交任务记录。
- 服务器 6 个历史本地任务目录共 29 个普通文件已备份并移出 Git checkout，未删除任何文件；tar 读取和 SHA256 前后比对均通过。
- 服务器当前另一个活跃任务的 tracked/untracked 改动保持原样并有明确归属；这些项是已解释的 HOLD，不属于无法解释的残留。
- BYD 运行时部署使用独立干净 worktree `0c2d949`，没有覆盖或依赖服务器并发工作区。
- 本次未读取或输出 `.env`、Token、数据库密码和第三方密钥。
