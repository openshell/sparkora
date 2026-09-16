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

### 启动补齐任务：依赖排序 + 网络耗时异步化（09-15 先例：图片标签/向量 runner）

多个 `ApplicationRunner` 做存量补齐时，**依赖关系必须用 `@Order` 显式固定**，不能靠注册顺序巧合：

```java
@Component @Order(10)   // 补标签（A）
public class ImageTagBackfillRunner implements ApplicationRunner { ... }
@Component @Order(20)   // 补向量（B，嵌入文本依赖 A 产出的标签）
public class ImageEmbeddingBackfillRunner implements ApplicationRunner { ... }
```

- **顺序反了会静默损坏数据**：B 用了 A 还没补的字段，产物「看起来完整但内容低质」；更糟的是 B 的补齐判定（如「已有向量」差集）会把错误产物视为已完成，**永不自动修复**，只能人工全量重建。
- **网络型补齐放独立守护线程**：逐行 embedding 是串行网络调用（图库 ~170 图实测 173s），`ApplicationRunner.run()` 返回前应用不就绪——放 `new Thread(..., "xxx-backfill").start()`（daemon），日志输出进度与耗时。阈值参考：超过 ~30s 即应异步。
- **异常必须全吞**（仅 warn）：补齐失败不得阻断启动，可事后调重建接口补救。
- 幂等判据用**差集**（`LEFT JOIN ... WHERE e.id IS NULL` / `WHERE col IS NULL`），重跑 `total=0` 自然跳过。
- 先例：`ImageTagBackfillRunner`（`@Order(10)`）+ `ImageEmbeddingBackfillRunner`（`@Order(20)`，守护线程）。

### 向量/派生数据写入需与调用方事务隔离（09-15 先例：REQUIRES_NEW）

「best-effort 写入」（嵌入、派生缓存）若发生在**调用方事务内**，其 SQL 失败会让 PostgreSQL 把整个事务标记为 aborted，此后调用方任何 SQL 都抛 `current transaction is aborted`——Java 侧 `catch` 无法挽回，主流程（父表 INSERT）照样回滚，**「失败不阻断主流程」的契约被悄悄打破**：

```java
// 自注入代理（@Lazy）——this.persistVector(...) 不经代理，@Transactional 不生效
@Autowired @Lazy private ImageEmbeddingService self;

public void embedOne(ImageAssetEntity img) {
    String vec = embeddingClient.embed(text);   // 网络调用放在事务外
    (self == null ? this : self).persistVector(...);   // self==null 兼容单测直 new
}

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void persistVector(...) { deleteByImageId(id); insert(id, vec); }  // 独立事务 + 先删后插原子化
```

- 判定信号：写入方有 `catch (Exception e) { log.warn(...) }` 却仍可能让主流程失败 → 必须隔离事务。
- 附带收益：独立事务让「先删后插」原子化，重写失败回滚保留旧值，不留空洞。
- 先例：`CarSyncJobService`/`ClarifyService` 的自注入代理写法；09-15 `ImageEmbeddingService.persistVector`。

### 定时任务防重叠需带陈旧自愈（09-11 先例：CarSyncScheduler；09-12 落地）

`@Scheduled` 消费任务表（如 `sparkora_car_sync_job`）时，用 `hasRunning()`（`status=RUNNING` 计数）防重叠。**但进程在任务中途死亡会残留 RUNNING 行**，无超时自愈则定时任务被永久跳过。**09-12 kb-cleanup 已为车型/新闻两侧补齐自愈**：

- 任务表**无 `updated_at`**（与生成类实体不同），存活时间戳用 **`started_at`**。
- 阈值 `SYNC_STALE_MS = 60 * 60 * 1000L`。**不要照搬生成链路的 10 分钟**：全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会把活任务误判为陈旧。
- 两个方法成对：`markStaleRunningAsFailed()`（`status=RUNNING 且 started_at < now-60min` 原子置 `FAILED` + `finished_at` + `error_msg`）与 `hasFreshRunning()`（只统计 `RUNNING 且 started_at >= now-60min`）。
- Scheduler 编排固定为**先清陈旧、再判新鲜**：`markStaleRunningAsFailed(); if (hasFreshRunning()) return;`。顺序不能反，否则陈旧行仍会在本轮被判为阻塞。
- 手动 `createJob`/`runJob` 的 `status=RUNNING→RUNNING` 原子锁语义**不变**（自愈只作用于定时路径的判定与清尾）。
- `job_type` 区分来源（`SELECTED` / `RETRY` / `SCHEDULED`），定时触发 `created_by` 走 `SecurityUtil.current()==null → "system"`。
- 先例：`CarSyncJobService`/`CarSyncScheduler`（车型）、`NewsSyncJobService`/`NewsSyncScheduler`（新闻）。

---

### 多对多关系表：独立关联表 + 应用层维护外键（09-13 先例：图片标签）

图片与标签这类「多对多、标签按名称自由新建」的关系，用**独立关联表**承载，不往主表加逗号/JSON 列（`body_image_ids` 那类有序小集合才用逗号列）：

```sql
CREATE TABLE IF NOT EXISTS sparkora_image_tag (
    id          BIGSERIAL PRIMARY KEY,
    image_id    BIGINT      NOT NULL,   -- 应用层维护，不建强 FK
    tag_name    VARCHAR(50) NOT NULL,
    created_by  VARCHAR(64) NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (image_id, tag_name)         -- 数据库级防重
);
CREATE INDEX IF NOT EXISTS idx_image_tag_name ON sparkora_image_tag(tag_name);
```

