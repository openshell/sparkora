# 图片语义向量检索（子B）

## Goal

让图片可被**自然语言检索**（「给我看销量海报」「出海签约的照片」），为子C（文章自动配图）与子D（问答语义配图）提供检索能力。在子A 的标签与来源追溯之上，补一层向量语义层。

## Dependencies

- **前置**：`09-15-img-classify`（子A）必须完成——本任务用其产出做嵌入文本：
  - 新闻图嵌入文本 = 新闻标题 + 主题/年份标签（依赖 `source_ref` 反查标题 + 标签）
  - `source_ref` 与标签命名空间（`主题/`、`年份/`）是嵌入与过滤的输入
- **被依赖**：子C（文章自动配图）、子D 语义路径。
- 若子A 未完成，本任务无法取到稳定的嵌入文本，**不得提前开始**。

## Background（09-15 勘察）

- `EmbeddingClient` 已具备：Qwen3-Embedding-8B，1024 维，OpenAI 兼容 `/v1/embeddings`，`embed(text)` 返回 pgvector 字面量、`embedList(text)` 返回 `List<Double>`。
- pgvector + HNSW cosine 已在三域跑通，且三域（car/kb/news）**同一向量空间**：`sparkora_car_doc_embedding`、`sparkora_kb_chunk_embedding`、`sparkora_news_doc_embedding` 均 `vector(1024)` + HNSW `vector_cosine_ops`。
- 图库规模小（当前约 170 张），全量嵌入成本极低。
- 图片本身无可嵌入文本，必须**用描述性文本代理**（标题/标签/prompt/文件名）。

## Requirements

### R1 图片向量表（与三域同构）

- 新建 `sparkora_image_embedding`：`id`、`image_id BIGINT`、`embedding VECTOR(1024)`、`source_text TEXT`（嵌入原文，便于调试与重建）、`created_at`。
- 索引：`image_id` 普通索引；`embedding` HNSW `vector_cosine_ops`（与三域一致）。
- **同一 embedding 模型/维度**，与现有三域同空间，可被统一检索复用；不引入新模型、不引入外部向量库。

### R2 嵌入文本构造（按来源分派）

| 来源 | 嵌入文本 |
|---|---|
| `byd-news` | 来源新闻标题 + 主题标签（如「比亚迪7月份销售41.9万辆 海外销售近18万… 主题/销量 主题/出海 年份/2026」） |
| `ai-text2img` / `ai-img2img` | `prompt_text` |
| `upload` | `file_name`（去扩展名） |
| `byd`（车型图） | `file_name` + 车型标签（如「车型-海豹」） |

- 文本为空/过短（如纯数字文件名）时：仍嵌入（避免缺向量），但检索时依赖相似度门槛过滤。
- 文本长度上限（如 2000 字符）截断，避免超长输入。

### R3 向量化时机

- **增量**：图片入库时（`ImageService.persistOrReuse` 成功插入后）异步或同步嵌入；失败仅告警，不阻断图片入库（图片可用性优先于可检索性）。
- **存量 + 重建**：提供重建接口，遍历图片重新嵌入（先物理清该图旧向量再插，幂等）；覆盖「增量嵌入失败」「词表调整后标签变化」「新增图片」三种补齐场景。

### R4 检索接口

- 服务层：`searchImages(queryText, topK, minScore)` → 返回 `[{imageId, score, url, thumbUrl, tags, sourceRef, sourceText}]`。
- 查询向量 cos 相似度排序 + 门槛过滤（门槛可配，参照三域 `AI_RAG_MIN_SCORE` 惯例新增 `AI_IMAGE_MIN_SCORE`）。
- 支持**标签预过滤 + 语义排序**（先按标签 AND 缩候选，再向量排序），用于「主题/销量」范围内语义搜。
- 接口：`POST /api/images/search`（三角色）body `{query, topK?, minScore?, tags?[]}`；响应 `R<List<...>>`。
- **复用现有检索写法**：参照 `searchTopKUnified` 的子查询 + HNSW，不新造范式。

### R5 可观测

- 重建接口返回 `{total, success, failed}`（参照 KB 域 `EmbedStats` 先例）。
- 日志输出嵌入失败原因与计数，不静默丢向量。

## Non-goals

- 不做以图搜图（视觉相似），只做以文搜图。
- 不做多模态模型（不引入 CLIP 类图像 embedding）。
- 不做向量检索的 rerank 模型。
- 不做自动配图链路本身（属子C/子D）。

## Acceptance Criteria

- [ ] `sparkora_image_embedding` 建表（`VECTOR(1024)` + HNSW cosine）幂等；与三域同维度同距离函数。
- [ ] 新入库图片自动产生向量（四来源各自的嵌入文本规则生效）；嵌入失败不阻断图片入库，有日志。
- [ ] 存量图片可通过重建接口补齐向量；重建幂等（重跑结果稳定，无重复行）。
- [ ] `POST /api/images/search` 语义检索可用：查「销量海报」能命中销售类新闻图，且分数排序合理。
- [ ] 支持带标签预过滤的语义检索（如限 `主题/销量` 内再排序）。
- [ ] 相似度门槛生效：低于门槛的结果被过滤；门槛可配。
- [ ] 重建接口返回成功/失败计数；失败有原因日志。
- [ ] 权限：检索接口三角色可读；重建接口 ADMIN/EDITOR。
- [ ] 零回归：车型/KB/新闻三域既有检索行为不变；不修改三域表结构。
- [ ] `mvn -q -DskipTests compile` 通过；`docs/s0-spec.md` 检索章节同步（图片向量域 + 接口契约）。

## Notes

- 复杂任务：需要 `design.md`（表结构/嵌入文本构造/检索 SQL/重建策略）+ `implement.md`。
- 图片量小，可先做**全量重建 + 同步嵌入**，不必引入消息队列或异步编排；规模增长后再考虑。
