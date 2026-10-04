# 知识域 · 通用汽车知识库（KB）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：手工录入的通用汽车知识（KB 域）的入库/切块/向量化，以及**三域统一检索**（CAR+KB+NEWS 同向量空间 UNION）的实现契约。

> S7「KB 双源检索」（2026-09-04）；S8 统一检索升级（2026-09-04）。三域状态机与降级语义见 [retrieval.md](../retrieval.md)。

---

## 1. 语义

知识来源从「仅车型域」扩展为**双源**——车型域（BYD 同步，既有）+ 通用域（手工录入知识，新增）。项目**未关联车型时生成（简报/正文）仍必查通用域**，不再零注入；已关联车型时双源独立配额合并。四态状态机与降级语义（[retrieval.md](../retrieval.md)）不变。

---

## 2. 数据层（Flyway `db/migration/V1__baseline.sql` S7 区块；**10-03 E3 扩展见 `V6__kb_normalize.sql`**）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_kb_doc` | id / title(≤200) / **domain 受控词表** / content / enabled / **source VARCHAR(200) 可空** / **effective_from DATE 可空** / **effective_to DATE 可空** / created_by / 审计字段 / deleted | 手工知识条目；逻辑删 |
| `sparkora_kb_doc_tag` | id / doc_id（应用层维护，无强 FK）/ tag_name VARCHAR(50) / created_by / created_at / UNIQUE(doc_id,tag_name) + `idx_kb_doc_tag_name` | **10-03 E3**：doc↔标签关联表（镜像 `sparkora_image_tag`）；物理删（随文档清理） |
| `sparkora_kb_chunk` | id / doc_id FK / seq / chunk_text / created_at | 检索块；`chunk_text` 首行固定「知识：<title>（<domain>）」 |
| ~~`sparkora_kb_chunk_embedding`~~ | id / chunk_id FK / embedding vector(1024) / embedding_model VARCHAR(100) / created_at | **已退役（10-03 E6 `V9` DROP）**。现向量行统一存单表 `vector_store`（`metadata.domain=KB` + `metadata.refId=kb_chunk.id` + `metadata.embeddingModel`），HNSW cosine；检索/统计/差集均按当前模型过滤 |

**受控 `domain` 词表（10-03 E3）**：代码常量 `com.sparkora.kb.KbDomain`（同 `NewsImageClassifier` 先例，纯静态可单测）取值保序 `通用/充电/保养/政策/技术科普/安全/驾驶`。写入侧统一 `normalize`（trim；空白→`通用`；**非词表值抛 `IllegalArgumentException` → 控制器 400 并附允许列表**）；存量行由 V6 按「精确匹配，否则归 `通用`」回填。改词表 = 改代码发版。

**生效期语义（10-03 E3）**：`effective_from`/`effective_to` 可空（null = 不限）。检索可用性由 store metadata `active` 承载：**`active = enabled && 今天∈[from,to]`**（边界含端点）。启动 + 每日 `KbEffectiveWindowReconciler`（`@Order(70)`）按当前日期重算并 `setActive`，使未来生效/到期自动翻转；异常仅 warn 不阻断。**不改检索 SQL 过滤语义**（仍只过滤 `active`），避免切分 CAR+KB 合并窗口破坏 E1 parity。

---

## 3. 服务与切块

`com.sparkora.kb.service.KbDocService` — create/update/delete/list/get/rebuild：

