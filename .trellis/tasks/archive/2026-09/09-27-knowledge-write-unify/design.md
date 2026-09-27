# 知识域写入侧统一 + 向量模型名防护 — 技术设计

> 无新表；V3 迁移只给 4 张既有向量表加一列。新共享类放入 `com.sparkora.ai`。`EmbeddingClient` 暂留 `car.client` 包（包迁移属机械改动且涉及多处 import，本任务不混入，见 Out of Scope）。

## 1. 共享抽象（新增于 `com.sparkora.ai`）

### 1.1 `TextChunker`（纯静态）

```java
public final class TextChunker {
    public static final int MAX_BODY_LEN = 500;

    /** 空正文行为：保留标题块 / 仅标题非空保留 / 返回空列表。 */
    public enum EmptyBody { KEEP_TITLE, KEEP_TITLE_IF_PRESENT }

    /** header 由调用方构造（KB「知识：t（d）」/ NEWS「新闻：t（date）」逐字不变）。 */
    public static List<String> chunk(String header, String content, EmptyBody emptyBody) { ... }
    static List<String> splitSentences(String p) { ... }   // 唯一实现
}
```

- 算法逐行搬自现有 KB 实现（空行分段 → 段内换行转空格 → ≤500 直成块 → 超长按句读合并 → 尾巴硬切 → 兜底 title 块）。
- 空正文：`KEEP_TITLE`（KB 语义）恒加 header 块；`KEEP_TITLE_IF_PRESENT`（NEWS 语义）仅 header 对应标题非空才加——由调用方传入「标题是否为空」的预判（NEWS 传 `title!=null && !title.isBlank()`），**不在公用器里解析 header**。
   - 简化：签名 `chunk(String header, String content, boolean titlePresent, boolean keepTitleWhenEmpty)`；当 content 空时：`keepTitleWhenEmpty && titlePresent` → 返回 `[header]`，否则空列表；当 content 非空时两域行为一致（都追加 header）。

### 1.2 `EmbedStats`

```java
public record EmbedStats(int total, int success, int failed) {}
```
`KbDocService.EmbedStats` 与 `ImageEmbeddingService.EmbedStats` 删除，改引用此共享 record（controller DTO 形态不变）。

### 1.3 `EmbeddingBatchRunner`

```java
@Component
public class EmbeddingBatchRunner {
    private final EmbeddingClient client;
    /**
     * @param textFn   条目 → 嵌入文本
     * @param persistFn 条目+向量 → 独立事务持久化（调用方已绑定 self 代理）
     * @param maxParallel 1 串行（KB），4（CAR/NEWS）
     * @param maxRetries  单条失败重试次数（CAR/NEWS=1，KB=0）
     */
    public <T> EmbedStats run(List<T> items, Function<T,String> textFn,
                              BiConsumer<T,String> persistFn,
                              String label, int maxParallel, int maxRetries);
}
```
- 内部：`Executors.newFixedThreadPool(min(maxParallel, max(1, n)))` + `invokeAll`，失败收集 `synchronizedList`，结束输出「成功 x/y 失败 seq/order」日志（沿用现有日志口径）。
- 网络 embed 在事务外；`persistFn` 是 REQUIRES_NEW 方法（见 §2）。
- `maxRetries=0` 时不重试；`maxParallel=1` 时仍走同一实现（可直接顺序执行以省线程池）。

## 2. 事务边界统一（R3）

现状 CAR/NEWS `insertDocWithEmbedding`（含 doc insert + embed + vector insert）标 `@Transactional protected` 但同类直调失效。改造为**网络调用与事务分离**：

```java
// 调用方（并发执行器内）：
String vec = embeddingClient.embed(doc.getChunkText());   // 网络，事务外
(self == null ? this : self).persistCarDoc(doc, vec);     // 独立事务

@Transactional(propagation = Propagation.REQUIRES_NEW)
public void persistCarDoc(CarDocEntity doc, String vec) {
    doc.setCreatedAt(now); doc.setUpdatedAt(now);
    docMapper.insert(doc);                                 // 拿 id
    embMapper.insert(doc.getId(), doc.getModelId(), vec, modelName);
}
```

- 效果：doc 行与向量行**同属一个 REQUIRES_NEW 事务**，向量插入失败则 doc 插入一并回滚（消除孤儿块）；且不加入调用方（`NewsService.upsertOne` 为 `@Transactional`）事务，失败不污染调用方。
- KB 同构：`KbDocService.persistChunk(KbChunkEntity, vec)` REQUIRES_NEW；embed 在外。
- 三域均加 `@Autowired @Lazy <Self> self`（IMAGE 先例；单测直 new 时 `self==null` 退化直调）。
- IMAGE 保持现有 `embedOne`/`persistVector`（已是此范式）。

## 3. 向量模型名列（R5）

### 3.1 配置

- `AiProperties`：新增 `private int embeddingDim = 1024;`。
- `application.yml`：`sparkora.ai.embedding-dim: ${AI_EMBEDDING_DIM:1024}`；`spring.flyway.placeholders.embeddingModel: ${AI_EMBEDDING_MODEL:Qwen3-Embedding-8B}`。
- `.env.example`：补 `AI_EMBEDDING_DIM=1024`（`AI_EMBEDDING_MODEL` 已存在）。

### 3.2 V3 迁移 `V3__embedding_model.sql`

```sql
ALTER TABLE sparkora_car_doc_embedding   ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE sparkora_kb_chunk_embedding  ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE sparkora_news_doc_embedding  ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
ALTER TABLE sparkora_image_embedding     ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100);
UPDATE sparkora_car_doc_embedding  SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
UPDATE sparkora_kb_chunk_embedding SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
UPDATE sparkora_news_doc_embedding SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
UPDATE sparkora_image_embedding    SET embedding_model = '${embeddingModel}' WHERE embedding_model IS NULL;
```
- 可空（不设 NOT NULL，避免极端数据导致迁移失败）；回填用 placeholder（存量行标记为**实际部署配置**模型）。
- 检索过滤用 `= #{model}`，NULL 行天然不匹配（安全方向：宁可漏检旧行，不混空间）。

