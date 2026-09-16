# 技术设计：图片语义向量检索（子B）

## 架构与边界

```
写路径（增量）：
  ImageService.persistOrReuse ──> ImageEmbeddingService.embedQuietly(imageId)
                                     ├─ 构造嵌入文本（ImageEmbeddingTextBuilder）
                                     ├─ EmbeddingClient.embed(text)  ← 复用现有客户端
                                     └─ ImageEmbeddingMapper.insert / deleteByImageId（先清后插，幂等）

写路径（存量/重建）：
  ImageEmbeddingBackfillRunner（启动，仅补无向量的图，幂等）
  POST /api/images/embeddings/rebuild（ADMIN/EDITOR，全量重建，返回 EmbedStats）

读路径：
  POST /api/images/search ──> ImageEmbeddingService.searchImages(query, topK, minScore, tags)
                                ├─ 标签预过滤：ImageService 复用 resolveTagIds（AND 语义）
                                ├─ EmbeddingClient.embed(query)
                                ├─ ImageEmbeddingMapper.searchTopK（可选 image_id 白名单）
                                └─ 回填 url/thumbUrl/tags/sourceRef
```

新增组件：
- `com.sparkora.service.ImageEmbeddingService` — 向量化 + 检索编排（对标 `KbDocService` 的 rebuild 范式）
- `com.sparkora.mapper.ImageEmbeddingMapper` — 注解 SQL（对标 `CarDocEmbeddingMapper`，VECTOR 类型 BaseMapper 无法处理）
- `com.sparkora.image.embed.ImageEmbeddingTextBuilder` — 纯静态嵌入文本构造（可单测）
- `com.sparkora.config.ImageEmbeddingBackfillRunner` — 启动存量补齐
- `com.sparkora.domain.dto.ImageSearchHit` — 检索命中 DTO
- `com.sparkora.domain.dto.ImageEmbedDTO` — 检索请求 DTO（`query`/`topK`/`minScore`/`tags`）

修改：
- `ImageService`（注入 embeddingService，`persistOrReuse` 加钩子、`delete` 联动清向量、list 供检索复用）
- `AiProperties`（加 `imageMinScore`）
- `.env.example`（加 `AI_IMAGE_MIN_SCORE`）

## 数据模型（schema.sql，幂等）

```sql
-- 09-15 img-semantic-search:图片向量表(pgvector;Qwen3-Embedding-8B 1024 维,与 car/kb/news 三域同空间)
CREATE TABLE IF NOT EXISTS sparkora_image_embedding (
    id          BIGSERIAL PRIMARY KEY,
    image_id    BIGINT       NOT NULL,              -- 关联 sparkora_image_asset.id(不加 FK,沿用图库应用层维护惯例)
    embedding   VECTOR(1024) NOT NULL,              -- 与三域同维同模型
    source_text TEXT         NOT NULL,              -- 嵌入原文(调试 + 重建可追溯)
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- 一图一向量:唯一约束即幂等保证(重建先删后插,重复插入不可能)
CREATE UNIQUE INDEX IF NOT EXISTS uk_image_emb_image ON sparkora_image_embedding(image_id);
CREATE INDEX IF NOT EXISTS idx_image_emb_vec_hnsw ON sparkora_image_embedding
    USING hnsw (embedding vector_cosine_ops);
```

- **不加 FK**：与 `sparkora_image_tag` 一致，应用层维护；删图同事务物理清向量。
- **不加 `deleted`**：物理删，同 tag 表 / 三域 embedding 表。
- **不加 `model` 列**：三域中 `car_doc_embedding` 有 `model_id` 但那是车型外键，非模型名；KB 域无 model 列。沿用 KB 域最简形态。

## 嵌入文本构造（核心，`ImageEmbeddingTextBuilder`）

```java
/** 纯函数：图实体 + 已有标签 + 可选来源新闻标题 → 嵌入文本。 */
public static String build(ImageAssetEntity img, List<String> tags, String newsTitle)
```

按 `source` 分派：

| source | 文本构成 |
|---|---|
| `byd-news` | `newsTitle`（优先，来自 `source_ref` 反查）+ 标签（含 `主题/*`、`年份/*`） |
| `ai-text2img` / `ai-img2img` | `promptText` + 标签 |
| `upload` | `fileBaseName(fileName)` + 标签 |
| `byd` | `fileBaseName(fileName)` + 标签（含 `车型-*`） |

规则与边界：
- 各段用空格连接、去掉空段；trim 后整体为空 → 用 `fileName` 兜底（`"(图片 " + id + ")"`），**仍写向量**避免缺向量图搜不到。
- 标签拼为 `主题/销量 主题/出海` 原样（前缀在嵌入里可提供「销量」等关键词信号，无需剥前缀）。
- 长度上限 **2000 字符**截断（防超长输入打爆 embedding；标题+标签实际远小于此）。
- `newsTitle` 由 `ImageEmbeddingService` 在嵌入时按需反查（`source=byd-news && sourceRef 非空` 才查，单次 `selectOne`）；查不到则退化为只用标签。