- 切块：**09-27 起薄委托 `com.sparkora.ai.TextChunker.chunk`**（空行分段、单段 ≤500 字符、超长按句读（KB 保持历史集合 `。；!?`）切分合并、段内换行转空格；KB 语义 = 空正文恒保留标题块；句读集合按域参数化，不取 NEWS 超集）。`splitSentences` 全库仅 `TextChunker` 一处定义。
- **10-03 E2 滑动重叠**：KB 服务层显式传 `TextChunker.DEFAULT_OVERLAP_CHARS`（60）启用相邻块句读重叠（前块 >60 时取其尾部片段作后块前缀，对齐句读边界、不整块重复、不增块数）；旧 5 参 `chunk` 默认无重叠保持向后兼容。KB 现网内容均为 <60 字短段落，重叠按设计不生效（无可取后缀），改造后与旧逐块一致。
- 重建幂等（先物理清 chunk + store 向量再重嵌）；**串行无重试（KB 失败策略不变）**，委托 `EmbeddingBatchRunner`（`maxParallel=1,maxRetries=0`）；embed 在事务外，持久化经自注入 `@Lazy self` 走 `@Transactional(REQUIRES_NEW)` 的 `persistChunk`（chunk 行与 store 向量同事务；**10-03 E6 起不再写旧 `sparkora_kb_chunk_embedding`**）；单块失败 warn+计数（共享 `com.sparkora.ai.EmbedStats` total/success/failed），块缺失用 rebuild 补齐。
- `enabled=false` 时清块。
- **10-03 E3 新维度**：`source`（trim、空串→null）；`tags` 关联表**先清后插全量覆盖**（normalize：trim/去空/去重保序/≤50，空列表=清空；无外层事务时撞 `UNIQUE` 捕 `DuplicateKeyException` 幂等）；`domain` 经 `KbDomain.normalize`；`update` 的 source/生效期用 `UpdateWrapper` 无条件 `.set(...)`（null 真正清库，规避 `updateById` 的 NOT_NULL 跳过）。
- **store metadata 扩展（E3）**：`persistChunk` 写入 `source`/`effectiveFrom`/`effectiveTo`（ISO `yyyy-MM-dd` 串）/`tags`（列表，空不写）与按生效期算出的 `active`；经 `VectorStoreService.upsert(..., Map<String,Object> extraMeta)` 可选重载（旧 9 参重载原样保留，CAR/NEWS/IMAGE 零影响）。

---

## 4. API（`/api/kb`，@PreAuthorize：读=三角色，写=ADMIN/EDITOR）

| Method | Path | 说明 |
|---|---|---|
| GET | `/api/kb/domains` | **10-03 E3**：受控领域词表（有序 `string[]`，供前端下拉） |
| GET | `/api/kb/docs` | 列表（含 `chunkCount`、`source`/`tags`/`effectiveFrom`/`effectiveTo`） |
| GET | `/api/kb/docs/{id}` | 详情（含 `content`） |
| POST | `/api/kb/docs` | 新建（`@Valid KbDocSaveDto`：title/domain/source/tags/effectiveFrom/effectiveTo/content，自动切块向量化） |
| PUT | `/api/kb/docs/{id}` | 编辑（自动重建；`enabled=false` 清块） |
| DELETE | `/api/kb/docs/{id}` | 删除（逻辑删文档 + 物理清块/标签） |
| POST | `/api/kb/docs/{id}/rebuild` | 手动重建，返回 `{total, success, failed}` |
| POST | `/api/kb/docs/batch` | **10-03 C 批量导入**：multipart `file` + 可选 `format`（csv/json/markdown；缺省后缀优先→内容嗅探）。返回 `{total,success,failed,results:[{index,title,success,error}]}`；逐条委托单条 `create`（normalize/切块/嵌入），单条失败/重复不阻断；整体解析失败/超 LIMIT → 400 且不落任何文档 |

- **批量导入语义（10-03 C）**：解析器 `com.sparkora.kb.KbBatchParser`（纯静态）/ 服务 `com.sparkora.kb.service.KbBatchImportService`（`MAX_ROWS=200`、单条正文 ≤50000、来源 ≤200）；去重键 `title+domain`（库内预取 + 批内 seen 双层，默认**跳过**回报「已存在同标题同领域文档，已跳过」，不覆盖）；Markdown 无 H1 时整篇标题取文件名去后缀。**既有单条 CRUD/domains 契约零改动**。
- 前端：`views/knowledge/KbLibraryPanel.vue`（知识中心「知识库」tab；列表卡片/新建编辑抽屉/删除确认/重建向量含失败提示/批量导入对话框；domain 为 `el-select` 受控值，另有 source 输入、tags 多选 allow-create、生效期 `daterange`；列表展示领域/标签/来源/生效期），AppShell 左 rail「知识库」入口；`api/index.js` 的 `kbApi`（含 `domains()`/`batchImport()`）。

---

## 5. 统一检索契约（S8，2026-09-04；S7 双源语义由本节取代）

