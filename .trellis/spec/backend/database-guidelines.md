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

### 部分更新与字段置空（MyBatis-Plus 陷阱）

`updateById(entity)` 走实体默认 `FieldStrategy.NOT_NULL`：**null 字段被跳过，无法把列清空**。需要「只改指定列」或「置空」时用 `UpdateWrapper` 显式 `.set(...)`：

```java
// 部分更新：只 set 本次请求出现的字段（null 不动），其余列不写回
projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
        .eq("id", id)
        .set(theme != null, "preview_theme", theme)
        .set(macStyle != null, "preview_mac_style", macStyle)
        .set("updated_at", LocalDateTime.now()));

// 置空：set 无条件发出 `col = null`，绕过 NOT_NULL 策略（用户清空表单场景）
.set("author", blankToNull(author))   // 空串 → null，真正清库
```

- `.set(boolean condition, column, value)` 的条件重载可做「非 null 才更新」；无条件 `.set(column, value)` 用于置空。
- 反例：`updateById` 全实体写回还会覆盖并发请求刚改的其他列（读改写竞态），部分更新场景一律用 `UpdateWrapper`。
- 先例：`ArticleProjectController` 的 `PUT /{id}/preview-style`、`PUT /{id}/publish-meta`。

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

### 定时任务防重叠需带陈旧自愈（09-11 先例：CarSyncScheduler）

`@Scheduled` 消费任务表（如 `sparkora_car_sync_job`）时，用 `hasRunning()`（`status=RUNNING` 计数）防重叠。**但进程在任务中途死亡会残留 RUNNING 行**，无超时自愈则定时任务被永久跳过。

- 个人项目/低频场景可先接受该风险（手动路径不受影响），但需在 spec/设计显式记录为已知限制。
- 完整做法参照 `BriefService` 的 `STALE_GENERATING_MS`：`hasRunning()` 应排除 `updated_at` 超阈值（如 10 分钟）的陈旧 RUNNING 行。
- `job_type` 区分来源（`SELECTED` / `RETRY` / `SCHEDULED`），定时触发 `created_by` 走 `SecurityUtil.current()==null → "system"`。

---

## Migrations

- 全部写进 `schema.sql`（幂等写法，启动自动执行），不引入独立迁移工具。
- 注意：**不能用 `DO $$` 块**——Spring ScriptUtils 不支持 dollar-quote（会把块按 `;` 截断）；列搬数用「补列 → UPDATE 搬数据 → DROP 旧列」三条单语句实现。
- 表结构变更三处同步：`schema.sql`（幂等）+ 对应 entity/mapper + `docs/s0-spec.md` 字段级表格。

### 索引幂等切换（改索引类型/名字，不每次启动重建）

`schema.sql` 每次启动都执行，改索引时若用裸 `CREATE INDEX` 会反复重建。切换索引类型时用「DROP 旧名 + CREATE 新名」并靠改名保证幂等（09-11 先例：KB 向量索引 IVFFLAT → HNSW）：

```sql
-- 首次启动:删旧 IVFFLAT,建 HNSW;后续启动:DROP 旧名 no-op + 新名已存在跳过
DROP INDEX IF EXISTS idx_kb_chunk_emb_vec;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_emb_vec_hnsw ON sparkora_kb_chunk_embedding
    USING hnsw (embedding vector_cosine_ops);
```

- **不要**复用旧索引名（`CREATE INDEX IF NOT EXISTS 旧名` 会因已存在而跳过，改不到新类型）；**换新名**才能让旧类型真正被替换。
- 向量索引统一 HNSW `vector_cosine_ops`（车型域 `idx_car_doc_emb_vec`、KB 域 `idx_kb_chunk_emb_vec_hnsw`）。

### 逻辑删除实体 + 物理向量表：级联清理

`*_embedding` 表（`sparkora_car_doc_embedding` / `sparkora_kb_chunk_embedding`）**无 `deleted` 列**，是物理表。删除带 `@TableLogic` 的实体时：

- 逻辑删除的 doc 通过检索 SQL 的 `JOIN ... AND d.deleted = 0` 过滤，不会命中。
- 但**物理行会残留**：`docMapper.selectList(eq model_id)` 受 `@TableLogic` 过滤，只能删到 `deleted=0` 的 doc，历史已逻辑删除的 doc 的 embedding 漏清。故删除实体时需**按外键一条 SQL 兜底物理清**（09-11 先例 `CarDocEmbeddingMapper.deleteByModelId`）：

```java
@Delete("DELETE FROM sparkora_car_doc_embedding WHERE model_id = #{modelId}")
int deleteByModelId(@Param("modelId") Long modelId);
```

- 外键列（如 `model_id`/`news_id`）直接 `WHERE` 即可，无需 JOIN 逻辑删除表。

### 多域统一检索：候选窗口必须按域隔离

统一检索（`sparkora_car_doc_embedding` + `sparkora_kb_chunk_embedding` + `sparkora_news_doc_embedding` 同向量空间）**不能用一个全局 `LIMIT` 包住所有 UNION 段**（09-11 先例 C2：新闻 1339 块 vs 车型 380 块，语义邻近时新闻占满窗口，实测 BYD 类 query 的 CAR 候选从 32 掉到 0，下游「各域独立配额」拿到空候选直接失效）。

正确做法：**每个来源域各自子查询取 top-#{limit}，外层仅合并排序、不再截断**：

```sql
SELECT * FROM (
    (SELECT ... FROM car/kb ... ORDER BY score DESC LIMIT #{limit})
    UNION ALL
    (SELECT ... FROM news  ... ORDER BY score DESC LIMIT #{limit})
) u ORDER BY score DESC
```

- C2 前只有 CAR/KB 时，`(CAR∪KB) LIMIT` 与旧全局 `LIMIT` 语义等价——**演进时把既有域合并保留原语义，新域单独开窗口**，避免回归。
- 调用方传入的 `limit` 必须 ≥ 各域配额（默认 `topK*4` 且至少 32，远大于 `ragKbTopk`/`ragNewsTopk`）。
- 不要在服务层用「加大 limit」来补偿多域争抢——候选窗口隔离才是根因修复，加大 limit 会静默扩大下游注入集。

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