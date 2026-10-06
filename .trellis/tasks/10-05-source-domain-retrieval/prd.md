# prd.md — 信源入库与检索接入

> 父任务：`10-05-self-hosted-sources`（需求集与设计论证持有者）
> 依赖：[`10-05-source-crawl-base`](../10-05-source-crawl-base/prd.md)（需要其规范化落库内容与任务表）。
> 关键约束来源：`docs/spec/knowledge/news.md` §5、`docs/spec/knowledge/kb.md` §5、`docs/spec/retrieval.md` §4.2/§11。

## Goal

把采集入库的信源内容**切块 + 嵌入**进单表 `vector_store`，并把现有 NEWS 域**泛化为通用信源域（SOURCE）**，使不同来源（BYD 官方新闻 / 工信部 / 乘联会 / 盖世）都能被统一检索命中，带来源标注、独立配额、新鲜度，并注入生成链路。

用户价值：让自建信源从「落库的数据」变成「写作时可被 RAG 检索到的权威证据」。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置**：`10-05-source-crawl-base`（信源注册表 + 采集内容 + 任务表）。
- **后继**：`10-05-source-center-ui`（需本任务的内容查询契约）、`10-05-source-web-fusion`（需本任务的来源/置信字段）。

## Requirements

- **E-R1 保留 NEWS 域、域内细化来源（核心决策 D2，评审修正）**：**不改 `domain` 名**（`VectorStoreService.docId()` 用 `UUID(domain+":"+refId)` 作确定性主键，改名会使全部存量 NEWS 向量 id 失配、须全量重嵌）；保留 `domain=NEWS`，用 `metadata.sourceType`（如 `byd-news`/`user-source`）与 `category`（如 `官方新闻`/`销量数据`/`投诉榜`/`政策公示`）区分来源。**BYD 新闻成为 `sourceType=byd-news`、`category=官方新闻` 的特例**，其检索行为/来源标注/配额保持等价。
- **E-R2 迁移与回填**：新增 Flyway 迁移（不改已应用的 V1-V11）——为信源内容建表（与 `10-05-source-crawl-base` 的落库表对齐）；`vector_store` 中既有 `domain=NEWS` 行**回填 `sourceType=byd-news`/`category=官方新闻`/`publishDate`**（如可解析），保证细化后存量检索命中不丢。**迁移需可重入、幂等；不回填向量 id（不改 domain 故无需重嵌）**。
- **E-R3 切块与嵌入**：信源内容切块复用 `TextChunker`（空正文仅标题保留标题块、滑动重叠 `DEFAULT_OVERLAP_CHARS=60`），首行锚点「信源：<title>（<publishDate>）」或按 category 定制；**结构化（表格）内容不得被默认切块压平**——按父设计 §6 择一方案（表格转行文本 / 按行切块 / 新增保留换行的 `TextChunker` 重载），保证行列表数值不丢；嵌入经 `EmbeddingBatchRunner` 写单表 store；单块失败 warn 不阻断；embed 在事务外、持久化走 `REQUIRES_NEW`（照 `NewsDocService` 范式）。
- **E-R4 统一检索接入（不改四态语义）**：`CarRagService.retrieveForGeneration` 的信源候选**按域隔离窗口**（复现 NEWS 独立窗，不得与 CAR/KB 共用全局 LIMIT）；**域内按 `sourceType` 二级隔离窗口与配额**——BYD 新闻保留原窗口/原 `AI_RAG_NEWS_TOPK`，用户采集源用独立窗口/独立配额（默认 off 零回归），**防止采集源挤占 BYD（破 AC-E3）**；信源块**不被锚点加权**（无 modelId）；行内标注「【信源：<title>】」或按 category 细分（BYD 场景保持旧「【官方新闻：<title>】」逐字等价）；citations `source` 纳入。
- **E-R5 新鲜度（可选增强）**：信源条目带 `publishDate` 时，可对时效型 query 做新鲜度偏好/衰减（配置默认 off，零回归）。仅在有 `publishDate` 的源生效。
- **E-R6 来源标注与前端兼容**：`CitationList.vue`/`FactSheetSummary.vue` 的 source 分支需兼容新 sourceType（**增量**，旧前端不读不报错）；`rag_citations` JSON 结构增量向后兼容。
- **E-R7 字段贯通（P0，评审新增）**：`sourceType`/`category` 必须贯通 **`UnifiedHit` 与 `Citation` 两级 record**——`CarRagService.Citation`（:87）是送到 `KnowledgeSearchTool` 的实际载体，只改 `UnifiedHit` 会在 `UnifiedHit→Citation` 映射时丢字段，F 将永远拿不到 sourceType。两处均加可空字段 + 兼容构造器，组 `cites`（:418）与 `toUnified`（:466）同步读 metadata。
- **E-R7 配置与文档**：`AI_RAG_*` 配额项、新鲜度开关进 `.env.example`；同步 `docs/spec/knowledge/news.md`（泛化说明）、`docs/spec/knowledge/kb.md` §5、`docs/spec/retrieval.md` §4.2/§11 字段级契约。

