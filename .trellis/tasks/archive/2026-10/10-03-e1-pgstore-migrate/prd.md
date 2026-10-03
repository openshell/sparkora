# E1 PgVectorStore 迁移阶段A（单表+同步+对拍）

## Goal

把 4 域向量检索从手写 pgvector SQL 迁移到 Spring AI `PgVectorStore` 单表（`metadata.domain` 过滤），
在**切块算法不变**的前提下，做到同 query 集逐条对拍一致。这是整个父任务的**前置硬门**。

## Depends On

无（父任务第一个子任务）。E2/E3/E4/E5 均依赖本任务完成。

## Requirements

- 父 R1–R5、R7、R8。
- 建 store：单张表（`vectorTableName`），维度 1024、HNSW、COSINE，`initialize-schema` 由 Flyway 管理
  （不用 Spring 自动建表，或显式约定）。
- `content` = `chunk_text`；`metadata` = `{domain, refId(域内 docId/chunkId/imageId), modelId, chunkType,
  name, active, embeddingModel}`。
- 4 域回填 runner（复用 `EmbeddingBatchRunner` 范式，异步、`REQUIRES_NEW`、异常全吞、幂等差集）。
- **活表同步层**：软删/停用/重建时同步 metadata `active`。
- `embeddingModel` metadata 过滤。
- `CarRagService.retrieveForGeneration` 改走 store：每域一次 `similaritySearch(domain, topK=limit)` +
  Java 合并（保持按域隔离候选窗口 + 业务配额/锚点/四态不变）。
- 旧向量表**不删**。

## Acceptance Criteria

- [ ] AC-A1 阶段 A 对拍：同 query 集（CAR/KB/NEWS/IMAGE + 锚点 + 子查询）候选集/分数/排序一致。
- [ ] AC-A2 四态判定与配额后 selected 与旧路径一致。
- [ ] AC-A3 活表：软删/停用后 Store 立即不命中该块。
- [ ] AC-A4 换模型后旧模型块不被检索。
- [ ] AC-A5 `RagResult`/`Citation` 结构不变；`mvn test` 全绿。
- [ ] AC-A6 旧表未删，可切回旧检索。

## Out of Scope

- 切块变更（E2）、KB 规范化（E3）、命名（E4）、覆盖度/去重（E5）。
