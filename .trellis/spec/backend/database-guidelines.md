# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

- MyBatis-Plus 3.5.7 + PostgreSQL。表前缀 `sparkora_`、`id-type: auto`、逻辑删除字段 `deleted`（`@TableLogic`）、下划线转驼峰。
- `src/main/resources/db/schema.sql` 是唯一建表/迁移入口，`spring.sql.init.mode: always` 启动自动执行——**必须是幂等的**（`CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`）。

---

## Query Patterns

- 单表 CRUD 用 mapper 直调；条件查询 `QueryWrapper` / `UpdateWrapper`。
- **原子抢占**（消除 check-then-set 竞态）：条件更新返回影响行数判定：

```java
int claimed = projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
        .eq("id", projectId)
        .and(w -> w.in("status", "DRAFT", "READY")
                .or(w2 -> w2.in("status", "GENERATING_BRIEF", "GENERATING_VERSIONS")
                        .lt("updated_at", staleCutoff)))   // 陈旧分支必须限定生成中状态
        .set("status", "GENERATING_BRIEF")
        .set("updated_at", LocalDateTime.now()));
if (claimed == 0) throw new IllegalStateException("状态冲突");
```

- 排序字段白名单：用户可控的 orderBy/orderDir 只允许映射到固定列名常量，**原始参数绝不透传 QueryWrapper**（SQL 注入面）。

---

## Async Claim / 幂等占位（09-11 先例：ClarifyService）

异步生成（202 语义）的「同步落占位 + 后台生成」范式，并发/自愈用**部分唯一索引**做数据库级兜底：

```sql
ALTER TABLE sparkora_article_brief ADD COLUMN IF NOT EXISTS plan_status VARCHAR(20); -- DEEP: PLANNING/READY
CREATE UNIQUE INDEX IF NOT EXISTS uq_brief_planning
    ON sparkora_article_brief(project_id) WHERE plan_status = 'PLANNING';
```

- 同步 `start()`：清理超 10min 的陈旧占位（进程死亡自愈）→ insert 占位（`plan_status='PLANNING'`）→ 撞索引捕 `DuplicateKeyException` 转 `IllegalStateException`（接口 409）。
- 部分唯一索引只约束 PLANNING 态，历史/完成行（null/READY）不冲突，可安全对存量库执行。
- 占位表用物理 `deleteById` 清理（若实体带 `@TableLogic` 逻辑删除，`delete` 只会置 deleted，占位仍可能被查询命中——brief 表无 `@TableLogic`，不受影响）。

> **Warning**: 占位行失败时若被删除，任何「按项目取最新行」的查询都会回退到更早的旧行，导致轮询误判状态。轮询必须用**本次启动返回的 briefId 精确定位**，不能只按 projectId 取最新（见 error-handling.md「轮询可删除占位」）。

---

## Migrations

- 全部写进 `schema.sql`（幂等写法，启动自动执行），不引入独立迁移工具。
- 注意：**不能用 `DO $$` 块**——Spring ScriptUtils 不支持 dollar-quote（会把块按 `;` 截断）；列搬数用「补列 → UPDATE 搬数据 → DROP 旧列」三条单语句实现。
- 表结构变更三处同步：`schema.sql`（幂等）+ 对应 entity/mapper + `docs/s0-spec.md` 字段级表格。

---

## Naming Conventions

- 表：`sparkora_<domain>_<entity>`；列：snake_case ↔ 实体驼峰自动映射。
- 状态值/枚举落 VARCHAR(20) 内全大写（TOPIC/IMITATION、DRAFT/READY...）。
- JSON 字段统一 TEXT 列存字符串（后端写入、前端解析），不引入 JSON 类型处理器。

---

## Common Mistakes

### Common Mistake: 非幂等迁移语句

**Symptom**: 二次部署启动失败（column already exists / table exists）。

**Cause**: schema.sql 里写了裸 `ADD COLUMN` / `CREATE TABLE`（无 IF NOT EXISTS）。

**Fix**: 全部补 `IF NOT EXISTS`；默认值用 `DEFAULT` 子句使旧行自动回填。

**Prevention**: 新增迁移段注释开头标明阶段号，并自检「重复执行无副作用」。