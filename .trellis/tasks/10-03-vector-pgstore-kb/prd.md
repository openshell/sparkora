# 向量层 PgVectorStore 迁移 + 知识库规范化

## Goal

把当前自研的 4 域 pgvector 检索（手写注解 SQL + JOIN 活表）全量迁移到 Spring AI
`PgVectorStore`（单表 + `metadata.domain`），并随迁移修复知识库质量债，最终获得
「框架统一、少自研 SQL、关系完整性不丢」的检索层。用户已明确：**先迁 PgVectorStore，再修质量债**。

## Key Decisions

- **D1 拓扑 = 单张 `vector_store` + `metadata.domain` 过滤**。已探测确认 4 语料
  （CAR 380 / NEWS 1339 / KB 3 / IMAGE 179）无数据重复，4 张向量表同形状，适合合并单表。
- **D2 KB 规范化 = 仅数据模型**（受控 `domain` 词表 + `source`/`tags`/生效期列；写入侧规范化、
  检索侧可过滤）。不含批量导入/审核流/版本管理/前端管理页。
- **D3 切块滑动重叠 = 纳入本次**，随迁移一并全库重嵌（car 380/news 1339/kb 3/image 179）。
- **D4 验收 = 分两阶段对拍**。阶段 A（仅搬存储、切块不变）同 query 集逐条对拍必须一致；
  阶段 B（切块重叠 + 重嵌）验证语义合理性与改进。
- **D5 #4 rerank = 本次不做**，待迁移稳定后另立任务（引入 LLM/模型成本与时延）。

## Background / Confirmed Facts（代码勘察，2026-10-03）

当前架构（关系源 + 向量二级索引，**非反规范化**）：

```
doc 主表 (car_model / kb_doc / news / image_asset)
  └─ chunk 表 (sparkora_car_doc / kb_chunk / news_doc)   ← chunk_text / chunk_type
       └─ embedding 表 (*_embedding)                     ← vector(1024) + embedding_model
```

- 4 张向量表：`sparkora_{car_doc,kb_chunk,news_doc,image}_embedding`，`VECTOR(1024)`、
  HNSW `vector_cosine_ops`、`embedding_model VARCHAR(100)`（V3 加列，写入盖名 + 检索过滤）。
  形状几乎一致（`id + FK + embedding + embedding_model + created_at`），但：
  CAR/NEWS 冗余父表 FK（`model_id`/`news_id`），KB 不冗余；仅 IMAGE 在向量表内存 `source_text`。
- 检索入口 `com.sparkora.car.service.CarRagService.retrieveForGeneration`：
  `CarDocEmbeddingMapper.searchTopKUnified`（CAR+KB 合并窗 / NEWS 独立窗 UNION ALL）；锚点加权
  `ragAnchorBoost`；分层配额（PARAM_GROUP/MODEL_INFO 优先, RIGHTS/FEATURE ≤1/3, KB 独立 `ragKbTopk`,
  NEWS 独立 `ragNewsTopk`）；四态 `OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE`；`coveredText` 仅 CAR PARAM_GROUP。
- Embedding 后端已由 C5 切到 Spring AI `EmbeddingModel`（`EmbeddingClient`）；检索 SQL 仍手写 pgvector。
- Spring AI `PgVectorStore`：单表 `vector_store(id uuid, content text, metadata json, embedding vector)`；
  支持 metadata filter；`initialize-schema` 默认 false、**维度建表固化**（换模型需重建表）。
  starter 已引入（C0），bean 惰性存在、未建表。
- 前端入口重叠（**UX 债，非本次迁移必需**）：「知识中心/车型 tab」与「车型库」浏览同一 `carApi.list()`
  数据（前者只读、后者可管理）；新闻无独立导航（只在知识中心）；KB 仅「知识库」入口。
- 数据量与重嵌成本：car 380 / news 1339 / kb 3 / image 179。

已识别六项质量债（与是否迁移无关，属独立债务）：

| # | 债务 | 处置 |
|---|---|---|
| 1 | `sparkora_car_doc` 实为车型**块**表，命名失真 | 本次（E4） |
| 2 | KB 欠发育：`domain` 自由文本、无来源/标签/生效期 | 本次（E3） |
| 3 | 切块无滑动重叠 | 本次（E2） |
| 4 | 无 rerank | 另立任务（D5） |
| 5 | `coveredText` 仅覆盖 CAR PARAM_GROUP | 本次（E5） |
| 6 | 无内容去重/嵌入缓存 | 本次（E5） |

## Requirements

