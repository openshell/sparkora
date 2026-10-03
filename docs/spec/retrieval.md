# 知识库检索（RAG 必查 + 降级可见）

> 回链：[系统说明总览](../README.md)

职责：定义生成链路（简报/正文/深度研究）中知识检索的**必查语义、检索状态枚举、门槛配置、引用明细与诚实边界**。跨模块横切契约（检索状态写入简报与版本，展示在前端）。

> 三域数据基座与统一检索实现见 [knowledge/kb.md](knowledge/kb.md)（含车型域/通用域/新闻域的库表与切块）；生成注入开关见 [settings.md](settings.md)。

---

## 1. 必查 + 降级可见（S6.1，2026-09-03）

**语义**：项目**已关联车型**时，生成简报与生成正文**必须发起**一次车型知识库 RAG 检索；检索失败或整体置信度过低**不阻断生成**（硬阻断会把创作绑死在 embedding 服务可用性上），但必须降级可见——AI 被要求在 `factRisks` 标注数据缺失，检索状态随产物落库并展示于前端。未关联车型视为「已查、无知识对象」，不算失败。

> S8 起「项目关联车型」不再是检索门禁（未关联也全库检索），降为**写作锚点加权**（见 [knowledge/kb.md](knowledge/kb.md)）。四态语义不变。

---

## 2. 检索状态枚举

`brief.rag_status` / `version.rag_status`，VARCHAR(20)：

| 状态 | 含义 | prompt 注入 | 前端展示 |
|---|---|---|---|
| `OK` | 命中且最高相似度 ≥ 整体门槛 | 权威数据注入，严格依据不得编造 | 「知识库 · 已引用」(绿) |
| `LOW_CONFIDENCE` | 有命中但最高相似度 < 整体门槛，**全部抛弃** | 不注入；提示 AI 不得臆造参数、`factRisks` 标注(建议 high) | 「知识库 · 低置信已抛弃」(橙)；版本卡片加「参数未经知识库核实」 |
| `FAILED` | 检索异常（embedding 服务等），**降级继续** | 不注入；要求 `factRisks` 标注数据缺失(建议 high)，不得臆造参数 | 「知识库 · 检索失败·已降级」(红)；版本卡片同上 |
| `NO_KNOWLEDGE` | 无车型关联对象或逐块过滤后无命中 | 不注入、不提示（与 S6 现状一致） | 「知识库 · 未引用」(灰) |
| `DISABLED` | **系统设置停用知识库（09-09-brief-gen-redesign，2026-09-09 增补）**：设置页 `kbEnabled=false` 时本地检索不发起 | 不注入任何本地知识块；外部搜索按 `webSearchEnabled` 独立启用（优先外部资料）；双关时 prompt 明确要求标注「未检索任何外部资料,数据未核实」 | 「知识库 · 知识库已停用(全局设置)」(灰)；不得与 `NO_KNOWLEDGE` 混淆 |

- `FAILED` 优先级高于其余状态：多车型检索时任一车型异常即标 `FAILED`（其余车型照常尝试）。
- 抛弃/失败**不得与「无命中」混淆**：`LOW_CONFIDENCE`/`FAILED` 必须显式落库，前端据此提示。
- `DISABLED` 是**主动停用**语义（设置页可随时切回），与失败/低置信的被动降级不同；仅深度链路产生（快速模式已下线）。

**字段级**：`sparkora_article_brief.rag_status`、`sparkora_article_version.rag_status` — `VARCHAR(20)`，可空（历史行为数据为 NULL，前端不展示）；GET brief/versions 响应自然携带该字段，无独立接口。

---

## 3. 知识引用明细（R3，2026-09-05 增补）

`sparkora_article_brief.rag_citations`、`sparkora_article_version.rag_citations` — `TEXT`（JSON 数组）：

```
[{source:"CAR|KB|NEWS", modelName, chunkType, score, chunkText, docId}]
```

