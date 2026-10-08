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
- **E-R8 采集配图进入图库检索（用户 2026-10-05 新增，P-R8 检索侧）**：B 转存的采集信源配图（图库 `source=source`）须可被既有**图片语义检索**命中，从而进入文章配图/问答配图链路。要点：
  - `ImageEmbeddingTextBuilder.build` 新增 `source` 分支：以来源内容**标题**（经 `sourceRef` 反查 `sparkora_news`）为嵌入主信号 + 标签（同 `byd-news` 模式）；
  - `ImageEmbeddingService.newsTitleOf` 的反查当前**硬编码仅 `byd-news`**，扩展为对 `source` 来源亦反查（或统一「凡 `sourceRef` 可反查即用」）；
  - 图库向量域 `domain=IMAGE`（`refId=imageId`）不变，**无新迁移**（向量由既有 `ImageEmbeddingService` 写入）。
  - 零回归：未接入采集图时 `byd-news`/AI/upload 图行为不变。

## Acceptance Criteria

- [x] **AC-E1 采集内容可检索**：工信部/乘联会/盖世采集内容切块嵌入后，统一检索能命中，来源标注正确，citations 带 `source` 与标题/publishDate。→ `SourceCollectService.upsertOne` best-effort 调 `SourceDocService.rebuildForNews`（:201-209）；`SourceDocService` 写 `upsert(domain=NEWS, refId=news_doc.id, metadata{sourceType,category,publishDate})`；`SourceDocServiceTest` 断言 refId/metadata；新增 `POST /api/source-contents/{id}/rebuild`。
- [x] **AC-E2 域隔离**：CAR/KB 候选窗口不被信源块挤占；NEWS 域内 BYD 不被用户采集源挤占。→ `CarRagService.retrieveUnified` 两次 `searchDomains` 保持域窗隔离；NEWS 候选按 sourceType 二分 + 独立配额；`CarRagServiceTest.E2_信源块激增_CAR候选不被挤占`、`E2_二级隔离_user子集塞满不改BYD子集`。（已知边界：生产规模窗口挤占见 design §4.3，默认 off 零风险。）
- [x] **AC-E3 BYD 等价**：BYD 标注逐位等价、存量向量 id 不失配、持续同步新块带 `byd-news`。→ `NewsDocService.persistNewsDoc` 写 `sourceType=byd-news`/`category=官方新闻`（:115-118）；`isBydNews` 对 `null` 兜底（含 V14 前旧块）；`sourceAnnotation` BYD 分支逐字「【官方新闻：name】」；`NewsDocTransactionTest` 断言 byd-news；V14 真实库 `BEGIN…ROLLBACK` 验证 `id` 不变。
- [x] **AC-E8 结构化内容**：表格类内容切块后数值不丢。→ `TextChunker` 新增 7 参 `preserveNewlines` 重载（旧 5/6 参委托 false，逐字等价）；`preservedBodies` 按行合并不压平；`TextChunkerTest.preserveNewlines_true_保留表格行结构`/`超长表格按行合并_整行不截断`；`SourceDocServiceTest.结构化分类_保留换行_数值行不丢`。
- [x] **AC-E9 字段贯通（P0）**：`sourceType`/`category` 经 `UnifiedHit`→`Citation`→`KnowledgeSearchTool` 完整传到位。→ `UnifiedHit`（:71-78）与 `Citation`（:98-108）两级均加可空字段 + 兼容构造器；`toUnified` 读 metadata（:541-545）；组 `cites` 透传（:454-456）；锚点加权重建同步（:321-322）；`CarRagServiceTest.E9_字段贯通_Citation带sourceType与category`。（下游 `SearchHit`/`SOURCE` 类型属 F 范围。）
- [x] **AC-E4 迁移幂等**：迁移可重入；存量回填后检索不丢；`mvn test` 全绿。→ `V14__news_source_metadata.sql` 仅 `metadata` 缺键守卫、不引用 `id`；check 以 `BEGIN…ROLLBACK` 复现幂等（二次 pass `UPDATE 0`）；V1–V13 未改；`mvn test` 1042 全绿。
- [x] **AC-E5 四态不变**：`rag_status` 语义与门槛判定不变。→ `RagStatus` 枚举与 `minScore`/`rejectScore` 判定逻辑未动；四态既有用例全通过。
- [x] **AC-E6 降级不阻断**：切块/嵌入失败不阻断生成。→ `SourceCollectService` try/catch warn；`SourceDocServiceTest.embedding失败_不抛出_失败计数`；`REQUIRES_NEW` 隔离。
- [x] **AC-E7 零回归**：未接入信源时行为等价；`npm run build` 通过。→ `AiProperties.ragSourceTopk=0` + `application.yml` + `.env.example` 默认 0；`sourceType==null` 兜底 byd；`E_sourceTopK默认0_用户源不注入_BYD不受影响`；`npm run build` ✓。
- [x] **AC-E10 采集配图可检索**：`source=source` 图经反查标题嵌入并可检索；旧来源不变；无新迁移。→ B 已实现 `ImageEmbeddingTextBuilder.build` `case "byd-news","source"` 与 `ImageEmbeddingService.newsTitleOf` 对 `source` 反查；`domain=IMAGE` 不变、无新迁移；`ImageEmbeddingTextBuilderTest.通用信源图_与新闻图同分派`。

## Out of Scope

- 采集/调度/解析（→ `10-05-source-crawl-base`）。
- 信源管理 UI（→ `10-05-source-center-ui`）。
- 外部搜索融合理由与置信规则（→ `10-05-source-web-fusion`）。
- 混合检索 BM25+RRF、外部向量库。
- 视频内容入库（正文配图采集侧见 B-R10，检索侧见 E-R8 已纳入）。
- 跨域一键重嵌编排。

## Notes

- 泛化迁移是**破坏性面最大**的子任务；实施前须先做 BYD 新闻检索对拍留基线（照 10-03 E1/E2 parity 范式）。
- 若新增独立 `sparkora_source*` 表而非扩展 `sparkora_news*`，须在 design 中给出与 `10-05-source-crawl-base` 落库表的字段映射，避免两任务各自建表冲突。