> **为什么不在 `persistOrReuse` 传新闻标题**：让重建路径（`rebuildAll`）自给自足，无需调用方补上下文。代价是 byd-news 每图一次标题查询，规模 ~170 可接受。

## 向量化编排（`ImageEmbeddingService`）

对标 `KbDocService.rebuild` 范式：

```java
public record EmbedStats(int total, int success, int failed) {}

/** 单图向量化（重建/增量共用）：先物理删旧向量 → embed → 插入。失败抛异常。 */
public void embedOne(ImageAssetEntity img)

/** 入库后 best-effort 嵌入：捕获全部异常仅 warn，绝不影响图片入库。 */
public void embedQuietly(Long imageId)

/** 全量重建：遍历全部图片，逐图 embedOne，计数返回。幂等（先清后插）。 */
public EmbedStats rebuildAll()

/** 仅补缺失：遍历无向量的图，逐图 embedOne，返回计数。启动 runner 用。 */
public EmbedStats rebuildMissing()

/** 删图联动（ImageService.delete 调用）：物理清该图向量。 */
public void deleteByImageId(Long imageId)

/** 语义检索。tags 非空时先按标签 AND 缩候选（复用 ImageService.resolveTagIds）。 */
public List<ImageSearchHit> searchImages(String query, Integer topK, Double minScore, List<String> tags)
```

细节：
- `topK` 默认 **10**，上限 **50**；`minScore` 为 null 时用 `AiProperties.imageMinScore`（默认 0.3）。
- query 空/空白 → 抛 `IllegalArgumentException("检索内容不能为空")`。
- **标签预过滤 + 语义排序**：先用 `ImageService.resolveTagIds`（AND）得 imageId 集；空集直接返回空列表（省一次 embedding 调用）；非空则作为 `image_id = ANY(#{ids})` 白名单传入 SQL，在候选内做 HNSW 向量排序。
- 命中后回填：`fillDerived`（url/thumbUrl）+ `tagService.fillTags`（标签）+ `sourceRef`。
- 分数低于 `minScore` 的行在 SQL 外层过滤（或 Java 过滤，取 SQL 更省传输）。

## Mapper（注解 SQL，对标三域）

```java
public interface ImageEmbeddingMapper {
    @Insert("INSERT INTO sparkora_image_embedding (image_id, embedding, source_text, created_at) " +
            "VALUES (#{imageId}, #{embedding}::vector, #{sourceText}, CURRENT_TIMESTAMP)")
    int insert(...);

    @Delete("DELETE FROM sparkora_image_embedding WHERE image_id = #{imageId}")
    int deleteByImageId(...);

    /** 无向量的图片 id 集（rebuildMissing 用）。 */
    @Select("SELECT a.id FROM sparkora_image_asset a " +
            "LEFT JOIN sparkora_image_embedding e ON e.image_id = a.id " +
            "WHERE e.id IS NULL ORDER BY a.id")
    List<Long> findImageIdsWithoutEmbedding();

    /** 余弦检索 top-K；ids 非空时限定候选集。 */
    @Select("<script>SELECT e.image_id AS \"imageId\", e.source_text AS \"sourceText\", " +
            "1 - (e.embedding <=> #{queryVec}::vector) AS \"score\" " +
            "FROM sparkora_image_embedding e " +
            "<if test='ids != null and ids.size() > 0'> WHERE e.image_id IN " +
            "<foreach item='i' collection='ids' open='(' separator=',' close=')'>#{i}</foreach></if> " +
            "ORDER BY e.embedding <=> #{queryVec}::vector LIMIT #{limit}</script>")
    List<Map<String, Object>> searchTopK(...);
}
```

- **不用 JOIN 主表**：主表字段由 service 批查回填（`selectBatchIds`），避免 JOIN 与 `deleted`/软删耦合（image_asset 无逻辑删除，物理删）。
- `image_id IN (...)` 白名单：标签候选集 ≤500（沿用 `ImageService` 截断保底），HNSW 在 IN 过滤下仍可用。

## 接口契约

### `POST /api/images/search`（ADMIN/EDITOR/VIEWER）

请求：
```json
{ "query": "销量海报", "topK": 10, "minScore": 0.35, "tags": ["主题/销量", "年份/2026"] }
```
- `query` 必填非空；`topK`/`minScore`/`tags` 可选。
- `tags` AND 语义（与 `GET /api/images` 一致）。

响应 `R<List<ImageSearchHit>>`：
```json
{ "code": 0, "data": [
  { "imageId": 37, "score": 0.62, "sourceText": "比亚迪7月份销售41.9万辆… 主题/销量 年份/2026",
    "fileName": "news-...detail632.jpg", "source": "byd-news",
    "sourceRef": "/page/byd-cn/news-2026/detail632",
    "url": "https://...", "thumbUrl": "https://...",
    "tags": ["主题/销量", "年份/2026", "新闻"] } ] }
```

