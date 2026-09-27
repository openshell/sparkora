# 知识域写入侧统一 + 向量模型名防护 — 实施计划

> 顺序：M1 共享抽象+维度校验+模型名（编译面） → M2 迁移+写入盖名 → M3 检索过滤+对账 → M4 NEWS 端点 → M5 测试/文档。每步可独立编译。

## 前置检查

- [ ] `git status` 干净基线
- [ ] `mvn -q -DskipTests compile` 通过（改动前基线）
- [ ] 确认 `db/migration/` 仅 V1/V2，V3 未被占用

## Step M1: 共享抽象 + EmbeddingClient 校验/modelName

- [ ] 新增 `com.sparkora.ai.EmbedStats`（record）
- [ ] 新增 `com.sparkora.ai.TextChunker`（`chunk` + `splitSentences` + `MAX_BODY_LEN` + `EmptyBody`）
- [ ] 新增 `com.sparkora.ai.EmbeddingBatchRunner`（泛型并发执行器，支持 maxParallel/maxRetries）
- [ ] `AiProperties` 增 `embeddingDim`（默认 1024）；`application.yml` 增 `embedding-dim: ${AI_EMBEDDING_DIM:1024}`；`.env.example` 补 `AI_EMBEDDING_DIM`
- [ ] `EmbeddingClient`：`embedList` 维度校验；新增 `public String modelName()`
- 验证：`mvn -q -DskipTests compile`

## Step M2: KB/NEWS 切块委托 + 三域事务边界 + 迁移

- [ ] `KbDocService`：删本地 `chunkContent`/`splitSentences`/`EmbedStats`/`MAX_BODY_LEN`，改委托 `TextChunker`（KB header + KEEP_TITLE 语义）；rebuild 改走 `EmbeddingBatchRunner`（maxParallel=1,maxRetries=0）；新增 `@Lazy self` + `@Transactional(REQUIRES_NEW) persistChunk`；embed 移出事务
- [ ] `NewsDocService`：同构（NEWS header + KEEP_TITLE_IF_PRESENT 语义）；`rebuildForNews` 返回 `EmbedStats`；`persistNewsDoc` REQUIRES_NEW
- [ ] `CarDocService`：rebuild 改走 `EmbeddingBatchRunner`（maxParallel=4,maxRetries=1）；删失效 `@Transactional insertDocWithEmbedding`，改 embed 在外 + `persistCarDoc` REQUIRES_NEW + `@Lazy self`
- [ ] `ImageEmbeddingService`：`EmbedStats` 改引用共享 record
- [ ] 新增 `V3__embedding_model.sql`（4 表加列 + placeholder 回填）；`application.yml` 增 `spring.flyway.placeholders.embeddingModel`
- 验证：`mvn -q -DskipTests compile`

## Step M3: 写入盖模型名 + 检索过滤 + 对账

- [ ] 4 个 embedding mapper `insert` 增 `embeddingModel` 参数 → 4 条写路径传 `embeddingClient.modelName()`
- [ ] 4 条检索查询增 `AND e.embedding_model = #{model}` → 调用方（CarRagService 4 处、ImageEmbeddingService 1 处）传参
- [ ] `CarDocEmbeddingMapper.countByModel` 用 `FILTER (WHERE embedding_model = #{model})`；`ImageEmbeddingMapper.findImageIdsWithoutEmbedding` JOIN 条件加模型
- [ ] 新增 `EmbeddingModelReconcileRunner`（启动对账 WARN）
- 验证：`mvn -q -DskipTests compile` + `grep embedding_model` 覆盖 4 表

## Step M4: NEWS 手动重建端点

- [ ] `NewsController` 新增 `POST /api/news/{id}/rebuild`（ADMIN/EDITOR，返回 `EmbedStats`）
- 验证：`mvn -q -DskipTests compile`

## Step M5: 测试 + 文档 + 全量验证

- [ ] 新增/迁移测试：`TextChunkerTest`（KB/NEWS 两语义）、`EmbeddingBatchRunnerTest`、`EmbeddingClientTest`（维度校验）、三域 rebuild 自注入断言；确保既有 `KbDocServiceTest`/`NewsDocServiceTest` 切块用例全绿（可能需把断言目标改指向 `TextChunker`）
- [ ] 文档同步：`docs/spec/knowledge/{car,kb,news,qa}.md`、`docs/spec/retrieval.md`、`docs/README.md`、`.trellis/spec/backend/{database,ai-rag}-guidelines.md`、`db/migration/README.md`
- [ ] `mvn -q -DskipTests compile` / `mvn test` / `npm run build`
- [ ] grep 去重指标：`splitSentences` 单处、`record EmbedStats` 单处
- [ ] 隔离 pgvector 容器验 V3（空库/既有库/回填/重启幂等）——由 check 子代理执行

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| M1 | 共享器空正文语义与 NEWS 差异导致切块漂移 | 逐字对照 KB/NEWS 现有实现；测试锁死 |
| M2 | REQUIRES_NEW 自注入在单测无代理时 NPE | 沿用 IMAGE 的 `(self==null?this:self)` 退化 |
| M2 | V3 placeholder 回填错值（未走实际配置） | 校验 application.yml placeholder 绑定；既有库实测 |
| M3 | 检索加过滤后存量行未回填 → 命中骤降 | 回填保证同模型；空库/既有库均验 |
| 全局 | 单 commit 回退 | 代码回退；V3 列闲置不破坏 |

## 提交约定

- `feat(ai): 知识域写入侧统一(切块/并发嵌入/事务边界) + NEWS 重建端点`
- `feat(db): 向量表加 embedding_model 列 + 维度校验 + 检索按模型过滤（V3）`
- `docs(spec): 向量模型列/维度校验/重嵌入口契约同步`

## start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] 用户已确认最终规划摘要
