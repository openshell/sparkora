# Flyway 版本化迁移 技术设计

> 无表结构变更（本任务只换迁移机制）；无新表；新增 1 个依赖（Boot BOM 管理版本，不写死）。

## 1. 依赖与配置

### pom.xml

```xml
<!-- 依赖管理：版本由 spring-boot-starter-parent 3.3.4 的 BOM 提供（Flyway 10.10.0），不显式写版本 -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>   <!-- Flyway 10 起 PostgreSQL 支持拆分为独立模块 -->
</dependency>
```

- Flyway 10.x 起 `flyway-core` 不再内置各数据库方言，PostgreSQL 必须额外引 `flyway-database-postgresql`（否则启动报 `Unsupported Database: PostgreSQL`）。
- 版本走 Boot BOM（10.10.0），**不写死版本号**（与仓库「配置走 Boot 管理」惯例一致）。

### application.yml

移除：

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:db/schema.sql
      continue-on-error: false
```

新增：

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true     # 既有库（非空 schema 且无历史表）标记基线后跳过历史，不重跑
    baseline-version: 1           # 基线对齐 V1；既有库记为 BSLN@1，V1 不执行；空库正常执行 V1
    baseline-description: "schema.sql baseline (S0~S12 全量)"
    validate-on-migrate: true     # 校验 checksum，防止已应用脚本被篡改
```

- `cleanDisabled` 保持 Flyway 10 默认 `true`（不显式配置），杜绝误 `clean` 清库。
- 无需新增 `.env` 变量：迁移是结构一次性能力，非运行时可调参数。

## 2. 迁移脚本

`src/main/resources/db/migration/V1__baseline.sql` = **改造前 `schema.sql` 全文逐字**（含 `CREATE EXTENSION`、全部 `CREATE TABLE IF NOT EXISTS`、`ADD COLUMN IF NOT EXISTS`、索引、`sparkora_role` seed、历史回填 `UPDATE`）。

**为什么整段作 V1 不拆分**：

- 既有库已执行过这些幂等语句 → 基线标记直接跳过，零重复执行；
- 空库执行一次即达当前结构；
- 拆出「V2=回填」反而让既有库多跑一遍回填（无必要）。

删除 `src/main/resources/db/schema.sql`（保留两份 = 双源漂移）。

### 两条启动路径

| 库状态 | Flyway 行为 |
|---|---|
| **既有库**（已有表、无 `flyway_schema_history`） | 检测到非空 schema 且无历史 → 建历史表 → 插入 `BSLN`@1 → 跳过 V1 → 启动继续 |
| **空库** | 无表、无历史 → 执行 `V1__baseline.sql` → 插入 `V1` 记录 → 启动继续 |
| **再次启动** | 历史表已存在、无 pending → 空跑，无操作 |

## 3. 兼容性与风险

### 兼容性

- **测试零影响**：`src/test` 无 `@SpringBootTest`、无测试 DB 配置 → `mvn test` 不启动 Spring、不连库。
- **DataInitializer**（Java seed 用户）在 `CommandLineRunner` 阶段执行，晚于 Flyway（DataSource 初始化期），顺序安全。
- **pgvector**：`CREATE EXTENSION IF NOT EXISTS vector` 权限要求与改造前完全一致（superuser 预建/授权）。
- **容器启动**：Flyway 在 backend 容器启动时运行（与 schema.sql 时机相同），`docker compose up -d --build` 后首次接入对既有库标记基线、对全新库建表。

### 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 既有库非空导致 Flyway 报「found non-empty schema without schema history」 | 启动失败 | `baseline-on-migrate=true` 消除；这是标准解 |
| 基线版本号选错（>1）导致 V1 被跳过而新库缺表 | 新库缺对象 | 固定 `baseline-version=1` + V1 文件；空库不触发 baseline 分支 |
| flyway-core 缺 postgres 模块 | 启动报 Unsupported Database | 同时引 `flyway-database-postgresql` |
| 迁移失败半途 | 启动失败 | PostgreSQL DDL 事务原子性：单个 V1 要么全成要么全回滚；失败即阻止应用启动（fail-fast，符合 `continue-on-error:false` 旧语义） |
| 无本地 PostgreSQL 可验证 | AC3/AC4 无法端到端 | 尝试连 `.env` 指向的 DB；不可达则用「临时库」或标注人工验证 |

### 回滚

- 纯配置/文件回退：恢复 `application.yml` 的 `spring.sql.init` + 还原 `schema.sql`，删 flyway 依赖。**已建的 `flyway_schema_history` 表无副作用**（下次可复用；不引用即无害）。
- V1 不修改任何已有库结构（对既有库被跳过），回滚无数据影响。

## 4. 文档同步面

`schema.sql` 共 33 处引用，需改指 Flyway：

- `AGENTS.md`（2 处：模块描述 + 表结构变更约定）
- `docs/README.md`（5 处：架构图、状态机自愈、配置表、已定决策、文档维护约定）
- `docs/spec/**`（8 处：overview/project-lifecycle/image×2/imitation/brief-generation/news/kb/qa/settings）
- `docs/deploy.md`（1 处：「无需手工迁移」段）
- `.trellis/spec/backend/database-guidelines.md`（权威迁移约定，6 处）+ `quality-guidelines.md` + `error-handling.md` + `guides/docs-structure-guide.md`
- 代码注释：`ImageAssetEntity.java`、`ProjectStatusService.java`、`application.yml`

**改动原则**：把「改 schema.sql（幂等）」的现行指引改为「新增 `db/migration/V<n>__<desc>.sql` 迁移脚本（基线 V1 已固化历史结构）」；历史叙述（「schema.sql 启动回填」描述既成事实）保留但标注为历史。

## 5. 验证方案

```bash
mvn -q -DskipTests compile          # 依赖解析 + 编译
mvn test                            # 370 用例（无 DB 上下文，预期零回归）
```

DB 端到端（若可达）：

1. **空库**：建临时库 `sparkora_flyway_verify_new` → 启动应用（或 `flyway` 命令）→ 断言 `flyway_schema_history` 有 `V1`/`type=sql` + 关键表存在。
2. **既有库模拟**：临时库执行改造前 `schema.sql` → 再启动 Flyway（baseline-on-migrate）→ 断言 `BSLN`@1 + V1 未重跑 + 表结构不变。
3. 验完精确 drop 临时库；**不触碰任何既有生产库/表**（quality-guidelines）。
