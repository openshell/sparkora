# C5 向量存储迁移 PgVectorStore

## Goal

把 4 张 embedding 表（car/kb/news/image）的"存储 + 相似度检索"迁移到 Spring AI `PgVectorStore`，
**保留业务 RAG 打分/配额/锚点/合并**，并以同 query 集对拍保证检索质量不回退。

## Depends On

- **C0**（Spring AI 2.0 + pgvector store starter）。

## Re-scope 决定（代码勘察后，见 research/c5-vector-store-mismatch.md）

现有检索**已是** raw pgvector（HNSW + `1-(embedding<=>vec)` + 按域候选窗口），且有效性依赖
**JOIN 活表**（`car_doc.deleted=0`/`kb_doc.enabled=TRUE`/…）。`PgVectorStore` 是 content 与
embedding 同表的模型，**无法表达 JOIN 活表语义**；全量替换需反规范化正文 + 新增活表同步层，
无法满足本任务 parity AC，且引入同步一致性风险。故本任务范围为：

- **交付（Scope A）**：embedding 客户端后端改由 Spring AI `EmbeddingModel` 实现（公共 API 不变）；
  保留 raw pgvector 检索 SQL 与 `CarRagService` 全部逻辑。
- **推迟（Scope B）**：`PgVectorStore` 表替换 + 反规范化 + 活表同步层，需产品显式决策另立任务。

## Requirements

- 父 R8（按上「Re-scope」收敛）。
- `com.sparkora.car.client.EmbeddingClient` 内部改用 Spring AI `EmbeddingModel`（OpenAI 兼容，
  指向 axonhub）；**公共 API 不变**（`embed`/`embedList`/`modelName`/`toPgVector`），调用点与测试零改动。
- 保留维度 fail-fast 校验（期望 `AI_EMBEDDING_DIM`，默认 1024）。
- **保留** `CarDocEmbeddingMapper`/`KbChunkEmbeddingMapper`/`ImageEmbeddingMapper` 检索 SQL 与
  `CarRagService`（锚点加权/按域配额/候选窗口隔离/置信度合并全部不动）。
- **不建** `vector_store` 表、**不新增** Flyway 迁移、不引 pgvector store 生命周期。

## Acceptance Criteria

- [ ] **对拍**（AC-向量迁移）：检索 SQL 与 `CarRagService` 未改动 → 同 query 集结果与迁移前
      **逐字一致**（parity 由「未改动」平凡成立）。
- [ ] `embedding_model` 防护未回归：4 张表写入盖名 + 检索过滤仍在（SQL 未动）。
- [ ] 锚点加权/多域配额/候选窗口隔离未回归（代码未动 + 既有测试全绿）。
- [ ] `EmbeddingClient` 后端为 Spring AI `EmbeddingModel`；公共签名不变；调用点零改动。
- [ ] `mvn test` 绿；Spring 上下文正常启动（`EmbeddingModel` bean 可注入）；无新迁移。

## Out of Scope

- FactSheet 归并算法、置信度策略、图片语义检索门槛语义。

## Risks

- 改写已验证的检索质量 → 对拍为唯一放行依据；旧表未删，可切回。
