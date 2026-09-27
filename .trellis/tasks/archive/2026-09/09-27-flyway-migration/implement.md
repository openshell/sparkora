# Flyway 版本化迁移 实施计划

> 单里程碑；顺序：依赖 → 配置 → 脚本 → 删旧 → 文档 → 验证。

## 前置检查

- [ ] `git status` 干净基线（当前 dirty：P1 父任务 task.json + 本任务目录）
- [ ] `mvn -q -DskipTests compile` 基线通过
- [ ] `mvn test` 基线 370 全绿
- [ ] 确认 `db/migration/` 为空目录（无既有 V*.sql）
- [ ] 探测 `.env` 的 DB 是否可达（`psql`/`pg_isready`，决定 AC3/AC4 能否端到端；不可达则走临时库或标注人工验证）

## Step 1: 引入 Flyway 依赖（pom.xml）

- [ ] 加 `org.flywaydb:flyway-core`（无版本，走 Boot BOM）
- [ ] 加 `org.flywaydb:flyway-database-postgresql`（Flyway 10 必需）
- 验证：`mvn -q -DskipTests compile` 通过（确认依赖解析成功）

## Step 2: 切换配置（application.yml）

- [ ] 移除 `spring.sql.init.mode/schema-locations/continue-on-error`
- [ ] 新增 `spring.flyway.{enabled,locations,baseline-on-migrate,baseline-version,baseline-description,validate-on-migrate}`
- 验证：编译通过（配置无 schema 校验，靠后续启动）

## Step 3: 固化基线脚本

- [ ] `git mv` 或复制 `src/main/resources/db/schema.sql` → `src/main/resources/db/migration/V1__baseline.sql`（内容逐字不改）
- [ ] 更新文件头注释：标明「V1 基线 = 历史 schema.sql 全量；已由 Flyway 接管，勿再改此文件；新增变更走 V2+」
- [ ] 删除 `src/main/resources/db/schema.sql`
- 验证：`git diff --stat` 显示 rename；文件内容与旧 schema.sql 语义一致

## Step 4: 文档同步（33 处引用）

- [ ] `AGENTS.md`：`schema.sql` 描述 → Flyway 迁移目录 + 版本化约定
- [ ] `docs/README.md`（5 处）：架构图 DB 节点、状态机自愈措辞、配置表、已定决策、文档维护约定
- [ ] `docs/spec/**`（8 处）：改「schema.sql 幂等」→「Flyway `db/migration/V*.sql`」
- [ ] `docs/deploy.md`：「无需手工迁移」段改为 Flyway 说明
- [ ] `.trellis/spec/backend/database-guidelines.md`（权威，6 处）：Migrations 段整体改写为 Flyway 约定；Common Mistake「非幂等迁移」保留但改指迁移脚本
- [ ] `.trellis/spec/backend/quality-guidelines.md`、`error-handling.md`、`guides/docs-structure-guide.md`：引用同步
- [ ] 代码注释：`ImageAssetEntity.java`、`ProjectStatusService.java`（「随 Flyway 子任务处置」改为已完成）
- 验证：`grep -rn "schema\.sql" AGENTS.md docs/ .trellis/spec/ src/` 仅剩历史语境

## Step 5: 全量验证

- [ ] `mvn -q -DskipTests compile`
- [ ] `mvn test`（370 全绿）
- [ ] 若 DB 可达：空库 + 既有库模拟两场景验证 `flyway_schema_history`（见 design.md §5）；验完精确 drop 临时库
- [ ] 若 DB 不可达：标注 AC3/AC4 为人工验证项，并在任务 notes 记录
- [ ] `grep -rn "spring.sql.init\|schema-locations" src/main/resources/` 应为空

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| Step 1 | 依赖解析失败/离线 | 单 commit revert；依赖本身不改行为 |
| Step 2 | 配置错误致启动失败 | 恢复 `spring.sql.init` 段（Step 3 未删前可两存） |
| Step 3 | 基线脚本遗漏内容 | 与 `git show HEAD:...schema.sql` 逐段 diff 核对；revert |
| Step 4 | 文档遗漏/改错 | 纯文档，无运行时影响 |

## 提交约定

- 一次工作提交（依赖+配置+脚本+删旧）`chore(db): 引入 Flyway 版本化迁移替代 schema.sql`
- 一次文档提交 `docs(spec): 迁移入口改指 Flyway + 版本化约定`
- 或合并为一次 `feat(db): 引入 Flyway 版本化迁移替代 schema.sql 兼职`（按 finish-work 步骤 3.4 分类时定）

## start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] 用户已确认最终规划摘要（baseline 策略 / 删除 schema.sql / 文档同步面）
