# design.md — E: 信源入库与检索接入

> 父任务设计：`../10-05-self-hosted-sources/design.md`（§2.4 域契约、§2.6 SOURCE 类型、§6 结构化切块）。
> 依赖：`10-05-source-crawl-base`（B 的信源内容与 `sparkora_news*` 落库）。
> 本文只写 E 自身：入库切块、metadata、检索二级隔离、来源标注。

## 1. 边界

| 层 | 文件 | 改动 |
|---|---|---|
| 迁移 | `db/migration/V14__news_source_metadata.sql` | 回填 `vector_store` NEWS 行 metadata（**不改向量 id**） |
| 入库 | `source/service/SourceDocService.java`（新增） | 采集内容→`sparkora_news_doc`→embedding（仿 `NewsDocService`） |
| 切块 | `ai/TextChunker.java`（新增重载，默认行为不变） | 结构化内容保留换行模式 |
| 检索 | `car/service/CarRagService.java` | NEWS 候选按 `sourceType` 二级分区 + 独立配额 + 标注 |
| 检索 | `ai/vector/SearchStore.java` / `VectorStoreService.java` | 从 metadata 读出 `sourceType`/`category` |
| 契约 | `CarRagService.UnifiedHit` **与 `Citation`** | **两处都要加 `sourceType`/`category` 字段**——`Citation` 是送到 `KnowledgeSearchTool` 的实际载体，只改 `UnifiedHit` 会在映射时丢字段（P0） |
| 标注 | `CarRagService` 行内来源 + citations `source` | 按 category 细分（BYD 逐字等价） |
| 配图 | `image/embed/ImageEmbeddingTextBuilder.java`、`service/ImageEmbeddingService.java` | 新增 `source` 分支 + 标题反查扩展（P-R8，父 §5.5） |
| 文档 | `docs/spec/knowledge/news.md` §5、`kb.md` §5、`retrieval.md` §4.2/§11、`image.md` | 字段级契约 |

**不做**：采集/调度（B）；UI（U）；融合置信规则（F）；BM25/RRF。

## 2. 入库：复用 B 的 `sparkora_news*`（id 空间约束）

B 已把通用信源内容写入 `sparkora_news`/`sparkora_news_doc`（B design §2.1）。E 的 `SourceDocService`：
- 读 B 的 `sparkora_news` 行（`source_id != NULL`、`content` 非空）；
- 切块（§3）→ 逐块写 `sparkora_news_doc`（首行「信源：<title>（<publishDate>）」）→ embedding 写 `sparkora_news_doc_embedding`；
- **`vector_store.upsert(domain="NEWS", refId=news_doc.id, ...)`**：`refId` 是 `sparkora_news_doc.id`，
  与 BYD 共享同一 id 空间，**零撞号**（B design §2.1）。
- 事务范式照 `NewsDocService`：embedding 网络调用在事务外，向量写入经 `@Lazy` 自注入代理走 `REQUIRES_NEW`（`NewsDocService:103`）。
- `metadata` 增写 `sourceType`（`byd-news` / `user-source`）、`category`、`publishDate`。
- **`sourceType` 需细到「可判独立来源」的粒度（实测 2026-10-05：盖世）**：盖世「车企官宣销量」与「销量排行」**来源基础不同**——
  官宣是车企自报（与乘联会独立可交叉），排行页可能派生自乘联会/上险（不可计独立交叉）。故 `sourceType` 至少区分
  `gasgoo-announce` / `gasgoo-ranking`，供 F 判定是否计入独立交叉。**粒度=栏目（channel）**（评审 2026-10-05：源可多栏目，同一盖世源的「官宣」「排行」是两条 channel，各带自己的 `sourceType` 与 `crossCounted` 布尔配置）。`sourceType` 由该内容所属 channel 的配置决定，非源级单一值。

> 与 B 的职责切分：B 落**原始内容**（`sparkora_news.content`），E 负责**切块+嵌入**（`_doc`/`_embedding`）。避免两任务重复写同一批表。