- `docId` 为 09-15 qa-auto-illustrate 起的可空域内块 id：`CAR=car_doc.id` / `KB=kb_chunk.id` / `NEWS=news_doc.id`。
- 检索 `OK` 且有命中时随生成落库（与注入 prompt 的 context 同源，上限 24 条、单条文本截断 120 字符，序列化超 8000 字符整体置 null）；`LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE` 为 null。**版本链路由 `VersionService` 写入；`brief.rag_citations` 的写入方（FAST 简报 `BriefService.generate`）已于 2026-09-26（R6）删除，深度简报链路不写该列（引用面板改由 `fact_sheet` 派生，见下条）**。
- 前端简报页「知识库引用」区（`CitationList` 组件）与版本卡片「引用 N」标签（点击展开）展示；空态按 `ragStatus` 显示降级文案（**前端不读 `docId`，纯增量不影响展示**）。
- **WEB 搜索来源并入（2026-09-05 增补）**：深度模式简报页的引用面板另将 `brief.fact_sheet.entries` 中条目派生为引用条目并入展示——**全部类型（KB/WEB/MULTI，2026-09-06 修订）**：KB 条目（置信 0.9/0.6）与本地 `rag_citations` 同款「通用知识」标签展示（修复「深度模式内容引用了知识库、页面却显示未引用」的展示断链，项目 29 实测）；WEB 带域名、MULTI 标多源交叉；上限 24 条。快速模式无 `fact_sheet`，行为不变（**2026-09-09 注：快速模式已下线，本句仅存量语义**）。版本卡片保持「本版生成时的本地知识库检索」语义，不重复展示 WEB 引用。

---

## 4. 检索门槛（粗调值，**待按真实 query 分数分布校准**；`REJECT` 须 ≥ `MIN`）

| `.env` 变量 | 默认 | 代码用途 |
|---|---|---|
| `AI_RAG_MIN_SCORE` | `0.3` | 逐块相似度门槛，低于不注入（沿用 S6 原硬编码值） |
| `AI_RAG_REJECT_SCORE` | `0.5` | 整体置信度门槛：全部命中块的最高相似度低于该值 → `LOW_CONFIDENCE` 全部抛弃 |
| `AI_RAG_KB_TOPK` | `4` | 通用知识库生成检索注入块数上限（与车型域配额独立；见 [knowledge/kb.md](knowledge/kb.md)） |
| `AI_RAG_KB_ENABLED` | `true` | 通用知识库总开关，false 时统一检索排除 KB 块（见 [knowledge/kb.md](knowledge/kb.md)） |
| `AI_RAG_ANCHOR_BOOST` | `1.15` | 统一检索锚点车型块分数加权系数（见 [knowledge/kb.md](knowledge/kb.md)） |
| `AI_RAG_NEWS_TOPK` | `4` | 新闻域生成注入块数上限（`0` 关闭 NEWS 注入；不受 KB 开关控制；见 [knowledge/news.md](knowledge/news.md)） |
| `AI_IMAGE_MIN_SCORE` | `0.3` | 图片语义检索门槛（独立入口；见 [image.md](image.md)） |
| `AI_EMBEDDING_DIM` | `1024` | 向量维度校验：`EmbeddingClient.embedList` 返回长度不符即抛 `AiException`（09-27；见下节） |

---

## 4.1 向量模型防护（09-27 P1-⑧）

