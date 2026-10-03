# E5 覆盖度三域统一 + 去重缓存

## Goal

把 `coveredText` 从「仅 CAR PARAM_GROUP」扩展到 CAR/KB/NEWS；并消除相同 chunk 文本的重复嵌入。

## Depends On

- **E1**（store 迁移）、**E2**（切块稳定）。

## Requirements

- 父 R6（#5、#6）。
- `coveredText` 覆盖 CAR/KB/NEWS 的等价「已覆盖事实」声明（语义与现有 CAR 口径对齐）。
- 内容去重/嵌入缓存：相同 chunk 文本（`content_hash` 先例）复用向量，避免重复嵌入。
- 缓存位置/失效策略明确（同模型才复用）。

## Acceptance Criteria

- [ ] `coveredText` 三域生效并有单测；prompt 注入语义不破坏现有防编造契约。
- [ ] 相同文本重复入库只嵌入一次（可观测：嵌入调用数下降）。
- [ ] 换模型后缓存不复用旧模型向量。
- [ ] `mvn test` 全绿。

## Out of Scope

- rerank（D5）。
