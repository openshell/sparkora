# 知识域写入侧统一 + 向量模型名防护

## Goal

1. **写入侧去重**：消除 CAR/KB/NEWS 三知识域（+ IMAGE 第四写入方）在切块、并发嵌入、向量持久化上的重复实现，顺带修复 CAR/NEWS `insertDocWithEmbedding` 的 `@Transactional` 因同类直调失效问题。
2. **向量模型名防护**：消除「更换 embedding 模型后旧向量静默污染检索」的缺口——4 张向量表当前不存模型名/维度，换模型（尤其同维 1024 换模型）后无检测、无隔离、无统一重嵌入口。

## Background（代码证据）

### 重复实现（真实重复）

| 重复点 | 证据 |
|---|---|
| **KB/NEWS 文本切块近逐行复制** | `KbDocService.chunkContent`+`splitSentences`（`kb/service/KbDocService.java:178-244`）vs `NewsDocService.chunkContent`+`splitSentences`（`news/service/NewsDocService.java:141-203`）。同算法、同 `MAX_BODY_LEN=500`（两处独立常量）。差异：①header 文本（`知识：title（domain）` vs `新闻：title（date）`）；②**正文为空的分支**：KB 恒加标题块，NEWS 仅标题非空才加、否则返回空列表。 |
| **CAR/NEWS 并发+重试嵌入模板重复** | `CarDocService.rebuildForModel`（`car/service/CarDocService.java:79-118`）vs `NewsDocService.rebuildForNews`（`news/service/NewsDocService.java:67-96`）：固定小线程池 + 单块重试 1 次 + synchronizedList 失败收集 + 计数日志，仅实体类型/字段名不同。 |
| **EmbedStats 记录重复** | `KbDocService.EmbedStats`（`KbDocService.java:53`）与 `ImageEmbeddingService.EmbedStats`（`service/ImageEmbeddingService.java:86`）同形。 |
| **4 份向量 mapper SQL 同构** | `CarDocEmbeddingMapper`(insert+deleteByDocId+deleteByModelId) / `KbChunkEmbeddingMapper` / `NewsDocEmbeddingMapper` / `ImageEmbeddingMapper`：均 `#{embedding}::vector` 手写注解 SQL + 各域 delete。 |
| **「先清后插」编排 4 份** | 各域 rebuild 各写一遍。 |

### 合理差异（统一时须参数化，不抹平）

- **CAR 切块是结构化切块**（按参数分组/权益 JSON，含 `cleanDisplay` 清洗），与 KB/NEWS 文本切块本质不同——**不动**。
- 失败策略：CAR/NEWS 并发+重试、KB 串行无重试、IMAGE 串行+REQUIRES_NEW 吞异常。

### 隐性缺陷

- `CarDocService.insertDocWithEmbedding`（`CarDocService.java:131-139`）与 `NewsDocService.insertDocWithEmbedding`（`NewsDocService.java:112-120`）标 `@Transactional protected`，但由同类线程池 lambda 内 `this` 调用 → **代理不生效，注解被忽略**。后果：embed 网络调用后若向量插入失败，doc 行可能已落而未回滚（孤儿块），事务边界不明确。
- 成熟范式可借鉴：`ImageEmbeddingService.embedOne`/`persistVector`（`service/ImageEmbeddingService.java:105-125`）——embed 在事务外 + 自注入 `@Lazy self` 走 `@Transactional(REQUIRES_NEW)`（`ImageEmbeddingService.java:66-68`，有回归测试 `ImageEmbeddingServiceTest`）。

### 模型防护缺口（系统性）

