# Flyway 版本化迁移（替代 schema.sql 兼职）

> 父任务：`09-27-p1-arch-consistency`（P1 架构一致性）。子任务 ⑥。解锁 ⑦（JSONB 表结构变更）。

## Goal

把「`src/main/resources/db/schema.sql` 兼职迁移工具」替换为 **Flyway 版本化迁移**：现有全量库结构固化为基线迁移，后续结构变更（P1-⑦ JSONB、⑧ 向量模型名列）走带版本号、可审计、可回滚的迁移脚本；消除「每次启动执行 659 行全量 DDL + 数据回填 UPDATE」与「无版本号、无回滚、无已执行记录」的结构性缺陷。

## Background（评审证据）

### 问题 ⑥（评审原文）

| 事实 | 位置 |
|---|---|
| `spring.sql.init.mode: always` + `schema-locations: classpath:db/schema.sql`，每次启动执行全量脚本 | `application.yml:18-22` |
| 659 行；含 `CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` / `CREATE EXTENSION` / seed `INSERT` / 启动数据回填 `UPDATE` | `src/main/resources/db/schema.sql` |
| 回填 UPDATE 每次启动全表扫描（如 `UPDATE sparkora_article_version SET style_tag...`、项目状态自愈 `UPDATE ... WHERE status IN ('READY','DRAFT')`） | `schema.sql:424-447` |
| 无版本号、无迁移历史表、无回滚、无「已执行」判定 | 全文件 |
| pom 无 flyway 依赖 | `pom.xml` |

### 关键约束（代码证据）

- **pgvector**：`CREATE EXTENSION IF NOT EXISTS vector;`（`schema.sql:188`，注释要求 superuser 预建/授权）。基线必须保留此句。
- **测试无 DB 依赖**：`src/test` 无 `@SpringBootTest`（0 个），无 `src/test/resources/*.yml`——`mvn test` 不启动 Spring 上下文、不连库，迁移改造**不影响现有 370 测试**。
- **无 compose 托管 DB**：`docker-compose.yml` 只有 backend/frontend，无 postgres 服务；DB 是外部/既有实例（`.env` 的 `SPARKORA_DB_*`）。dev 与产线共用同一套配置来源。
- **既有库已建表**：生产/开发库已由 schema.sql 建好全部对象；Flyway 接入**必须对既有库零破坏**（不能重复跑 DDL 报错、不能清库）。
- **seed 角色**在 SQL（`sparkora_role` 幂等 INSERT），**seed 用户**在 Java（`DataInitializer` CommandLineRunner，BCrypt 哈希无法 SQL 硬编码）——后者不受迁移改造影响。
- `db/migration/` 目录已存在且为空。
- **文档引用面**：`schema.sql` 在 33 处被引用（AGENTS.md 2 处、docs/README.md 5 处、docs/spec/** 8 处、.trellis/spec/** 6 处、代码注释 3 处等），需同步。

## Requirements

- R1 **引入 Flyway**：pom 增 `flyway-core` + `flyway-database-postgresql`（Spring Boot 3.3.4 管理的 Flyway 10.x 需独立 postgres 模块）；`application.yml` 启用并配置 `spring.flyway.*`。
- R2 **基线化现有结构**：现有 `schema.sql` 全量内容（DDL + 索引 + 扩展 + 角色 seed + 历史回填 UPDATE）迁移为 `db/migration/V1__baseline.sql`；既有库通过 `baseline-on-migrate` 标记为已应用、**不重跑**，新库从 V1 正常执行。
- R3 **移除 schema.sql 兼职通路**：删 `schema.sql`，移除 `spring.sql.init.mode/schema-locations`；`schema.sql` 不再作为建表/迁移入口。
- R4 **后续迁移能力**：确立 `V<n>__<desc>.sql` 命名与「结构变更只新增迁移脚本」约定，为 ⑦ 提供版本化能力；`flyway_schema_history` 表记录执行历史。
- R5 **安全默认**：Flyway `clean` 保持禁用（Flyway 10 默认 `cleanDisabled=true`）；不在迁移中执行破坏性 DROP（除既有的索引类型切换先例）。
- R6 **文档同步**：所有 `schema.sql` 引用改为指向 Flyway 迁移目录/约定（AGENTS.md、docs/README.md、docs/spec/**、.trellis/spec/**、代码注释）。
- R7 **运行时行为不回退**：既有库启动后所有表/列/回填效果与改造前一致；新库启动后 DDL 与 seed 效果与改造前一致。

## Acceptance Criteria

- [ ] AC1 `mvn -q -DskipTests compile` 通过（含新增 Flyway 依赖解析）。
- [ ] AC2 `mvn test` 全绿（370 用例不回归；无 DB 上下文，预期零影响）。
- [ ] AC3 **既有库**：容器/进程启动成功，`flyway_schema_history` 出现 baseline 记录（`type=baseline`），V1 不重跑；业务表结构与改造前一致（抽查列/索引）。
- [ ] AC4 **新库**：对空库启动，`flyway_schema_history` 出现 `V1` 记录（`type=sql`），全量对象创建成功。
- [ ] AC5 二次启动幂等：无待应用迁移时 Flyway 空跑，无报错、无重复执行。
- [ ] AC6 `spring.sql.init` 已移除，`schema.sql` 已删除，`grep -rn "schema.sql" src/main/java` 无业务代码引用（仅注释改指迁移目录）。
- [ ] AC7 文档同步：`grep -rn "schema\.sql" AGENTS.md docs/ .trellis/spec/` 命中仅剩「历史迁移记录」语境或已改指 Flyway；无「改 schema.sql」的现行指引。
- [ ] AC8 `db/migration/V1__baseline.sql` 内容与改造前 `schema.sql` 语义等价（DDL/seed/回填逐段核对）。

## Out of Scope

- P1-⑦ 的具体 JSONB/表结构变更（本任务只铺路，不改表结构）。
- P1-⑧ 向量模型名列。
- 引入 `flyway-maven-plugin` 做 CI 外置迁移（当前仅应用内启动迁移）。
- DB 备份/PITR 能力（评审另项）。
- `DataInitializer`（Java seed 用户）不动。

## Key Decisions

- **baseline 策略**：`baseline-on-migrate=true` + `baseline-version=1`；既有库标记 V1 已应用（不重跑历史 DDL/回填），空库正常执行 V1。这是「既有库零破坏 + 新库可自举」的标准解。
- **V1 = 现有 schema 全量**（含历史回填 UPDATE 与角色 seed）：既有库本就执行过这些幂等语句，标记跳过安全；新库执行一遍即达当前状态。回填语句保留幂等（重复执行无副作用），不做拆分（拆出 V2 只会增加既有库的重复执行面）。
- **删除 schema.sql 而非并存**：保留两份 = 双源漂移风险，与「单一迁移入口」目标相悖。
- **不引入 flyway-maven-plugin**：应用启动时迁移已覆盖 dev（`mvn spring-boot:run`）与产线（容器启动）两路；外置迁移属额外运维面，按需另立。

## Open Questions

（无——baseline 策略与删除 schema.sql 已定案；范围收敛为「接入 Flyway + 基线化 + 文档同步」）