- **配置单一来源**：`AI_EMBEDDING_MODEL`（`.env` → `sparkora.ai.embedding-model` → `AiProperties.embeddingModel`）；维度 `AI_EMBEDDING_DIM`（默认 1024，与 DDL `VECTOR(1024)` 一致）。
- **4 张向量表加 `embedding_model VARCHAR(100)`**（Flyway V3）：`sparkora_car_doc_embedding` / `sparkora_kb_chunk_embedding` / `sparkora_news_doc_embedding` / `sparkora_image_embedding`；存量行回填为实际部署配置模型（placeholder `${embeddingModel}`）。
- **写入盖名**：4 条写路径（CAR/KB/NEWS/IMAGE）统一盖 `EmbeddingClient.modelName()`；**检索过滤**：4 条查询（`searchTopK`×3 + `searchTopKUnified` 三段）均加 `embedding_model = 当前模型`。换模型后旧模型行自动失效（可见降级而非静默混空间），重嵌后新向量自动生效。
- **维度校验 fail-fast**：`EmbeddingClient.embedList` 校验返回长度 == `AI_EMBEDDING_DIM`，不符抛 `AiException`（中文提示含实际/期望维度与模型名），首次写入或查询即暴露。
- **启动对账**：`EmbeddingModelReconcileRunner`（`@Order(60)`，Flyway 之后）逐表 `GROUP BY embedding_model`，存在非当前模型的行时 WARN（列出模型名+条数，提示重嵌），异常仅 warn 不阻断启动。
- **重嵌入口**：CAR `POST /api/car/models/rebuild-all`、KB `POST /api/kb/docs/{id}/rebuild`、NEWS `POST /api/news/{id}/rebuild`（09-27 新增）、IMAGE `POST /api/images/embeddings/rebuild`；跨域一键重嵌编排 out of scope。

## 4.2 向量检索层（10-03 E1：Spring AI PgVectorStore 单表，阶段 A）

> 迁移子任务 E1（`.trellis/tasks/10-03-e1-pgstore-migrate`）。旧 4 表保留、未删（可回退）。

- **拓扑**：单张 `vector_store`（Flyway `V5__pgvector_store.sql`；`id uuid / content text / metadata json / embedding vector(1024)`；HNSW cosine + metadata GIN）。`initialize-schema=false`（由 Flyway 建表）；维度/距离/索引由 `spring.ai.vectorstore.pgvector.*` 约定（1024 / cosine / hnsw）。
- **metadata 契约**：`{domain(CAR|KB|NEWS|IMAGE), refId(域内 id), modelId(仅 CAR), chunkType, name, active, embeddingModel}`；`content` = `chunk_text`（IMAGE 域 = 旧 `source_text`）。
- **id 确定性**：`UUID.nameUUIDFromBytes(domain+":"+refId)`，供 upsert/delete/setActive 按 id 定位（store 无按 metadata 更新 API）。
- **读路径**：`CarRagService` 经 `ai.vector.SearchStore`；候选窗按域隔离复现——CAR+KB 合并一次 `similaritySearch` + NEWS 独立一次（`domain in [...] && active && embeddingModel`）+ Java 合并；`similarityThreshold=0`，门槛仍在 Java 侧用 `ragMinScore`/`ragRejectScore` 判定。`ImageEmbeddingService.searchImages` 走 IMAGE 域（可选 `refId ∈ 白名单`）。
- **活表同步层**：`active` 由写路径 `VectorStoreService.upsert/setActive/deleteByRef` 维护（软删/停用/重建/删父联动）；`CarDocService`/`KbDocService`/`NewsDocService`/`ImageEmbeddingService` 的 persist/delete 同步单表。
- **回填**：`VectorStoreBackfillRunner`（`@Order(50)`，守护线程，幂等差集）从旧表复用已算好的向量（**不重新嵌入**）搬入 store；异常仅 warn 不阻断。
- **阶段 A 对拍**：`.trellis/tasks/10-03-e1-pgstore-migrate/research/parity-A.md`（7 query 集 × CAR+KB/NEWS/IMAGE，候选集/分数/排序逐条一致）。
- **契约不变**：`RagResult`/`Citation`/`rag_status`/`rag_citations` 与前端交互零变更。

---

## 5. 检索策略升级（S6.2，2026-09-03；修复海狮08 文章价格/续航错误暴露的检索精度缺陷）