- 配置单一来源：`.env AI_EMBEDDING_MODEL`（`.env.example:55` 默认 `Qwen3-Embedding-8B`）→ `application.yml:53 embedding-model` → `AiProperties.embeddingModel`（`config/AiProperties.java:19`）→ `EmbeddingClient.embedList`（`car/client/EmbeddingClient.java:51-78`）。
- **4 张向量表均无 model/dim 列**（`V1__baseline.sql` `sparkora_car_doc_embedding`/`sparkora_kb_chunk_embedding`/`sparkora_news_doc_embedding`/`sparkora_image_embedding`），DDL 维度硬编码 `VECTOR(1024)`；V1 注释明说「**故不存模型名/维度列**，沿用最简形态」——本任务**推翻该决策**（见 Key Decisions）。
- `EmbeddingClient.embedList` **不校验返回向量维度**；插入直接 `#{embedding}::vector`。
- 换模型后果：维度≠1024 → pgvector insert 报错、块写入失败（无「模型维度不匹配」专门提示）；维度恰好=1024 但语义空间不同 → **静默混空间**，`searchTopKUnified` 继续跨域比较，无任何检测。
- **无统一重嵌入口**：CAR 有 `POST /api/car/models/rebuild-all`、KB 仅逐文档 `POST /api/kb/docs/{id}/rebuild`、IMAGE 有 `POST /api/images/embeddings/rebuild`、**NEWS 无任何手动重建端点**（`NewsController` 仅 list/get/sync/jobs）。
- 检索调用面（加模型过滤的落点）：`CarDocEmbeddingMapper.searchTopK`（per-model）/`searchTopKUnified`（`CarDocEmbeddingMapper.java:42,67-94`）、`KbChunkEmbeddingMapper.searchTopK`、`ImageEmbeddingMapper.searchTopK`；查询向量构造在 `CarRagService`(6 处) 与 `ImageEmbeddingService:247`。

## Requirements

### R1 文本切块器统一（KB/NEWS）

- 提取纯函数切块器（新类，如 `com.sparkora.ai.TextChunker`），参数化：`header`（首行锚点）、`emptyBodyBehavior`（空正文是否保留标题块，KB=保留 / NEWS=仅标题非空才保留）、`maxBodyLen`（统一常量 500）。
- `KbDocService.chunkContent`/`NewsDocService.chunkContent` 改为薄委托；保留各自 header 构造与空正文语义**逐字不变**（现有测试必须全绿）。

### R2 并发嵌入模板统一（CAR/NEWS）

- 提取通用执行器（如 `com.sparkora.ai.EmbeddingBatchRunner`）：固定线程池（`min(4, max(1, n))`）+ 单块重试 1 次 + 失败收集 + 计数日志，泛型于条目类型。
- `CarDocService.rebuildForModel` / `NewsDocService.rebuildForNews` 改为调用该执行器（per-item 写入仍为各域薄方法）。
- KB 保持串行（不改其失败策略），仅统一 `EmbedStats` 类型。

### R3 向量写入事务边界统一

- 三域统一为 IMAGE 范式：**embed 网络调用在事务外**，随后经自注入 `@Lazy self` 走 `@Transactional(REQUIRES_NEW)` 的持久化方法完成「插 doc（拿 id）+ 插 vector」原子写入；失败回滚不留孤儿块，且不污染调用方事务（NewsService.upsertOne 为 @Transactional）。
- 移除 CAR/NEWS 失效的 `@Transactional protected insertDocWithEmbedding` 写法。

### R4 EmbedStats 统一

- 单一 `EmbedStats(int total, int success, int failed)`（共享 record），三域与 IMAGE 复用。

### R5 向量模型名防护（推翻 V1「不存模型名」决策）

- `AiProperties` 增 `embeddingDim`（env `AI_EMBEDDING_DIM`，默认 `1024`）。
- Flyway **V3** 迁移：4 张向量表 `ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(100)`，回填存量行 = 当前配置模型（**用 Flyway placeholder `${embeddingModel}`**，由 `spring.flyway.placeholders.embeddingModel=${AI_EMBEDDING_MODEL:Qwen3-Embedding-8B}` 提供；避免把已部署库错标为固定默认值）。
- 4 个 insert mapper 增 `embeddingModel` 参数；4 条写路径统一盖当前配置模型。
- 4 条检索查询增 `AND embedding_model = #{model}` 过滤；查询向量同样用当前模型构造。
- 效果：换模型后，旧模型向量**不再参与检索**（不静默混空间）；重嵌后新向量带新模型标签自动生效。

### R6 NEWS 手动重建入口

