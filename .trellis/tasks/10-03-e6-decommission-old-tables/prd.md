# E6 旧向量表退役（消除双写）

## Goal

E1 引入 `vector_store` 单表后，旧 4 张 `sparkora_*_embedding` 表**仍在被双写**（每次 rebuild 都向
旧表 + store 各写一次），且仍被 `vector-stats` API、`EmbeddingModelReconcileRunner`、
`ImageEmbeddingService.rebuildMissing` 读取。本任务消除这套双写/双读债务并退役旧表，使向量层
单一只真源（`vector_store`），减少维护负担。

## Depends On

- **E1–E5** 全部完成（旧表与 store 已逐字节等价、切读路径稳定）。本任务是父任务收尾的一部分。

## Background（集成复核发现）

- 写路径双写：`CarChunkService`（`embMapper.insert/deleteByDocId`）、`KbDocService`、
  `NewsDocService`、`ImageEmbeddingService` 均同时写旧表 + store。
- 读路径仍依赖旧表：`CarModelService.vectorStats()`→`CarDocEmbeddingMapper.countByModel`；
  `EmbeddingModelReconcileRunner`→`EmbeddingModelStatsMapper`（4 表 GROUP BY）；
  `ImageEmbeddingService.rebuildMissing`→`ImageEmbeddingMapper.findImageIdsWithoutEmbedding`；
  `VectorStoreBackfillMapper`（E1 回填用，只读旧表，回填已完成→可退役）。
- 旧表向量与 store 逐字节等价（E1 阶段 A 已证）。

## Requirements

- 父 R8（单一只真源）、R7（契约不变）。
- **代码退役**：移除 4 域写路径对旧表的 insert/delete；`vectorStats`/reconcile/`rebuildMissing`
  改走 `vector_store`（按 metadata `domain`+`embeddingModel` 聚合/差集）。
- 删除旧 mapper（`CarDocEmbeddingMapper`/`KbChunkEmbeddingMapper`/`NewsDocEmbeddingMapper`/
  `ImageEmbeddingMapper`/`EmbeddingModelStatsMapper`/`VectorStoreBackfillMapper`）与
  `EmbeddingModelReconcileRunner` 的旧表实现（改为 store 版或删除）；
  `VectorStoreBackfillRunner` 退役（回填已完成）。
- `ImageEmbeddingService.rebuildMissing` 差集改查 store。
- **迁移 DROP**：新增 Flyway 迁移 DROP 旧 4 表（**仅当**代码退役 + 全绿 + 对拍后；不可逆，放最后）。
- `GET /api/car/models/vector-stats` 响应结构不变（改数据来源）。

## Acceptance Criteria

- [ ] 生产代码 grep 无旧 4 表 `*_embedding` 的读写（除 V1 基线/迁移脚本/文档）。
- [ ] `vectorStats` / reconcile / `rebuildMissing` 改走 store，语义/响应结构不变。
- [ ] 旧 mapper 与 `VectorStoreBackfill*` 删除；无残留引用。
- [ ] DROP 迁移落地，`flyway_schema_history` 正常；旧表不再存在。
- [ ] `mvn test` 全绿；检索/统计/对账端到端冒烟通过。
- [ ] 契约不变：`vector-stats` 响应、`RagResult`/`Citation` 结构不变。

## Out of Scope

- store 的进一步结构优化；rerank（D5）。
- 前端改动（`vector-stats` 消费方零改动）。

## Risks / Rollback

- DROP 不可逆：仅在前序步骤验证达标后执行；回退 = 由 `vector_store` 重建旧表（向量逐字节可复现，
  E1 已证）或 `git revert` DROP 迁移 + 从备份/重嵌恢复。
