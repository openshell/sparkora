# C5 研究：PgVectorStore 与现有检索的结构性错配

> 结论：完整迁移到 Spring AI `PgVectorStore` 会破坏 C5 自身的 parity AC，故本任务只做
> 「embedding 后端 → `EmbeddingModel`」的安全部分，`PgVectorStore` 表替换列为 defer。

## 1. 现有检索是什么

- 4 张向量表：`sparkora_{car_doc,kb_chunk,news_doc,image}_embedding`，`VECTOR(1024)`，
  **HNSW `vector_cosine_ops`** 索引；查询用 raw pgvector 距离 `1 - (embedding <=> vec)`。
- 有效性依赖 **JOIN 活表**：
  - car：`JOIN sparkora_car_doc d ON d.id=e.doc_id AND d.deleted=0` + `JOIN car_model`；
  - kb：`JOIN kb_chunk` + `JOIN kb_doc d ON d.deleted=0 AND d.enabled=TRUE`；
  - news：`JOIN news_doc d AND d.deleted=0` + `JOIN news n AND n.deleted=0`。
- 多域统一检索 `CarDocEmbeddingMapper.searchTopKUnified`：**按域隔离候选窗口**
  （CAR+KB 合并 top-K / NEWS 独立 top-K），外层仅合并排序。
- 业务层（`CarRagService`）：锚点加权 `ragAnchorBoost`、按域配额 `ragKbTopk`/`ragNewsTopk`、
  块类型配额（RIGHTS/FEATURE ≤1/3）、表头块丢弃、来源标注、`RagResult` 四态。
- `embedding_model` 列：写入盖当前模型名 + 三段检索 `WHERE embedding_model = #{model}`（V3）。

## 2. PgVectorStore 的模型（为什么错配）

`PgVectorStore` = 单表 `vector_store(id uuid, content text, metadata json, embedding vector(d))`：
- **content 与 embedding 同表**：检索命中即返回 content，无 JOIN——要把 chunk 正文
  **反规范化**复制进向量表；
- **有效性靠 metadata filter**（`Filter.Expression`，转 PostgreSQL JSON path），无法表达
  「JOIN 活表」；
- 一个 store 一个表一个维度；跨域「按域独立窗口」需多次 `similaritySearch` 再合并。

## 3. 迁移的代价 / 为何破坏 parity

1. **活表语义丢失**：软删 doc / 停用 kb_doc / 删 news 后，`vector_store` 中的向量不会自动失效。
   必须新增**同步层**（每次 deleted/enabled 变更时删对应向量+其 chunk），漏一处即检索到已删内容。
   现有 JOIN 方案零同步、天然正确。
2. **候选窗语义不可逐字复现**：CAR+KB 合并窗 vs NEWS 独立窗的精确 top-K 边界、`embedding_model`
   过滤、HNSW 排序，用 metadata filter + 多次 search 难以逐字等价。
3. **数据搬运**：380+1339+179+3 行 chunk 正文需回填进 `vector_store` 并保持与源表同步。
4. 结论：**无法满足 AC-1「同 query 集对拍（命中集/分数/排序）达标」**。

## 4. 决定

- **保留** raw pgvector 检索 SQL 与 `CarRagService`（parity 平凡成立）。
- **迁移** embedding 后端到 Spring AI `EmbeddingModel`（真实框架价值：重试/观测/配置统一）。
- 不建 `vector_store` 表、不引其生命周期、不加迁移。

## 5. Defer 项（需产品显式决策，另立任务）

若未来确实要统一到 `PgVectorStore`，需一并决定：
- 接受「反规范化 content + 活表同步层」的复杂度与一致性风险，或改为软失效标记 + 定期重建；
- 是否放弃「逐字 parity」，改以「人工抽检 + 命中集 overlap 阈值」作为验收；
- 图片域（独立入口，179 向量）是否也统一。
