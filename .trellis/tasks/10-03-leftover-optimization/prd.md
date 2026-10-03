# 遗留优化规划：rerank / 知识中心 UX / KB 运营

## Goal

规划并处理 10-03 向量层迁移（PgVectorStore + KB 规范化）时明确推迟的三项遗留债务：
检索重排（rerank）、前端知识信息架构收敛、KB 批量导入运营能力。

## Key Decisions

- **D1 推进方式** = 父任务 + 3 个可独立验收子任务（A rerank / B 知识中心 UX / C KB 批量导入），
  并行规划；依赖写在子任务内。
- **D2 遗留项 A** = **LLM 重排（ChatClient，axonhub 同 key）**。集成点/覆盖域/开关/降级/时延预算
  由 design.md 定（推荐：候选合并后、配额前重排；覆盖 CAR/KB/NEWS；NEWS 只重排窗口 top-N；
  默认关 + 可配 + 失败回退原序）。
- **D3 遗留项 B** = 除图库外所有知识类数据（车型/知识库/新闻）归入**知识中心**（含检索与管理）；
  知识中心 = 4 tab（车型 / 知识库 / 新闻 / 检索问答）+ 图库独立；`/car`、`/kb`、`/qa` 旧路由
  **移除（不重定向）**，`/car/:id` 详情保留；按 tab 做角色门控。
- **D4 遗留项 C** = **仅批量导入**（CSV/JSON/Markdown 批量新建 KB 文档）；不含审核流/版本管理。

## Background / Confirmed Facts

来源：10-03 向量层任务树（父 `10-03-vector-pgstore-kb`，子 E1–E6，已归档并部署产线）。

- 检索现状：`CarRagService.retrieveForGeneration` → `VectorStoreService.searchDomains`（Spring AI
  `PgVectorStore` 单表 `vector_store` + metadata filter）→ 纯向量余弦 top-K → 业务规则（锚点加权/
  分层配额/门槛/四态）。**无 rerank**。候选窗口按域隔离（CAR+KB 合并窗 / NEWS 独立窗）。
- 配置：`AI_RAG_MIN_SCORE`/`AI_RAG_REJECT_SCORE`/`AI_RAG_ANCHOR_BOOST`/`AI_RAG_KB_TOPK`/`AI_RAG_NEWS_TOPK`。
- embedding：`EmbeddingClient`（Spring AI `EmbeddingModel`，axonhub Qwen3-Embedding-8B，1024 维）。
- 前端导航（`frontend/src/constants/nav.js`）：项目/图库/风格库/知识中心/知识问答/车型库/知识库 并列。
  - `KnowledgeCenter.vue` 现有 tab：车型（`CarKnowledgePanel`）、新闻（`NewsKnowledgePanel`）。
  - `CarLibrary.vue`（`/car`）与知识中心「车型」tab **浏览同一 `carApi.list()` 数据**：前者可管理
    （同步/删除/重建向量），后者只读；职责重叠。新闻无独立导航（只在知识中心）。
- KB 现状（`KbLibrary.vue` + `/api/kb`）：单条 CRUD + `DELETE` + `POST /{id}/rebuild` + `GET /domains`；
  **无批量导入**。E3 已完成数据模型规范化（受控 `domain` + source/tags/生效期）。

## Requirements

- **R1** 为 A/B/C 各建可独立验收的子任务，父任务持有源需求、任务映射与跨子验收。
- **R2（A）** LLM rerank 层：可开关、失败降级回原序、时延/token 预算受控、与现有配额-门槛衔接、
  有 A/B 效果评估。
- **R3（B）** 知识信息架构收敛：知识中心 4 tab（含管理动作）；移除 `/car`、`/kb`、`/qa` 旧路由
  （`/car/:id` 保留）；导航与按 tab 角色门控；契约（后端 API）不变。
- **R4（C）** KB 批量导入：CSV/JSON/Markdown 多文档批量新建，复用 E3 normalize/切块/嵌入链路，
  失败逐条可诊断。

## Acceptance Criteria

- [ ] **AC-A1** rerank 可配置启停；关闭时行为与现状逐条一致（零回归）。
- [ ] **AC-A2** rerank 失败/超时自动降级回原序，不阻断生成（四态不变）。
- [ ] **AC-A3** 有 A/B 效果评估报告（代表 query 前后相关性/命中排序对比）。
- [ ] **AC-A4** 时延/token 预算有上限保护（超预算放弃重排）。
- [ ] **AC-B1** 知识中心含车型/知识库/新闻/检索问答 4 tab，浏览与管理动作齐备。
- [ ] **AC-B2** `/car`、`/kb`、`/qa` 旧路由移除且无残留引用；`/car/:id` 详情可用。
- [ ] **AC-B3** 按 tab 角色门控正确（浏览 vs 管理）；既有后端 API 契约不变。
- [ ] **AC-B4** 导航结构自洽（`nav.js` 与路由一一对应，AppShell dev-only 校验通过）。
- [ ] **AC-C1** 支持 CSV/JSON/Markdown 批量导入 KB 文档；逐条成功/失败结果可读。
- [ ] **AC-C2** 复用 E3 受控 domain/来源/标签/生效期 + 切块 + 嵌入；导入后向量入 store。
- [ ] **AC-C3** 非法/重复条目有明确处置（拒绝与归并策略可测），不产生孤儿文档。

## Out of Scope

- rerank 的本地 cross-encoder / GPU 服务（D2 选 LLM 重排）。
- KB 审核流 / 版本管理（D4 仅批量导入）。
- 图库入口调整（保持独立）。
- 后端检索打分/配额/四态规则语义变更（除重排插入外不改业务规则）。

## Task Map（父任务持有）

| 子任务 | 交付 | 依赖 |
|---|---|---|
| **A** rerank 重排层 | LLM 重排（ChatClient）+ 开关/降级/预算 + A/B | 无 |
| **B** 知识中心信息架构收敛 | 4 tab 知识中心 + 移除旧路由 + tab 角色门控 | 无 |
| **C** KB 批量导入 | CSV/JSON/Markdown 批量新建 + 逐条结果 | 无 |

三项相互独立、可并行；依赖（若有）写在子任务 `prd.md`/`implement.md`。

## Notes

- 本任务为规划父任务；实际实现由子任务 A/B/C 承担。
- 复杂任务 → 需 `design.md` + `implement.md`（父任务持有跨子验收与技术设计）。