### 2.1 BYD 持续入表必须也写 sourceType（P1，时间维度 — 评审新增 2026-10-05）

**问题**：BYD 走**既有** `NewsDocService.persistNewsDoc`（:109-113），其 `vectorStoreService.upsert(...)` **不写 `sourceType`**。
E 的 `SourceDocService` 只处理 `source_id != NULL`（非 BYD）。→ V14 只回填**存量**；**上线后新增/更新的 BYD 块无 `sourceType`**，
检索层「按 sourceType 二分」会把它们误归 user-source 窗口，BYD 配额逐渐选不到新内容。

**修正**：
1. E 必须**同时修改 `NewsDocService` 的 NEWS upsert**，写入 `sourceType=byd-news`/`category=官方新闻`（与 `SourceDocService` 共用同一 metadata 契约）；
2. 检索二分把 **`sourceType == null` 视为 `byd-news` 兜底**（兼容任何未迁移/未打标的 BYD 块），确保零回归；
3. AC-E3 增加「持续 BYD 同步后，**新入库**块仍带 `byd-news` 且归 BYD 配额」。

## 3. 结构化切块（父 §6）

`TextChunker` 现按空行分段、**段内换行转空格**（:97-102）——会把 B 已转成行文本的表格再压平。

**决策**：新增重载 `TextChunker.chunk(title, content, ..., boolean preserveNewlines)`（**默认 false，旧行为逐字不变**）。
- `preserveNewlines=true`：段内**不**把 `\n` 转空格，按行成块或整段成块，保留 `列1 | 列2` 的行结构。
- `SourceDocService` 对 `category ∈ {销量数据,投诉榜,政策公示}` 的源用 `preserveNewlines=true`；
  BYD/普通资讯保持默认 false（回归锁）。
- 不新增依赖（jsoup 已在 B 侧解析表格）。

## 4. 检索：NEWS 域内二级隔离（保护 BYD）

现状（`CarRagService:340-364`）：NEWS 候选进一个 `newsCandidates`，配额 `AI_RAG_NEWS_TOPK`（=4）。
泛化后 BYD 官方新闻与用户采集源**共享这一窗与配额**，BYD 可能被挤占（破 AC-E3）。

**决策**：`retrieveUnified` 把 vector metadata 的 `sourceType`/`category` 透传，
**必须贯通两级 record**（P0 修正）：
- `UnifiedHit`（`CarRagService:69`）加 `sourceType`/`category`（可空 + 兼容构造器）；
- `Citation`（`CarRagService:87`）**同时加** `sourceType`/`category`（可空 + 保留 5 参/6 参兼容构造器）；
  因为 `KnowledgeSearchTool`（:45-48）只从 `Citation` 取字段、调 `SearchHit.kb(...)`，
  **`Citation` 不加字段，sourceType 会在 `UnifiedHit→Citation` 映射时被丢弃，F 永远拿不到**。
  映射点：`CarRagService` 组 `cites` 处（:418）与 `toUnified`（:466）同步读 metadata。
检索层：
1. `newsCandidates` 按 `sourceType` **二分**：`byd-news`（**含 `sourceType==null` 的旧/未打标块兜底**）/ 其余（user-source）。
2. **两套独立配额**：`byd-news` 用原 `AI_RAG_NEWS_TOPK`（行为锁）；`user-source` 用新 `AI_RAG_SOURCE_TOPK`（**默认 0=off**，零回归）。
3. **窗口**：NEWS 共享过采样窗不变（`oversample=max(topK*4,32)`，:257）；因配额分离，BYD 配额只从 `byd-news` 子集取，
   user 源取不到 BYD 的份额 → 二级隔离在**选择层**达成，无需扩展 `searchDomains` 的 metadata 过滤。
   - 若实测 top-窗被 user 源占满导致 BYD 候选不足，则升级为对 NEWS 域按 sourceType **两次 `searchDomains`**（需 Store API 加 metadata 过滤）——列为实现期观察项。

## 5. 来源标注（BYD 逐字等价）

