# C5 执行计划 — 向量层 Spring AI 化（安全范围）

## 决策（Re-scope，依据代码勘察）

勘察结论：现有检索**已是** raw pgvector（HNSW 索引 + `1-(embedding<=>vec)` 余弦 +
按域候选窗口），且**有效性依赖 JOIN 活表**（`car_doc.deleted=0` / `kb_doc.enabled=TRUE` /
`news_doc.deleted=0`）。Spring AI `PgVectorStore` 是「content 与 embedding 同表、metadata 用
JSON、按 metadata filter」的模型，**无法表达 JOIN 活表语义**；替换需把 chunk 正文反规范化进
`vector_store`，并在每次软删/启停时同步删除——引入大面同步一致性风险，且**无法满足 C5 自身的
「同 query 集对拍（命中集/分数/排序）达标」AC**（候选窗语义无法逐字复现）。这与本项目既有裁定
一致（`database-guidelines.md`：JSON 保持 TEXT、不为统一强行 UNION 异构结果——verified 专用
SQL 优于被迫套用通用抽象）。

故 C5 采用**安全增量**范围，保留已验证检索，交付真正适配框架的部分：

### Scope A（本任务交付）
1. **embedding 后端 → Spring AI `EmbeddingModel`**：`com.sparkora.car.client.EmbeddingClient`
   内部改由 Spring AI `EmbeddingModel`（OpenAI 兼容，指向 axonhub）实现，**公共 API 不变**
   （`embed(String)` / `embedList(String)` / `modelName()` / `static toPgVector(List<Double>)`），
   使 10 个调用点与既有测试零改动。保留维度 fail-fast 校验（期望 `AI_EMBEDDING_DIM`）。
2. **检索 SQL 保留**：`CarDocEmbeddingMapper` / `KbChunkEmbeddingMapper` / `ImageEmbeddingMapper`
   及 `CarRagService` 的按域窗口/锚点/配额逻辑**不动**（parity 天然成立）。
3. **不建 `vector_store` 表、不新增 Flyway 迁移、不引 pgvector store 生命周期**。

### Scope B（本任务外，另立跟进）
- 完整 `PgVectorStore` 表替换 + 反规范化 + 活表同步层：需产品显式决策（可能牺牲 parity 或承担
  同步风险）。写入 `research/c5-vector-store-mismatch.md` 存档。

## 执行清单
- [ ] `EmbeddingClient` 内部改用 `EmbeddingModel`（构造注入 `ObjectProvider<EmbeddingModel>` 或
      `EmbeddingModel`，单测可注入 stub）；公共签名不变。
- [ ] 维度校验：`EmbeddingModel.embed(text)` 返回 `float[]`，长度须等于 `AI_EMBEDDING_DIM`（默认 1024），
      不符抛 `AiException`（保留旧 fail-fast 语义）。
- [ ] 批量/单条：`embedList` 返回 `List<Double>`（与现有 `embed` 拼接 pgvector 字面量一致）。
- [ ] `modelName()` 仍返回 `AiProperties.getEmbeddingModel()`（写入/检索过滤同源，零改动）。
- [ ] 单测：stub `EmbeddingModel` 断言 `embed`→pgvector 字面量、`embedList` 维度校验、`modelName`
      透传；保留既有 `EmbeddingClientTest` 行为（如走旧 HTTP stub 则改为注入 EmbeddingModel）。
- [ ] `research/c5-vector-store-mismatch.md` 记录 mismatch 与 defer 理由。

## 验证
- `mvn -q -DskipTests compile`
- `mvn test`（C4 基线 622，预期 ≥622 全绿）
- `grep EmbeddingClient` 调用点零改动（签名不变）
- 对拍（AC-1）：检索 SQL 未改动 → 同 query 集结果与迁移前逐字一致（parity 平凡成立）

## 回退
- 仅改 `EmbeddingClient` 单文件内部实现 + 测试；`git revert` 即回退。

## 风险
- `EmbeddingModel` 自动配置的 base-url 须含 `/v1`（C1 已确立，`spring.ai.openai.*` 已配）。
- 若单测原用本地 HTTP stub 直连 `/v1/embeddings`，改为注入 stub `EmbeddingModel` 更稳定。