| 缺陷（S6.1 现状） | S6.2 修复 |
|---|---|
| 「XX参数表及配置表」零信息表头块（仅标题行）得分最高挤占 topK | 切块层：有效参数 <2 的分组不入库（`CarDocService`）；检索层兜底丢弃仅含标题行的参数块 |
| 权益块与主题措辞相似挤占配额 | 分层配额 `applyQuota`：PARAM_GROUP/MODEL_INFO 优先，RIGHTS/FEATURE 合计 ≤ 总配额 1/3 |
| 单查询整句 topic 与参数级子问题不对齐 | 参数级子查询 `deriveSubQueries`：query 含价格/续航/油耗等参数词时逐词派生子查询，主/子查询结果按 `chunkText` 去重合并 |
| AI 在知识块未覆盖的参数处编造数值 | 覆盖度声明：`RagResult.coveredText` 携带「参数名→值」清单注入 prompt；清单外参数禁止写具体数值，要求定性表述 + `factRisks` 标注 |

- 修复生效前提：**重新同步车型**（旧表头块仍在库中，检索层已兜底过滤，但建议重同步清理）。
- 生成时后端须运行 S6.2 代码（历史教训：S6.1 合入后进程未重启，生成仍走旧链路）。

---

## 6. 数据清洗链路治理（S6b，2026-09-04；kb-clean-audit 任务）

| 项 | 契约 |
|---|---|
| 清洗方式三态 | `car_param_clean.clean_method` ∈ `RULE`(规则引擎命中) / `AI`(LLM 兜底) / `FALLBACK`(双失败 STRING 原样,需人工关注)；**不再出现把兜底误标 RULE 的旧行为**，旧数据需重清洗刷新口径 |
| 清洗统计 | `CleanStats`(RULE/AI/FALLBACK 计数)：随 `cleanForModel` 日志汇总、同步任务聚合日志(`fallbackPct`)、`GET /api/car/models/{id}/clean-stats` 按 method/valueType 分组查询(三角色可读) |
| PARAM_GROUP 块首行 | 固定 `车型：<全名>`（消除 EV/DM-i 同系跨版本检索混淆，即 S6.2 P1 遗留项）；块行文本 `参数名：清洗值` |
| 清洗值展示 | 优先 `car_param_clean.param_value`，缺失回退 `raw_value`；NUMBER/LIST 类型且值不含单位时拼接单位（如 `2820mm`）；清洗与原始值均缺省跳过该行 |
| 向量重建 | `rebuildForModel`：embedding 并发（固定线程池 ≤4）+ 单块失败重试 1 次；完成日志输出「成功 X/失败 Z」，失败块记 `sortOrder`（消除静默丢块） |
| 批量重建/对账 | `POST /api/car/models/rebuild-all`（ADMIN/EDITOR）逐车型重建汇总；`GET /api/car/models/vector-stats`（三角色）返回 `{modelCount, chunkCount, embeddedCount, missingCount, missingTopN}`（仅统计 `deleted=0`；2026-09-04 实测全库 380/380 缺失 0） |
| 入库去重 | `persistVersions`/`persistParams` 同名版本/同名分组去重（官网接口历史上曾按模块重复推送，防再发）；重同步车型39 复测 clean 与参数版本值 1:1 精确对齐 |
| AI 兜底空值防线 | `AiParamCleaner` 对 AI 返回 value 空白视为失败返回 null（走 FALLBACK 兜底），「无值清成空串」不再落库 |
| 摊平核查结论 | 6432 清洗行疑云 = 历史上游重复推送 + `@TableLogic` 逻辑删先清后插堆积（非清洗层摊平）；详见 `archive/2026-09/09-04-clean-followup/research/flatten-findings.md` |
| 生效前提 | 切块口径变更**仅对新重建的车型生效**；存量 56 车型需逐个重建向量（体检发现 408/1293 块历史向量缺失，重建一并补齐） |