- 新增 `POST /api/news/{id}/rebuild`（单篇，返回 `EmbedStats`），补 NEVS 缺失的手动重嵌能力（CAR/KB/IMAGE 已有）。

### R7 维度校验 + 启动对账

- `EmbeddingClient.embedList` 校验返回向量长度 `== embeddingDim`，不符抛 `AiException`（中文提示含实际/期望维度），fail-fast 于首次写入或查询。
- 新增启动对账日志（`ApplicationRunner`，晚于 Flyway）：查询 4 表 `DISTINCT embedding_model` 及计数，存在 ≠ 当前配置的模型时输出 WARN（列出模型名+条数），提示需重嵌。不阻断启动。

## Acceptance Criteria

- [ ] AC1 `mvn -q -DskipTests compile` 通过。
- [ ] AC2 `mvn test` 全绿；既有 `KbDocServiceTest`/`NewsDocServiceTest`（切块）用例**逐条不变仍通过**。
- [ ] AC3 `npm run build` 通过（前端预期零改动）。
- [ ] AC4 切块统一后，KB/NEWS 对同一输入的产出与改造前**逐块一致**（新增等价性测试或复用既有断言证明）。
- [ ] AC5 去重指标：`splitSentences` 全库仅一处定义；CAR/NEWS 并发模板仅一处；`EmbedStats` 单一定义（grep 证明）。
- [ ] AC6 事务：CAR/NEWS `insertDocWithEmbedding` 不再标 `@Transactional` 直接自调；改为 embed 在事务外 + `REQUIRES_NEW` 自注入持久化（测试断言自注入路径，参照 `ImageEmbeddingServiceTest`）。
- [ ] AC7 V3 迁移在隔离 pgvector 容器验证：空库 `V1→V2→V3`、既有库 `BSLN@1`+V2 后应用 V3；4 表 `embedding_model` 回填为配置模型；重启幂等。
- [ ] AC8 维度校验：mock 返回错误长度向量时 `EmbeddingClient` 抛 `AiException`。
- [ ] AC9 检索过滤：4 条查询含 `embedding_model` 条件；构造「旧模型行」在隔离库中不被检索命中。
- [ ] AC10 `POST /api/news/{id}/rebuild` 存在且返回 `EmbedStats`。
- [ ] AC11 文档/spec 同步：`docs/spec/knowledge/{car,kb,news,qa}.md`、`docs/spec/retrieval.md`、`docs/README.md`、`.trellis/spec/backend/{database,ai-rag}-guidelines.md` 更新向量模型列/维度校验/重嵌入口。

## Out of Scope

- **跨域一键重嵌编排**（「模型变更后一键重嵌全库」）——本任务只保证各域有入口 + 启动告警；编排另立任务。
- CAR 结构化切块算法（合法差异，不动）。
- 统一失败策略（保留各域现有并发/串行语义）。
- 向量维度变更时的自动重嵌（只做检测+隔离+告警，重嵌仍手动）。
- 向量表的 HNSW 索引/查询性能调优。
- P1 其余项（⑨ 拆巨石、⑩ 等）与 P2/P3。

## Key Decisions

- **推翻 V1「不存模型名」**：加 `embedding_model` 列。理由：同维换模型是真实风险且当前完全不可检测；per-row 标签是唯一能同时处理「全量换模型」与「部分重嵌」的方案。检索按当前模型过滤，旧行自然失效（可见降级而非静默污染）。
- **Flyway placeholder 回填**：存量行回填为**实际配置**模型（非硬编码默认），避免把既有库错标。
- **统一事务范式取 IMAGE 的 REQUIRES_NEW+自注入**：已成熟且有回归测试；顺带修 CAR/NEWS 失效注解。
- **维度校验 fail-fast**：新增 `AI_EMBEDDING_DIM`（默认 1024，与现 DDL 一致，不破坏现状）。写入/查询任一命中即报错，避免脏数据进入。
- **NEWS 只补手动重建端点**，不做跨域编排（控范围）。

## Open Questions

（无——决策项已按 Key Decisions 定案；若审核者要求缩小，可裁掉 R5/R6 的检索过滤或整体降级为「仅维度校验」，见 plan review）