- **R1** 4 域检索全量迁移到 Spring AI `PgVectorStore`（单表 + `metadata.domain`），替换
  `CarRagService` 的 raw pgvector 查询路径。
- **R2** store 契约：`content` 承载 `chunk_text`；`metadata` 承载
  `domain`(CAR/KB/NEWS/IMAGE) / 域内引用 id（`docId` 语义随域）/ `modelId`（CAR）/ `chunkType` /
  `name`（车型名/标题）/ `active`（活表态）/ `embedding_model`。
- **R3** 活表语义同步层：`deleted`/`enabled` 变化同步到 metadata（软删/停用/重建时），检索只命中有效块。
- **R4** `embedding_model` 防护不回归：metadata 过滤当前模型；换模型旧行不参与检索。
- **R5** 两阶段对拍（D4）：阶段 A 逐条一致；阶段 B 语义合理性与改进。
- **R6** 迁移后修复质量债 #1/#2/#3/#5/#6（#4 rerank 除外）。
- **R7** 契约等价：`RagResult`/`Citation`/`rag_status`/`rag_citations`/检索 API 与前端交互不得变。
- **R8** 可回退：迁移分批次（建表/回填 → 对拍 → 切读 → 删旧表），每批可独立 `git revert`；旧表暂不删。

## Acceptance Criteria

- [x] **AC-阶段A对拍**：切块不变前提下同 query 集候选集/分数/排序/四态/配额逐条一致。→ E1 已证。
- [x] **AC-活表**：软删/停用/重建后 Store 立即不含失效块。→ E1 已证。
- [x] **AC-模型防护**：换模型后旧模型块不被检索（metadata filter）。→ E1 已证。
- [x] **AC-阶段B**：切块重叠落地且全库重嵌；语义合理、改进可观测。→ E2 已证（NEWS 58.5% 重叠）。
- [x] **AC-KB 规范化**：受控 domain 词表 + source/tags/生效期写入与检索过滤。→ E3 已证。
- [x] **AC-命名**：`sparkora_car_doc`→`sparkora_car_chunk`（表/实体/mapper/引用/文档）。→ E4 已证。
- [x] **AC-覆盖度/去重**：`coveredText` 覆盖 CAR/KB/NEWS；嵌入缓存对相同 chunk 生效。→ E5 已证。
- [x] **AC-契约等价**：`mvn test` **683 全绿**；前端 `npm run build` 通过；检索 API 结构与语义不变。
- [x] **AC-回退**：旧向量表在切读达标后由 E6 退役；每子任务可 `git revert`。
- [x] **AC-单一只真源（E6 增量）**：旧 4 表读写全部退役（双写消除），`vectorStats`/reconcile/`rebuildMissing`
      改走 `vector_store`；V9 DROP 旧表；`vector-stats` 响应结构不变。

> **收尾状态（2026-10-03）**：E1–E6 全部完成、归档、推送。向量层单一只真源 = `vector_store`。
> 生产 docker 容器需 `docker compose up -d --build` 才带 V6–V9 迁移与全部代码。

## Out of Scope

- #4 rerank 重排层（D5，另立任务）。
- KB 运营能力（批量导入/审核流/版本管理/前端管理页）。
- 前端「知识中心 vs 车型库」入口重叠的 UX 收敛（另议）。
- 业务 RAG 打分/配额/锚点**规则语义**变更（迁移期只搬存储+检索，不改业务规则）。
- 前端交互与对外响应契约变更。

## Task Map（父任务持有）

| 子任务 | 交付 | 依赖 |
|---|---|---|
| **E1** PgVectorStore 迁移阶段 A | 单表 store + 4 域回填 + 活表同步 + 模型过滤 + 阶段 A 对拍 | 无（前置） |
| **E2** 切块滑动重叠 + 全库重嵌 | `TextChunker` overlap + 重嵌 + 阶段 B 验收 | E1 |
| **E3** KB 数据模型规范化 | 受控 domain 词表 + source/tags/生效期 + 写入/检索 | E1 |
| **E4** 命名规范化 | `sparkora_car_doc`→块语义命名（表/实体/mapper/引用/文档） | E1 |
| **E5** 覆盖度三域统一 + 去重缓存 | `coveredText` 扩域 + content_hash 去重/嵌入缓存 | E1,E2 |
| **E6** 旧向量表退役（消除双写） | 去旧表读写 + stats/reconcile/差集改 store + DROP 旧 4 表 | E1–E5 |

父任务拥有源需求、任务映射、跨子验收与最终集成复核；子任务各自 `prd.md`/`implement.md` 写明依赖。
（E6 为集成复核时新增，非原始范围，用于消除 E1 遗留的双写债。）
