# Database Guidelines

> Database patterns and conventions for this project.

---

## Overview

- MyBatis-Plus 3.5.7 + PostgreSQL。表前缀 `sparkora_`、`id-type: auto`、逻辑删除字段 `deleted`（`@TableLogic`）、下划线转驼峰。
- `src/main/resources/db/migration/` 是唯一建表/迁移入口（**Flyway 版本化迁移**）：历史全量结构固化为 `V1__baseline.sql`，后续结构变更新增 `V<n>__<desc>.sql`；由 `spring.flyway.*` 在启动时按版本执行，`flyway_schema_history` 记录执行历史。既有库经 `baseline-on-migrate` 标记基线 V1 后跳过，不重跑历史 DDL。

---

## Query Patterns

- 单表 CRUD 用 mapper 直调；条件查询 `QueryWrapper` / `UpdateWrapper`。
- **原子抢占**（消除 check-then-set 竞态）：条件更新返回影响行数判定（项目状态机的抢占/推进/回退已收敛到 `ProjectStatusService` 单一写权持有者，见 error-handling.md「状态机写权收敛」；新链路只做委托，不手写状态 UpdateWrapper）：

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
- 先例：`ProjectPreviewController`（09-27 前为 `ArticleProjectController`）的 `PUT /{id}/preview-style`、`PUT /{id}/publish-meta`。

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

### 多对多 / 有序一对多关系表：独立关联表 + 应用层维护外键（09-13 先例：图片标签）

图片与标签这类「多对多、标签按名称自由新建」的关系，用**独立关联表**承载，不往主表加逗号/JSON 列。**有序一对多同样走关联表**（用 `sort_order` 保序），不再给主表加逗号列：

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

### 派生数据不落库，只持久化用户决策（09-15 先例：配图建议「忽略」）

「按需重算且结果稳定」的**派生视图**（如正文 × 图库的语义检索建议）**不落库**——落库只会引入「建议陈旧」（正文/图库变了，库里建议还是旧的），按需计算已足够。**只有用户的显式决策**需要持久化：

```sql
CREATE TABLE IF NOT EXISTS sparkora_illustration_dismiss (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL, version_id BIGINT NOT NULL,
    anchor_key VARCHAR(200) NOT NULL,       -- 定位指纹(非序号)
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, anchor_key)         -- 重复「忽略」幂等
);
CREATE INDEX IF NOT EXISTS idx_illustration_dismiss_version ON sparkora_illustration_dismiss(version_id);
```

- **定位用指纹而非序号**：被引用对象（正文段落）会编辑，序号漂移会让决策错位到别的对象；指纹（稳定字段 + 内容短哈希）在未编辑时稳定，编辑后仅该条失效、不误伤其他。
- 幂等写入 = 先查 + `UNIQUE` 兜底捕 `DuplicateKeyException`；该写入无外层事务，冲突后无后续 SQL 会撞 aborted 事务（若在事务内，见上「向量/派生数据写入需与调用方事务隔离」）。

> **Warning**: 关联/派生表的「清空/置空」不要用 `updateById(entity)`——MyBatis-Plus `NOT_NULL` 策略会跳过 `null` 字段，导致**清空最后一行的操作静默失败**。置空必须用 `UpdateWrapper.set(col, null)`。**更好的是改为关联表按行删**（P1-⑦ 先例：`ImageService.modifyBodyImage` 移除最后一张插图由「`updateById` 写 `body_image_ids`」改为 `ArticleVersionImageMapper.deleteByVersionAndImage`，缺陷自然消失）。

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

### 文件/ID 集合参数直接绑定 `List`（09-26 img2img-multi-ref 先例）

`FormData.append('files', f)` 逐项追加同名文件、`FormData.append('refImageIds', id)` 逐项追加同名数字时，Spring 可直接把 `@RequestParam` 绑成集合，**不必手工 `getParameterValues`**：

```java
@PostMapping("/generate-from-image-upload")
public R<...> generate(
    @RequestParam(value = "files", required = false) List<MultipartFile> files,
    @RequestParam(value = "refImageIds", required = false) List<Long> refImageIds, ...)
```

- **`required = false` + 集合类型**：参数缺失时绑定为 `null`（不是空集合）→ 服务层必须先做 `null` 过滤再计数，否则 `[null]` 会绕过「0 张」校验，且 `null` 元素触发 NPE。
- **数量校验要用「过滤后」的计数**：`files.size()+refImageIds.size()` 须在剔除 `null`/空项之后再判定 1~4（0 → 400，超限 → 400）。
- **顺序契约**：多来源合并（如「先上传文件、后图库 id」）必须由服务层按固定顺序拼接，**不得重排**，前端展示顺序 == 提交顺序 == AI 收到的顺序。
- 类型不匹配（如 `refImageIds` 传非数字）由全局 `ApiExceptionHandler` 落 400，无需接口内手工兜。
- 先例：`ImageController.generateFromImageUpload` + `ImageService.generateImage2ImageFromUpload`（多参考图：`files[]` + `refImageIds[]`，上限 4）。

