# 现状链路调研(kb-generalize 前置,2026-09-04)

## 检索与生成链路(S6.2 后)

- `CarRagService.retrieveForGeneration(modelIds, query, topK)`:
  - modelIds 空 → 直接 `RagResult.EMPTY`(NO_KNOWLEDGE)——**通用主题零知识**,本任务要改的短路点。
  - 每车型:主查询 `retrieveTyped(modelId, topic, topK)` + `deriveSubQueries(query)` 参数级子查询,chunkText 去重合并。
  - 块类型配额 + 零信息兜底(仅 1 换行的表头块丢弃);minScore=0.3 逐块,rejectScore=0.5 整体。
  - 状态:双域合并逻辑见 design §3.2;FAILED 优先;coveredText 仅统计参数块(「参数名→值」≤400 字)。
- 调用方:`BriefService`(L96)/`VersionService`(L120)均为 `modelIds.isEmpty() ? RagResult.EMPTY : ragService.retrieveForGeneration(...)`。
- prompt 注入:rag.status 分支(FAILED/LOW_CONFIDENCE 各有降级提示文案);OK 时 context 进 prompt,factRisks 机制承接。
- 检索 SQL:`CarDocEmbeddingMapper.searchTopK(modelId, queryVec, limit)` — `WHERE model_id=#{modelId} ORDER BY embedding <=> CAST(#{queryVec} AS vector)`;KB 版需去 model_id 条件。

## 可复用资产

- 块类型配额逻辑与 `TypedHit`;子查询派生 `deriveSubQueries`;RagStatus 四态与前端展示位(无前端改动即可复用)。
- embedding 客户端 `EmbeddingClient.embed/embedList`(Qwen3-Embedding-8B,1024 维);向量字面量 `toPgVector`。
- 测试假件:`CarRagServiceTest` 的 FakeEmbeddingClient/FakeMapper 手写模式(JDK21 动态代理下 Mockito 不稳,勿改回 mock)。

## 车型域表结构参考(schema.sql S6 区块)

- `sparkora_car_doc(id, model_id, group_id, chunk_type, chunk_text, sort_order, created_at, updated_at, deleted)`。
- `sparkora_car_doc_embedding(id, doc_id, model_id, embedding vector(1024), created_at)` + ivfflat lists=100。
- KB 三表对齐此形态但以 doc 为根(无 model_id),见 design §2。