- **UNIQUE 约束做并发兜底**：批量插入捕 `DuplicateKeyException` 静默吞，写入天然幂等；`mergeTags`（已有∪传入只插差集）语义 = 「补打」。
- **不建强 FK + 实体无 `@TableLogic`**：关系行生命周期跟父行，父删除时**同事务物理清**（`deleteByImageId`），避免逻辑删除残留孤儿行（同 embedding 表处理先例）。写入前校验父行存在，防止给已删父写孤儿关系（存量 `car_model.intro_images` 可能引用已删图 id）。
- **批量回填避免 N+1**：列表页先收集父 id 集合，一次 `WHERE image_id IN (...)` 查全部关系行再按父分组回填（`fillTags`）。
- **按标签反查用两段查询**：`SELECT image_id WHERE tag_name=?`（走标签索引）→ 外层 `qw.in("id", ids)`；命中集超大时截断（本任务保底 500），不引入 JOIN。

> **Warning**: 自由文本标签的 `tag_name` 必须入口统一 normalize（trim/去空/去重保序/长度上限），Controller 的 multipart 多值与 JSON 数组两条入口共用同一个 normalize，否则同一个标签会以 `" 新闻"` / `"新闻 "` 两种形态落库，按标签筛选漏命中。

### 受控词表分类不建 DB 字典表 + 来源关联列（09-15 先例：新闻图主题分类）

「按固定维度给数据打分类」这类需求，**分类词表放代码常量、不建 DB 字典表**；分类结果复用已有的标签/关系表承载，主表只加一个**通用来源关联列**：

```java
// NewsImageClassifier：纯静态无 Spring 依赖，可单测；LinkedHashMap 保序即展示序
private static final Map<String, Pattern> THEMES = new LinkedHashMap<>();  // 销量/出海/合作签约/...
public static List<String> classifyThemes(String title)     // 0~n，可重叠
public static List<String> toTags(String title, LocalDate publishDate)  // 主题/x + 年份/y
```

- **词表放代码的理由**：分类维度是低频变更的**产品定义**，放代码可版本化、可单测、可复现（零 AI 调用）、避免运行时配置漂移；改词表=改代码发版，可接受。DB 字典表只适合运营端实时增删的场景。
- **允许一词多主题**（不互斥）：如「海外销量创新高」同时属销量+出海，强制单主题丢信息；下游筛选用多标签 AND 天然处理。
- **无命中不打标签**，不强制归「其他」——噪音标签比缺失更难清理。
- **标签命名空间前缀**（`主题/销量`、`年份/2026`）：把系统生成标签与用户自由标签隔离，筛选下拉可按 `/` 前缀分组；代价是名称不「干净」。替代方案是标签表加 `group` 列，但纯名称模型下前缀是低成本做法。
- **词表必须用真实数据回归**：上线前跑全量真实标题统计各主题命中数 + 人工核对宽泛词（如 `获`/`发布`/`榜`）误命中；调整后的词表要**记录在案**（implement.md「实测词表调整」），否则后续无法判断命中分布变化是词表还是数据变动。

主表加来源关联列（通用串，非强 FK）：

```sql
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS source_ref VARCHAR(200);
CREATE INDEX IF NOT EXISTS idx_image_asset_source_ref ON sparkora_image_asset(source_ref);
```

- 一个 `source_ref` 承载所有来源（新闻图=`news_id` 业务唯一键，而非自增 id，更稳定）；其他来源留空，未来可扩展。
- **存量回溯优先用已有信息解析，不做网络重抓**：新闻图文件名含 `detail<数字>`，正则提取后反查 `news_id` 精确后缀匹配——**必须 Java 端 `endsWith` 精确比对，LIKE 只做粗筛**（否则 `detail63` 会误配 `detail632`）。
- 回溯只处理待补行（`source_ref IS NULL`），天然幂等；异常仅 warn，日志汇总「处理 X/跳过 Y/失败 Z」。

> **Warning**: 「仅当列值为空才补写」这类语义**必须把条件写进 UPDATE 的 WHERE**（`.and(w -> w.isNull("source_ref").or().eq("source_ref",""))`），不能只在 Java 端先读后判——先读后写存在 check-then-set 竞态窗口（两条新闻并发同步同一张图时都读到空值，后写覆盖先写，违背「保留首次值」语义）。判定原子性与 09-11「原子抢占」同范式。命中 0 行时以库中现有值回填实体，避免响应体与库不一致。

### multipart 同名多值参数：`getParameterValues` + 逗号拆分

`multipart/form-data` 要传数组时，前端 `FormData.append('tags', v)` 逐项追加同名参数最自然；后端用 `request.getParameterValues("tags")` 收齐后**再对每项按逗号拆分**，兼容「多值」与「单值逗号分隔」两种前端传法：

```java
String[] raw = request.getParameterValues("tags");
List<String> tags = new ArrayList<>();
if (raw != null) for (String v : raw)
    if (v != null) for (String part : v.split(",")) tags.add(part);
// 统一走与 batch/JSON 相同的 normalize（trim/去空/去重/长度校验）
```

- 单值 JSON body（`ImageGenDTO.tags`）与 multipart 两条入口的校验/落库语义必须一致，否则「上传能打标、AI 生图报 400」这类不一致。
- JSON 数组里的元素**不保证是字符串**（前端可能传数字）：用 `String.valueOf` 归一，避免 `(List<String>)` 强转 `ClassCastException` 直接 500。

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