## Migrations

- **Flyway 版本化迁移**：全部结构变更写进 `src/main/resources/db/migration/V<n>__<desc>.sql`（命名约定两个下划线），由 `spring.flyway.*` 启动时执行；`flyway_schema_history` 表记录版本/checksum/执行时间。
- **`V1__baseline.sql` 是历史基线**：= 改造前 `schema.sql` 全文（逐字固化），**不得修改**；既有库由 `baseline-on-migrate=true` + `baseline-version=1` 记为 `BSLN`@1 后跳过，空库正常执行 V1 自举。
- **已应用的迁移脚本不得改**：`validate-on-migrate: true` 会校验 checksum，事后改动会导致启动失败；修正只能**新增更高版本脚本**。
- **迁移脚本无需写 `IF NOT EXISTS` 兜底**（Flyway 按版本只执行一次）；V1 保留原幂等写法仅为语义等价固化，新脚本按普通 DDL 写即可。
- `clean` 保持 Flyway 10 默认禁用（`cleanDisabled=true`），**不得**开启；迁移中不执行破坏性 DROP（索引类型切换的 `DROP INDEX IF EXISTS` 先例除外）。
- 表结构变更三处同步：新增迁移脚本 + 对应 entity/mapper + `docs/spec/**` 对应模块文档字段级表格（规格入口 `docs/README.md`）。
- 约定/目录说明见 `src/main/resources/db/migration/README.md`。

### 列搬数（不可用 `DO $$` 块）

Flyway / Spring ScriptUtils **不支持 dollar-quote**（会把块按 `;` 截断），迁移脚本里不得用 `DO $$ ... $$`。列搬数用「补列 → UPDATE 搬数据 → DROP 旧列」三条单语句实现：

```sql
ALTER TABLE sparkora_x ADD COLUMN IF NOT EXISTS new_col VARCHAR(20);
UPDATE sparkora_x SET new_col = old_col WHERE new_col IS NULL;
ALTER TABLE sparkora_x DROP COLUMN IF EXISTS old_col;
```

### 索引切换（版本化脚本中改索引类型/名字）

迁移脚本按版本**只执行一次**，不存在「每次启动重建」问题；切换索引类型直接用 `DROP INDEX IF EXISTS 旧名` + `CREATE INDEX 新名`（09-11 先例：KB 向量索引 IVFFLAT → HNSW）。**换新名**，不要复用旧名：

```sql
DROP INDEX IF EXISTS idx_kb_chunk_emb_vec;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_emb_vec_hnsw ON sparkora_kb_chunk_embedding
    USING hnsw (embedding vector_cosine_ops);
```

- 向量索引统一 HNSW `vector_cosine_ops`（车型域 `idx_car_doc_emb_vec`、KB 域 `idx_kb_chunk_emb_vec_hnsw`）。

### 向量模型名防护：加列 + placeholder 回填（推翻 V1「不存模型名」）

4 张向量表（`sparkora_{car_doc,kb_chunk,news_doc,image}_embedding`）**同向量空间**（同 embedding 模型/维度）；V1 曾决策「不存模型名/维度列」，**09-27 P1-⑧ 推翻**——同维换模型是真实风险且完全不可检测。做法：

```sql
-- V3__embedding_model.sql：加列（可空，避免极端数据迁移失败）；回填 = 实际部署配置模型
ALTER TABLE sparkora_car_doc_embedding ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
UPDATE sparkora_car_doc_embedding SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
-- …另外 3 表同构
```

