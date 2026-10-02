# C5 向量存储迁移 PgVectorStore

## Goal

把 4 张 embedding 表（car/kb/news/image）的"存储 + 相似度检索"迁移到 Spring AI `PgVectorStore`，
**保留业务 RAG 打分/配额/锚点/合并**，并以同 query 集对拍保证检索质量不回退。

## Depends On

- **C0**（Spring AI 2.0 + pgvector store starter）。

## Requirements

- 父 R8。
- 每域建 store：维度 **1024**、HNSW、COSINE、自定义表名（`vectorTableName`）。
- **`embedding_model` 防护硬约束**：现有 V3「写入盖名 + 检索过滤」必须在 store 的 metadata 中承载
  （`embedding_model` 作 metadata + `filterExpression`），**不得回归**，防同维换模型静默混空间。
- 回填 runner：复用现有 `rebuildAll/rebuildMissing` 范式，**异步**（守护线程）+ `REQUIRES_NEW`
  事务隔离 + 异常全吞 + `@Order` 固定依赖序。
- **保留自研**：锚点加权 `ragAnchorBoost`、按域独立配额 `ragKbTopk`/`ragNewsTopk`、候选窗口隔离、
  置信度/跨源合并（`FactSheetService`/`ClaimSimilarity`）。`VectorStore` 只替换存储+检索层。
- 图片域独立入口（不并入 `searchTopKUnified`）。
- **迁移分两次**：先建/回填，后切读路径；旧表/列在达标后由**后续**迁移再删（本任务先不删）。

## Acceptance Criteria

- [ ] 迁移前后**同 query 集对拍**（命中集/分数/排序）达标（AC-向量迁移）。
- [ ] 跨 `embedding_model` 的向量不混空间（硬验收，含对账/补齐口径同步）。
- [ ] 锚点加权/多域配额/候选窗口隔离不回归。
- [ ] 回填失败不阻断启动（仅 warn），可重跑幂等。
- [ ] `mvn test` 绿；`flyway_schema_history` 正常。

## Out of Scope

- FactSheet 归并算法、置信度策略、图片语义检索门槛语义。

## Risks

- 改写已验证的检索质量 → 对拍为唯一放行依据；旧表未删，可切回。