校验与错误矩阵：

| 条件 | 行为 |
|---|---|
| 未登录 | 401（Security 默认） |
| VIEWER 调用 | 200（三角色可读） |
| `query` 空/空白 | 400 `R.fail(400,"检索内容不能为空")` |
| `topK` > 50 / < 1 | 收敛为 50 / 默认 10（不报错） |
| `tags` 无交集成空集 | 200 `data: []`（不查向量库） |
| 模型未配置（`AI_EMBEDDING_MODEL` 空） | 500 `R.fail(500,...)`，消息含「未配置」 |
| embedding 调用失败 | 500 `R.fail(500,...)` |

### `POST /api/images/embeddings/rebuild`（ADMIN/EDITOR）

- 无 body；全量重建，返回 `R<EmbedStats>` → `{total, success, failed}`。
- 幂等：重复调用结果稳定（先清后插 + 唯一约束）。
- 失败图不阻断整体，计数 + 日志原因。

## 接入点改动

| 位置 | 改动 |
|---|---|
| `ImageService.persistOrReuse` | 新图插入后 + 去重命中后，均调 `embeddingService.embedQuietly(id)`（best-effort，异常不影响入库） |
| `ImageService.delete` | `tagService.deleteByImageId` 之后加 `embeddingService.deleteByImageId(id)` |
| `ImageService.list` | 暴露 `resolveTagIds` 供检索复用（已是 package-private static，改为 public static 或经 `ImageEmbeddingService` 直接调 `tagService.imageIdsByTag`） |
| `AiProperties` | 加 `private double imageMinScore = 0.3;` |
| `.env.example` | 加 `AI_IMAGE_MIN_SCORE=0.3` 注释段 |
| `ImageTagBackfillRunner` | 不改；向量补齐由新 `ImageEmbeddingBackfillRunner` 承担（职责分离） |

## 兼容与迁移

- 纯增量：新表 + 新接口；不改三域表结构、不改三域检索 SQL。
- `persistOrReuse` 钩子为 best-effort：`embedQuietly` 捕获所有异常 → 既有入库链路（含新闻同步、图库上传、AI 生图）行为不变。
- 存量补齐幂等：`rebuildMissing` 只处理 `e.id IS NULL` 的图；重跑零副作用。
- 唯一索引 `uk_image_emb_image` 是幂等兜底：即便并发重复嵌入也只留一行（第二插入报唯一冲突 → `embedQuietly` 吞掉并 warn）。

## 权衡记录

- **嵌入文本用「标题+标签」而非纯标题**：标签是人工/规则沉淀的结构化信号（`主题/销量`），把「销量」二字带入文本能显著提升「销量海报」类查询命中；且与子A 的标签体系形成正向循环。
- **一图一向量（唯一索引）而非多向量**：图片描述文本单一，多向量无收益；唯一索引让幂等免费获得。
- **标签用 AND 预过滤而非「标签+语义」加权**：与 `GET /api/images` 的标签语义保持一致（用户已认可 AND），避免同一筛选词在两个入口语义不同。
- **`embedQuietly` 吞异常**：图片可入库优先于可检索；缺失向量可由 rebuild 补齐（与 KB 域「单块失败 warn 跳过 + 计数」同哲学）。
- **启动补齐而非手动触发**：存量 157 张需一次性补齐才能在子C/D 立即可用；runner 幂等，代价可控。若启动期 embedding 服务不可用，runner 失败仅 warn，不阻断启动，可事后调 rebuild 接口。
- **不加 `model`/维度列**：与三域同模型是硬约束（同一向量空间才有意义），列存模型名只会制造「不同模型混检索」的错觉。

## 回滚

- 前端无改动（本任务纯后端）。
- 后端回滚：还原 Java 文件；表留存无害（不被引用）。
- 数据回滚：`DROP TABLE IF EXISTS sparkora_image_embedding;`（含索引）。
- 关闭能力：`AI_EMBEDDING_MODEL` 置空 → 嵌入/检索调用失败（有明确报错），但图片入库（`embedQuietly`）不受影响。

## 风险

- **首启 runner 期间 embedding 服务慢**：157 图 × 单次调用，可能启动期耗时数十秒。缓解：runner 异步（`@Async` 或独立线程）或在日志输出进度；不阻断应用就绪。
- **HNSW + IN 白名单性能**：候选集 ≤500，图库总量 ~170，实际影响可忽略；规模增长后若 IN 集过大，可改为「先向量 top-N 再 Java 端按标签过滤」。
- **`source_text` 与标签漂移**：用户改标签后向量未更新 → 检索文本与实际标签不一致。缓解：本期记录为已知限制，重建接口可修正；如需实时，可在标签变更后触发重嵌（留给后续任务）。