- **回填用 Flyway placeholder** `${embeddingModel}`（`application.yml` 的 `spring.flyway.placeholders.embeddingModel: ${AI_EMBEDDING_MODEL:Qwen3-Embedding-8B}`），**不得硬编码默认值**——否则会把既有部署库错标为默认模型。这是「回填值须随实际配置走」的通用范式。
- **写入盖名 + 检索过滤成对**：4 个 insert mapper 增 `embeddingModel` 参数（写路径传 `EmbeddingClient.modelName()`）；4 条检索 SQL 加 `AND e.embedding_model = #{model}`。列可空 + `=` 比较：NULL 行天然不匹配（安全方向——宁可漏检旧行，不混空间）。
- **对账/补齐口径同步**：判定「已向量化」的统计（`countByModel.embeddedCount`、`findImageIdsWithoutEmbedding` 的 LEFT JOIN 条件）必须加同模型过滤，否则「只有旧模型向量」会被误判为已就绪、`rebuildMissing` 永不修复。
- **启动对账**：`EmbeddingModelReconcileRunner`（`@Order(60)`，Flyway 之后）逐表 `GROUP BY embedding_model`，非当前模型行 WARN；异常仅 warn 不阻断启动。

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
- **图片域（第四域）是同空间但独立检索**：`sparkora_image_embedding` 与三域同模型同维度，但**不并入 `searchTopKUnified`**（图片查询是独立入口 `POST /api/images/search`，不与文本块混排）。同空间只保证「同一 embedding 模型/维度」这一硬约束，不代表共用一条 SQL；新增域时按「是否需要与既有域混排」决定并入还是独立，不要为了「统一」把异质结果强行 UNION。
- 不要在服务层用「加大 limit」来补偿多域争抢——候选窗口隔离才是根因修复，加大 limit 会静默扩大下游注入集。

---

## Naming Conventions

- 表：`sparkora_<domain>_<entity>`；列：snake_case ↔ 实体驼峰自动映射。
- 状态值/枚举落 VARCHAR(20) 内全大写（TOPIC/IMITATION、DRAFT/READY...）。
- JSON 字段统一 TEXT 列存字符串（后端写入、前端解析），不引入 JSON 类型处理器。

### JSON 存 TEXT 是「有意约定」（P1-⑦ 复核裁定，不转 JSONB）

评审曾提「`fact_sheet`/`rag_citations`/`citations`/`image_refs` 等 JSON 列改 JSONB」，**复核后判定不予处置**，理由是「有意约定 + 零收益 + 高 churn」：

1. **明文约定**：本文件「JSON 字段统一 TEXT 列存字符串，不引入 JSON 类型处理器」即项目约定；`ArticleBriefEntity` 类注释同款声明；全实体 JSON 列均为 `String`，全库无 `TypeHandler`/`autoResultMap`。
2. **零收益**：全库**零 JSON-path 查询**——JSON 列是纯不透明载荷（后端 `ObjectMapper` 写、前端 `JSON.parse` 读），从不参与查询/索引/过滤（`grep -rn '\->>\|jsonb\|json_array_elements\|stringtype' src/` 仅命中迁移 README 示例名）。
3. **高 churn**：JDBC URL 须加 `?stringtype=unspecified`（当前 `application.yml` 无参数），否则 insert 报 varchar vs jsonb 类型错；~20 个 TEXT JSON 列需 `USING ::jsonb` 迁移 + 空串防御。

> 结论：**JSON 列保持 TEXT**。仅当出现「按 JSON 内部字段查询/索引/聚合」的真实需求时再重新评估（届时按新 Flyway 迁移版本实施，并同步改 JDBC URL）。

### 有序小集合不落逗号列：P1-⑦ body_image_ids 规范化先例

`sparkora_article_version.body_image_ids VARCHAR(1000)`（逗号分隔有序 id 串）违反 1NF，已由 Flyway `V2__article_version_image.sql` 规范化为关联表 `sparkora_article_version_image`（`version_id`/`image_id`/`sort_order` 保序、`UNIQUE(version_id,image_id)`、两索引、无强 FK）：

```sql
-- P1-⑦：逗号列 → 关联表（含回填 + DROP 列，单迁移内完成）
CREATE TABLE IF NOT EXISTS sparkora_article_version_image (
    id         BIGSERIAL PRIMARY KEY,
    version_id BIGINT  NOT NULL,
    image_id   BIGINT  NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,      -- 0 起，与原串顺序一致
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (version_id, image_id)
);
INSERT INTO sparkora_article_version_image (version_id, image_id, sort_order)
SELECT v.id, trim(parts.token)::bigint, parts.ord - 1
FROM sparkora_article_version v
CROSS JOIN LATERAL regexp_split_to_table(v.body_image_ids, ',') WITH ORDINALITY AS parts(token, ord)
WHERE v.body_image_ids IS NOT NULL AND trim(v.body_image_ids) <> '' AND trim(parts.token) <> ''
ON CONFLICT (version_id, image_id) DO NOTHING;
ALTER TABLE sparkora_article_version DROP COLUMN IF EXISTS body_image_ids;
```