## Acceptance Criteria

- [ ] **AC-E1 采集内容可检索**：工信部/乘联会/盖世采集内容切块嵌入后，统一检索能命中，来源标注正确，citations 带 `source` 与标题/publishDate。
- [ ] **AC-E2 域隔离**：新增信源块后，复现 `news.md` §5 验证口径——CAR/KB 候选窗口不被信源块挤占（构造 BYD 类 query，CAR 候选数不因信源块增加而下降）；**且 NEWS 域内 BYD 官方新闻不被用户采集源挤占**（构造 BYD 新闻 + 采集源并发命中，验证二级隔离）。
- [ ] **AC-E3 BYD 等价**：BYD 新闻标注为 `sourceType=byd-news`、`domain=NEWS` 不变后，检索命中/来源标注/配额/前端展示与改造前**逐位等价**（回归对拍）；**存量向量 id 不失配、无需重嵌**；**持续 BYD 同步后新入库块仍带 `byd-news`**（`NewsDocService` 同步写入 metadata，不能只有 V14 回填存量）。
- [ ] **AC-E8 结构化内容**：乘联会销量表等表格类内容切块后行列表数值不丢、可被检索命中；不被 `TextChunker` 段内换行转空格压平。
- [ ] **AC-E9 字段贯通（P0）**：user-source 的 NEWS 命中，其 `sourceType`/`category` 能经 `UnifiedHit`→`Citation`→`KnowledgeSearchTool` 完整传到位（断言 `Citation.sourceType()=="user-source"`），下游 F 可据此判 SOURCE；BYD 命中仍为 `byd-news`。
- [ ] **AC-E4 迁移幂等**：Flyway 迁移 + 回填可重入；存量 NEWS 向量行回填后检索不丢；`mvn test` 全绿。
- [ ] **AC-E5 四态不变**：`rag_status` `OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE/DISABLED` 语义与判定口径不变（门槛 `ragMinScore`/`ragRejectScore` 仍作用于原分）。
- [ ] **AC-E6 降级不阻断**：信源切块/嵌入失败不阻断生成；生成链路可正常跑通。
- [ ] **AC-E7 零回归**：未接入自建信源时行为与现状等价；`npm run build` 通过。

## Out of Scope

- 采集/调度/解析（→ `10-05-source-crawl-base`）。
- 信源管理 UI（→ `10-05-source-center-ui`）。
- 外部搜索融合理由与置信规则（→ `10-05-source-web-fusion`）。
- 混合检索 BM25+RRF、外部向量库。
- 图片/视频内容入库。
- 跨域一键重嵌编排。

## Notes

- 泛化迁移是**破坏性面最大**的子任务；实施前须先做 BYD 新闻检索对拍留基线（照 10-03 E1/E2 parity 范式）。
- 若新增独立 `sparkora_source*` 表而非扩展 `sparkora_news*`，须在 design 中给出与 `10-05-source-crawl-base` 落库表的字段映射，避免两任务各自建表冲突。