- 体检报告（量化）见 `.trellis/tasks/09-04-kb-clean-audit/research/clean-audit-report.md`：规则引擎覆盖 98.8%+（口径可信度受旧误标影响，重清洗后复测）；AI 兜底 9 行中 4 行「无值清成空串」属错误输出（P2 建议：AI 返回空值视为失败不落库）；**31.6% 文档块无向量（历史静默丢失）**；单车型 39 清洗行 6432（占 60%）疑似多版本摊平，待核查。

---

## 7. 诚实边界

相似度衡量**相关性**而非事实正确性——知识库本身存错的数据会以高相似度被当作权威注入；防错依赖入库源头（比亚迪同步 + 人工清洗），检索门槛不承诺拦截知识库错误数据。

---

## 8. 关键实现路径

- 后端：`com.sparkora.car`（`CarRagService.retrieveForGeneration`、`CarDocService` 切块/配额/子查询/覆盖度）、`ai.vector.VectorStoreService`（单表 store 读写/同步，10-03 E1）、`ai.EmbeddingClient`、`ai.RagStatus`、`service.VersionService`（写入 `rag_status`/`rag_citations`；`service.BriefService` 的 FAST `generate` 曾写入 `brief.rag_status`/`rag_citations`，该路径已于 2026-09-26（R6）删除，`BriefService.citationsJson` 仍被 VersionService 复用）、`deep.service.FactSheetService`（WEB/KB 冲突裁决）。旧表路径 `mapper.CarDocEmbeddingMapper.searchTopKUnified` 保留（未删，可回退），E1 后不再被 `CarRagService` 调用。

---

- 前端：`views/project/deep/CitationList.vue`（引用面板，CAR/KB/NEWS/WEB/MULTI 分支）、`views/project/StepVersions.vue`（版本卡片「引用 N」/降级提示）。
- 表：`sparkora_article_brief.rag_status/rag_citations`、`sparkora_article_version.rag_status/rag_citations`。

---

## 9. 切块滑动重叠与阶段 B 重嵌（10-03 E2）

- **重叠能力**：`com.sparkora.ai.TextChunker.chunk(..., int overlapChars)`（6 参重载）；旧 5 参委托 `overlapChars=0`（逐块等价旧行为，向后兼容）。KB/NEWS 服务层显式传 `DEFAULT_OVERLAP_CHARS=60` 启用；**CAR（参数分组块）与 IMAGE（`ImageEmbeddingTextBuilder`）不走 TextChunker，切块形态不变**。
- **重叠策略**：相邻产出块（短段落分块 + 超长段句读合并两路）中，前块 >60 字则取「尾部片段」（≤60，优先从片段内首个句读分隔符之后开始对齐句读边界）作后块前缀；前缀+本块仍须 ≤500（放不下则不重叠）；不整块重复、不增块数。
- **全库重嵌（阶段 B）**：经既有 rebuild 入口重建 4 域（旧 4 表保留、不新增破坏性脚本）——CAR `POST /api/car/models/rebuild-all`、KB `POST /api/kb/docs/{id}/rebuild`、NEWS 逐篇 `POST /api/news/{id}/rebuild`、IMAGE `POST /api/images/embeddings/rebuild`。实测（2026-10-03）：CAR 56/56、KB 3/3、NEWS 168/168、IMAGE 180/180 成功；对账 `embeddedCount == chunkCount`（380/1341/3/180），store 合计 1904 行全部 `active=true` 且 `embeddingModel=Qwen3-Embedding-8B`。
- **阶段 B 验收**：8 个代表 query（KB/NEWS/CAR/锚点/混合）前后 `ragStatus` 均 `OK`（不恶化）、候选条数与来源分布逐 query 不变、分数分布 Δ≤0.02；NEWS 1173 相邻块对中 58.5% 建立 15–60 字重叠，跨块边界语义补齐、边界 query 分数微升。详见 `.trellis/tasks/10-03-e2-chunk-overlap/research/parity-B.md`。