- **逗号列代价**（本先例的动机）：读侧两份重复 `split(",")` 解析易漂移；引用检查只能 `LIKE '%id%'` 粗筛，`id=5` 会误匹配 `15/51`，必须再 Java 侧精确过滤兜底；`updateById` 回写整行还带读改写竞态。
- **规范化收益**：有序查询 `WHERE version_id=? ORDER BY sort_order`（`ArticleVersionImageMapper.findImageIdsByVersion`）；引用检查 `WHERE image_id=?` 精确反查（`findVersionIdsByImage`），`LIKE` hack 与误匹配消失；add = `sort_order=max+1` 追加（幂等 + `UNIQUE` 兜底），remove = 精确 `DELETE`（幂等，且天然修掉「清空最后一行静默失败」）。
- **列搬数用「补表/补列 → 迁移数据 → DROP 旧列」**，且回填与 DROP 同一迁移内完成（数据是搬移非丢失）；回滚 = 重加列 + `string_agg(image_id::text, ',' ORDER BY sort_order)` 聚合回填（manual，文档化）。
- **对外契约不变**：快照 API 仍返回 `bodyImageIds: List<Long>`（由关联表按 `sort_order` 计算），前端零改动。

---

## Common Mistakes

### Common Mistake: 修改已应用的迁移脚本 / 漏引 postgres 方言模块

**Symptom**: 二次部署启动失败（`Migration checksum mismatch` / `Found non-empty schema without schema history` / `Unsupported Database: PostgreSQL`）。

**Cause**:

1. 改动了已应用（已在 `flyway_schema_history` 中）的迁移脚本 —— `validate-on-migrate: true` 校验 checksum 失败；
2. 引入 Flyway 时漏了 `baseline-on-migrate` —— 既有非空库无历史表，Flyway 直接拒绝启动；
3. 只加 `flyway-core` 未加 `flyway-database-postgresql` —— Flyway 10 起方言拆分，报 `Unsupported Database`。

**Fix**: 已应用脚本**只增不改**（修正走新 `V<n+1>__...sql`）；既有库保留 `baseline-on-migrate=true` + `baseline-version=1`；pom 同时引 `flyway-core` 与 `flyway-database-postgresql`（版本走 Boot BOM）。

**Prevention**: 新增迁移脚本注释开头标明阶段号；提交前自检「脚本未被改动」与「依赖成对」。

### Common Mistake: Boot 4 下只引 `flyway-core` → 迁移静默不执行（10-03 E1 实测）

**Symptom**: 新增迁移脚本后启动**无任何 Flyway 日志**、`flyway_schema_history` 不新增行、目标表未建，但应用照常启动（**静默**，最危险）。

**Cause**: Boot 4 起自动配置按技术拆分模块：Flyway auto-config（`org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`）从 `spring-boot-autoconfigure` 移入独立 `spring-boot-flyway`（starter = `spring-boot-starter-flyway`）。只把 `flyway-core` / `flyway-database-postgresql` 放进依赖时，**Flyway 自动配置类不在 classpath**，`spring.flyway.*` 全部被忽略（连 `Unsupported Database` 都不会报——因为根本没跑）。本仓 10-03 Boot 4 升级（`e625709`）后即处于该状态，直到 E1 发现。

**Fix**: pom 改引 `org.springframework.boot:spring-boot-starter-flyway`（内含 `spring-boot-flyway`+`flyway-core`），**并显式另加 `org.flywaydb:flyway-database-postgresql`**（starter 不带方言模块，缺失会退化为 `Unsupported Database`）。版本走 Boot BOM。

**Prevention**: 升级 Boot 大版本后，凡「新增迁移未生效」先查启动日志有无 `Database: jdbc:postgresql.../Migrating schema`；无则核对自动配置模块是否随 Boot 拆分而缺失（Boot 4 拆出的模块还包括 `spring-boot-flyway`/`spring-boot-jdbc` 等，starter 是唯一稳妥入口）。

### Common Mistake: Flyway 版本落后于 PostgreSQL 主版本（仅告警，非阻断）

**Symptom**: 启动日志出现 `Flyway upgrade recommended: PostgreSQL <x> is newer than this version of Flyway`。

**Cause**: Boot 3.3.4 BOM 管理的 Flyway 10.10.0 官方支持上限为 PG16，而产线库为 PG17。

**Fix**: 实测基线/迁移/checksum/幂等重启均正常，**保持现状**——升级 Flyway 会偏离「版本走 Boot BOM」约定并带来 Boot 兼容风险；待 Boot 升级抬升 Flyway 版本后自然消除。

**Prevention**: 见到此告警先确认迁移是否实际成功（查 `flyway_schema_history`），不要把告警当失败；仅在确认新语法/类型不兼容时才考虑显式提升 Flyway 版本。