`CarRagService:391-394` 现固定「【官方新闻：<name>】」。改为按 `category` 细分：

| sourceType/category | 标注 |
|---|---|
| `byd-news` / `官方新闻` | `【官方新闻：<name>】`（**逐字不变**） |
| `user-source` / `销量数据` | `【销量数据：<name>】` |
| `user-source` / `投诉榜` | `【投诉榜：<name>】` |
| `user-source` / 政策公示 | `【政策公示：<name>】` |
| `user-source` / 其他 | `【信源：<name>】` |

`sourceLine`（:376-380）的「官方新闻」构成项：BYD 存在时保持；user 源存在时追加其 category 名。
citations `source` 字段同步（`Citation.source()`）。

## 6. 迁移（不改向量 id）

`VectorStoreService.docId` 用 `domain+":"+refId`，**改 domain 名会使全部 NEWS 向量 id 失配**。故：
- **不改 `domain` 值**（仍 `NEWS`）。
- V14 只回填 metadata：`UPDATE vector_store SET metadata = jsonb_set(...)` 为 `domain='NEWS'` 行补
  `sourceType`/`category`（存量=byd）/`publishDate`；`id` 列不动，**无需重嵌**。
- 幂等：仅当键缺失时写入。

## 6.5 采集配图进入图库检索（P-R8 检索侧）

B 已把采集信源正文图转存图库（`source=source`，父 §5.5）。E 负责让它们**可被语义检索命中**：

- `ImageEmbeddingTextBuilder.build` 新增 `source` 分支：嵌入文本 = 来源内容标题（`sourceRef` 反查 `sparkora_news`）+ 标签，同 `byd-news` 模式（图片本身无文本，标题是主信号）。
- `ImageEmbeddingService.newsTitleOf` 现**硬编码 `"byd-news".equals(source)`**（`:241`）→ 扩展为 `source` 来源亦反查（或统一「凡 `sourceRef` 可反查即用」）。
- 图库向量域 `domain=IMAGE`（`refId=imageId`）**不变**，无新迁移；向量仍由既有 `ImageEmbeddingService.embedQuietly` 写入。
- 命中后进入既有 `IllustrationSuggestionService`（文章配图）/ 问答配图链路，无需改消费端。
- 零回归：`byd-news`/AI/upload 分支不变（新增 `case`，不动旧 `case`）。

## 7. 四态与配额不变
`rag_status`（OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE）、`ragMinScore`/`ragRejectScore` 判定**完全不动**；
新增的 `AI_RAG_SOURCE_TOPK` 默认 0，未启用时不改任何既有选择结果。

## 8. 风险

| 风险 | 缓解 |
|---|---|
| 改 domain 名致向量 id 失配 | **不改名**，只回填 metadata（§6） |
| user 源挤占 BYD | §4 二级配额隔离（BYD 用原配额） |
| 表格被压平 | §3 `preserveNewlines` 重载 |
| 入库重复 BYD 路径 | E 只处理 `source_id != NULL` 的行；BYD 走既有 `NewsDocService` |
| 迁移不可重入 | V14 仅在键缺失时写；`mvn test` 全绿 |
| 标注改动影响 BYD | BYD 分支逐字保留，回归对拍 |

## 9. 回滚

`AI_RAG_SOURCE_TOPK=0` + 不采集 → 零回归。全量回滚 = `git revert` V14（metadata 可保留，不影响旧读）。

## 10. Rollout（评审新增：默认 0 需显式开启）

`AI_RAG_SOURCE_TOPK` 默认 0 意味着**功能默认不生效**，需验证后开启：
1. 采集+入库跑通 → 确认 user-source 检索命中且 `sourceType` 贯通（AC-E9）；
2. 先 `AI_RAG_SOURCE_TOPK=2` 灰度，观察 fact_sheet 中本地来源占比与 BYD 等价（AC-E2/E3）；
3. 确认无回归后再升到目标值。BYD 配额走原 `AI_RAG_NEWS_TOPK` 不受影响。