| 项 | 行为 |
|---|---|
| 统一检索 | 三域**同向量空间全库检索**，按余弦分排序；返回行带 `source(CAR/KB/NEWS)`/`modelId`/`chunkType`/`modelName`。「项目关联车型」**不再是检索门禁**——未关联车型也全库检索（修复文章18 类误伤：数据在库却因未关联查不到）。检索按当前模型过滤（换模型后旧向量不再参与）。**10-03 E1 起读路径走 Spring AI PgVectorStore 单表**（`ai.vector.SearchStore`，CAR+KB 合并窗 + NEWS 独立窗复现）；**10-03 E6 起旧 `searchTopKUnified` UNION SQL 随旧表一并删除**；字段/状态契约不变（详见 [../retrieval.md §4.2](../retrieval.md)） |
| 锚点加权 | 项目关联车型降为**写作锚点**：CAR 块 `modelId ∈ anchor` → `score × AI_RAG_ANCHOR_BOOST`（默认 1.15，上限 1.0 截断）重排；~~前端项目编辑页改「写作锚点车型」文案~~（**2026-09-09：创建页车型选择入口已移除**——创作不与车型绑定，知识库停用期间该字段无生效点；后端关联逻辑与锚点加权保留，存量项目不受影响；新项目无 anchor 即全库无加权） |
| 配额 | 核心块（`PARAM_GROUP`/`MODEL_INFO`）优先、`RIGHTS`/`FEATURE` ≤1/3、`KB_CHUNK` 独立配额 `AI_RAG_KB_TOPK`；`AI_RAG_KB_ENABLED=false` 时 KB 块在配额层排除（等价 S6 行为，检索仍跑） |
| 来源标注 | 行内前缀「【车型数据：名称】」/「【通用知识：标题】」/「【官方新闻：标题】」；首行「知识来源：…」按命中构成生成 |
| 子查询 | S6.2 参数级子查询保留，子查询同走统一检索 |
| 状态判定 | 检索异常（单路统一检索）→ `FAILED`；`rawHit==0` → `NO_KNOWLEDGE`；`maxScore<reject` → `LOW_CONFIDENCE`；其余 `OK`（S6.1 四态语义不变） |
| 覆盖度声明 | `coveredText` 仅统计 CAR 域 `PARAM_GROUP` 块 |

- **候选窗口按域隔离（C2 check 修复）**：CAR+KB 合并取 top-`limit`（与 C2 前完全一致），NEWS 单独取 top-`limit`；不可三者共用一个全局 `LIMIT`——新闻块（≈1300+）与车型/KB 同向量空间且语义邻近时会占满整个窗口，把 CAR/KB 完全挤出候选（实测 BYD 新闻类 query CAR 候选从 32 掉到 0），使下游独立配额失效。详见 [news.md §5](news.md)。
- **图片域（第四域）是同空间但独立检索**：store 中 `domain=IMAGE` 与三域同模型同维度，但**不并入统一检索**（图片查询是独立入口 `POST /api/images/search`）。

**配置**：`AI_RAG_KB_TOPK`（默认 4）/ `AI_RAG_KB_ENABLED`（默认 true），见总览[配置总览](../../README.md)与 [retrieval.md §4](../retrieval.md)。

---

## 6. 关键实现路径

- 后端：`com.sparkora.kb.service.KbDocService`、`web.controller.KbDocController`、`domain.entity.KbDocEntity`/`KbChunkEntity`/`KbDocTagEntity`、`kb.KbDomain`（受控词表）、`mapper.KbDocTagMapper`、`config.KbEffectiveWindowReconciler`（生效期对账）、`car.service.CarRagService.retrieveForGeneration`（统一检索消费方）、`ai.vector.VectorStoreService`（单表 store，KB 向量写入/删除，10-03 E1/E6）。
- 前端：`views/KbLibrary.vue`。
- 表：`sparkora_kb_doc` / `sparkora_kb_doc_tag` / `sparkora_kb_chunk`；向量行存单表 `vector_store`（旧 `sparkora_kb_chunk_embedding` 已 E6 退役）。

---

## 7. 已知限制

- KB 块不参与锚点加权（无 `modelId`）。
- `AI_RAG_KB_ENABLED=false` 只是配额层排除，检索仍会跑（浪费一次向量查询）。
- 「项目关联车型」入口已从创建页移除，存量项目 anchor 仍生效。
- **换 embedding 模型后 KB 存量向量自动失效**（store `metadata.embeddingModel` 过滤），需逐文档 `POST /api/kb/docs/{id}/rebuild` 重嵌；无跨域一键重嵌。