### 3.3 写入盖模型名

4 个 insert mapper 增参 `embeddingModel`，写路径传 `embeddingClient.modelName()`（新增 `public String modelName()`）：
- `CarDocEmbeddingMapper.insert(docId, modelId, embedding, embeddingModel)`
- `KbChunkEmbeddingMapper.insert(chunkId, embedding, embeddingModel)`
- `NewsDocEmbeddingMapper.insert(docId, newsId, embedding, embeddingModel)`
- `ImageEmbeddingMapper.insert(imageId, embedding, sourceText, embeddingModel)`

### 3.4 检索按模型过滤

4 条查询增 `AND e.embedding_model = #{model}`；查询向量由 `embeddingClient.embed(query)` 产出（当前模型），并传 `modelName()`：
- `CarDocEmbeddingMapper.searchTopK`（per-model）
- `CarDocEmbeddingMapper.searchTopKUnified`（3 个子查询各加）
- `KbChunkEmbeddingMapper.searchTopK`
- `ImageEmbeddingMapper.searchTopK`
- 调用方 `CarRagService`（4 处传参）、`ImageEmbeddingService`（1 处）。

### 3.5 对账/补齐口径修正（防「陈旧向量被误判为已就绪」）

- `CarDocEmbeddingMapper.countByModel` 的 `embeddedCount` 只计**当前模型**行：`COUNT(e.id) FILTER (WHERE e.embedding_model = #{model})`，并加 `#{model}` 参数。
- `ImageEmbeddingMapper.findImageIdsWithoutEmbedding` 的 LEFT JOIN 条件加 `AND e.embedding_model = #{model}`，使 `rebuildMissing` 能补「只有旧模型向量」的图。

## 4. EmbeddingClient 校验（R7）

```java
public List<Double> embedList(String text) {
    ... 调用后 ...
    if (list.size() != props.getEmbeddingDim())
        throw new AiException("embedding 维度不符: 期望 " + props.getEmbeddingDim()
            + ", 实际 " + list.size() + "（模型 " + model + "）", null);
    return list;
}
public String modelName() { return props.getEmbeddingModel(); }
```

## 5. 启动对账 Runner（R7）

`com.sparkora.config.EmbeddingModelReconcileRunner implements ApplicationRunner`（`@Order` 置于其他 backfill runner 之后；Flyway 先于所有 ApplicationRunner）：
- 对 4 表执行 `SELECT embedding_model AS model, COUNT(*) AS cnt ... GROUP BY embedding_model`（各 mapper 新增一方法，或一个汇总查询）。
- 存在 `model != embeddingClient.modelName()` 的行 → `log.warn("向量表存在非当前模型的行: table=... model=... count=...（需重嵌）")`。
- 异常仅 warn，绝不阻断启动。

## 6. NEWS 手动重建端点（R6）

- `NewsDocService.rebuildForNews(Long)` 改为返回 `EmbedStats`（原 void）。
- `NewsController` 新增：
```java
@PostMapping("/{id}/rebuild")
@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")
public R<EmbedStats> rebuild(@PathVariable Long id) { ... }
```
- 错误映射沿用既有约定。

## 7. 影响面 / 兼容性

| 区域 | 变化 | 兼容性 |
|---|---|---|
| KB/NEWS 切块 | 委托共享器 | 产出逐块不变（AC4 断言） |
| CAR/NEWS rebuild | 换执行器 + REQUIRES_NEW | 对外仍 void/计数；失败策略不变 |
| KB rebuild | 换执行器（串行无重试） | 返回 EmbedStats 不变 |
| 向量表 | +embedding_model 列 | 可空+回填；旧客户端无影响 |
| 检索 | +模型过滤 | 存量行回填为当前模型 → 行为不变；换模型后旧行自动失效 |
| 配置 | +AI_EMBEDDING_DIM | 默认 1024 与现 DDL 一致，零行为变化 |
| 前端 | 无 | `npm run build` 回归 |

## 8. 回滚

- 代码回退即可恢复；V3 加列不删列，回退代码后新列闲置（无破坏）。
- 若需回收列：手写 V4 `DROP COLUMN`（本任务不提供）。

## 9. 测试设计

| 层 | 用例 |
|---|---|
| TextChunker | 复用 KB/NEWS 既有切块用例语义：KB 空正文→[header]；NEWS 空正文+无标题→[]、+有标题→[header]；超长段句读合并；硬切；换行转空格 |
| EmbeddingBatchRunner | maxRetries=1 首次失败重试成功；两次失败计 failed；maxParallel=1 顺序；计数/日志 |
| 三域 rebuild | 委托持久化被调用（verify self 路径）；embed 失败→doc 不落（REQUIRES_NEW 回滚，mock 断言） |
| EmbeddingClient | 维度不符抛 AiException；modelName() 返回配置 |
| mapper SQL | 检索含 `embedding_model = ?`（可用参数捕获/或隔离库集成） |
| V3 迁移 | 隔离容器：空库/既有库、回填、重启幂等（check 子代理执行） |

## 10. 验证命令

```bash
mvn -q -DskipTests compile
mvn test
cd frontend && npm run build
grep -rn "splitSentences" src/main/java          # 期望仅 TextChunker 一处
grep -rn "record EmbedStats" src/main/java       # 期望仅 ai.EmbedStats 一处
grep -rn "embedding_model" src/main/resources/db/migration/V3__embedding_model.sql
```
