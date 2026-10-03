# A 检索 rerank 重排层（LLM/ChatClient）

## Goal

在向量召回后插入 LLM 重排，提升检索相关性（当前纯余弦 top-K 无重排）。

## Depends On

无（可与 B/C 并行）。

## Requirements

- 父 R2、D2。
- 新增 `Reranker` 接口 + `LlmReranker`（Spring AI `ChatClient`，axonhub 同 key）；
  prompt 模板 `resources/prompts/rag/rerank-system.st`（C1 资产化范式）。
- 插入点：`CarRagService.retrieveForGeneration` 候选合并后、锚点加权/配额前。
- 覆盖 CAR/KB/NEWS；NEWS 采用**全局 top-N** 等价实现（design §3.1 允许的两种简单策略之一，
  无独立域窗口），已在 `docs/spec/retrieval.md` 与 `ai-rag-guidelines.md` 明确。
- 开关 `AI_RAG_RERANK_ENABLED`（默认 false）+ `AI_RAG_RERANK_TOPN`（默认 20）+ 超时预算。
- 失败/超时/超预算/不可解析 → **原序返回 + warn**，绝不阻断生成。

## Acceptance Criteria

- [ ] AC-A1 关闭态：检索行为与现状逐条一致（零回归，回归测试锁定）。
- [ ] AC-A2 失败/超时自动降级回原序，四态判定不变。
- [ ] AC-A3 `research/rerank-ab.md` A/B 评估（代表 query 前后相关性/排序对比）。
- [ ] AC-A4 时延/token 预算有上限保护（超预算放弃重排）。
- [ ] AC-A5 `.env.example` + `application.yml` 同步；`mvn test` 全绿。

## Out of Scope

- 本地 cross-encoder / GPU 服务；检索配额/锚点/门槛/四态语义变更。
