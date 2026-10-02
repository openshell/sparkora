# 版本生成（多版本正文）

> 回链：[系统说明总览](../README.md)

职责：基于简报（brief）+ 用户选择的风格，产出多版本正文，并维护「当前版本」与版本展示字段。

- 主题创作的**多版本生成接口已封死**（2026-09-09，恒 410）；主题正文由深度链路的 `/deep/generate` 生成（**09-27-gen-async 起为批量异步**：`styleIds[]` 一次触发，见 [brief-generation.md](brief-generation.md)）。`POST /generate/versions` 仅保留给**文章仿写**（见 [imitation.md](imitation.md)）。
- 项目状态机推进（READY→GENERATING_VERSIONS→VERSIONS_READY / 失败回退）见 [overview.md §4](overview.md)。

---

## 1. 版本字段级（`ArticleVersionEntity` → 表 `sparkora_article_version`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 自增主键 |
| project_id | BIGINT | 所属项目 |
| brief_id | BIGINT | 基于哪个 brief 生成 |
| title | VARCHAR(200) | 该版本标题。来源优先级（S6）：项目 `selected_title` 非空 → 该选定标题（trim/截断 200）；否则正文首个 Markdown H1（深度链路）/ AI 产出 `title`（仿写链路）；再否则项目 `topic`。可不同于 brief 候选 |
| content_md | TEXT | 正文 Markdown |
| version_label | VARCHAR(10) | `A` / `B` / `C`（`VersionService.LABELS="ABCDEFGHIJ"`，一次最多 10 版） |
| style_tag | VARCHAR(20) | 风格标记（正式 / 活泼 / 干货 等） |
| ai_model | VARCHAR(64) | 实际调用的模型名 |
| token_usage | INTEGER | token 用量 |
| rag_status | VARCHAR(20) | 知识库检索状态 `OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE`（见 [retrieval.md](retrieval.md)） |
| rag_citations | TEXT | 知识库引用明细 JSON `[{source,modelName,chunkType,score,chunkText,docId}]`（R3） |
| fact_risks | TEXT | 数值回查结果 JSON `[{claim,riskLevel,suggestion}]`（深度模式；见 [brief-generation.md](brief-generation.md)） |
| word_count | INTEGER | 字数 |
| similarity_score | DOUBLE PRECISION | 文章仿写：与原文 5-gram 重合率 0~1（仅仿写版有值；见 [imitation.md](imitation.md)） |
| similarity_report | TEXT | 文章仿写：自检明细 JSON `{maxRunLength,maxRunText?,repeatedRuns:[{text,length}],thresholds}` |
| cover_image_id | BIGINT | S3b：该版本封面（`sparkora_image_asset.id`，可空；每版本一张） |
| created_at | TIMESTAMP | 创建时间 |

- 版本-图片关联**挂版本，不挂项目**：多版本各有排版，预览/发布按「当前版本」取图；项目级关联无法表达版本间差异（见 [image.md](image.md)）。
- 索引：`idx_version_project (project_id)`。
- **正文插图（P1-⑦ 规范化）**：原 `body_image_ids VARCHAR(1000)`（逗号分隔有序 id 串）已改为独立关联表 `sparkora_article_version_image`（`version_id`/`image_id`/`sort_order`/`created_at`，`UNIQUE(version_id,image_id)`），字段/契约见 [image.md](image.md) §5。
- 幂等迁移：`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（`rag_status` / `fact_risks` / `rag_citations` / `cover_image_id`；仿写字段见 [imitation.md](imitation.md)）。

---

## 2. `POST /api/projects/{id}/generate/versions` 契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | **主题创作恒 `R.fail(410, "生成流程已升级为深度模式,版本生成请使用深度生成(/deep/generate)")`**；**仿写项目例外**（`genSource=IMITATION`）：**09-27-gen-async 异步化**，返回 `{status:"GENERATING_VERSIONS", styleCount:N}`（毫秒级），后台逐风格生成，产出含 `similarity_score`/`similarity_report`，契约详见 [imitation.md](imitation.md) |

- `styleIds` 为风格库 id 列表；空 → `IllegalArgumentException`（400「至少选择一个风格」）；超过 10 个 → 400「一次最多生成 10 版」。
- 所选风格查无 → 400「所选风格不存在」。

---

## 3. `VersionService` 语义（09-27-gen-async 同步/异步切分）

`com.sparkora.service.VersionService`——同步 `generate()` 做校验 + 抢占 + 触发异步并立即返回；`@Async runGenerate()` 执行 AI：

**同步 `generate(projectId, styleIds)`（毫秒级）**

1. **入参校验**：`styleIds` 非空、≤ `LABELS.length()`（10）。
2. **项目/简报就绪校验**：项目不存在 → 400；`current_brief_id` 为空 → `NotReadyException`「尚未生成 brief，无法生成版本」；brief 不存在 → `NotReadyException`；`selectBatchIds` 为空 → 400「所选风格不存在」（校验保留在同步阶段，400 即时反馈）。
3. **并发防护 + 原子抢占**（消除 check-then-set 竞态）：
   - 项目处于生成中（`stuckGenerating(p)`，`updated_at` 在 10 分钟内）→ `IllegalStateException`「该项目正在生成中，请稍候（刷新页面可查看进度）」。
   - `claimVersionsGenerating` 条件更新置 `GENERATING_VERSIONS`：仅当 `status ∈ {READY, VERSIONS_READY}`（首生成/追加）**或**「生成中且已陈旧（超 `STALE_GENERATING_MS=10min`，进程已死，自愈）」才生效；陈旧分支必须限定生成中状态，否则任何 `updated_at` 较旧的下游状态都会被误放行、状态机回退。`PUBLISHED_DRAFT` 之后已触发下一步，再生成版本会把状态机拉回 VERSIONS_READY，拒绝（`guardMsg` =「…下游步骤已触发，不支持回退重做」）。
   - 同时清 `last_version_error`。
4. **触发异步**：`(self == null ? this : self).runGenerate(...)`（自注入 `@Autowired @Lazy` 代理确保 `@Async` 生效），返回 `{status:"GENERATING_VERSIONS", styleCount:N}`。

**异步 `runGenerate(projectId, styleIds)`（后台）**

5. **重取实体**（不复用同步阶段快照，防陈旧）→ 再次校验 brief 就绪。
6. **风格与 RAG**：`styleMapper.selectBatchIds(styleIds)`；RAG 检索 `CarRagService.retrieveForGeneration(topic, 8, modelIds)`（`modelIds` 由 `ArticleProjectCarService.listModelIds` 取，S8 起仅作**写作锚点加权**，未关联也全库检索）。仿写模式跳过 RAG（`RagResult.EMPTY`，`ragStatus=NO_KNOWLEDGE`）。
7. **逐风格生成**：每风格一版，`label = A/B/C…`；单版失败仅 warn 并计入 `perVersionErrors`（**部分成功也继续**）。
8. **成功落库**：`advanceVersionsReady(projectId, first.getId(), partialErrors)`（委托状态服务，条件更新防回退 + 首版两拆分；部分失败明细写 `last_version_error`，无失败则清空）。
9. **整体失败/异常**：`failVersionsToReady(projectId, e.getMessage())`（仅生成中状态回 READY，错误截断 1000 收在状态服务）；**异步体顶层 catch 吞异常不外抛**（异步线程无调用方），避免卡 `GENERATING_VERSIONS` 到 10min 自愈。

---

## 4. 版本相关接口

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects/{id}/versions` | 三角色 | — | `{versions[]}`（全量，按 id 升序；仿写版含 `similarity_score`/`similarity_report`） |
| PUT | `/api/projects/{id}/current-version` | ADMIN/EDITOR | `?versionId=` | `{ok:true}`（设定当前版本，供预览/发布取图与渲染） |
| PUT | `/api/projects/{id}/versions/{versionId}/content` | ADMIN/EDITOR | `{contentMd}` | `{ok:true}`（预览页左栏编辑保存，S4；400 参数错 / 500 保存失败） |
| PUT | `/api/projects/{id}/versions/{versionId}/title` | ADMIN/EDITOR | `{title}` | `{ok:true}`（编辑版本标题，S6；400/500 同上） |
| PUT | `/api/projects/{id}/selected-title` | ADMIN/EDITOR | `{title}`（空串清除） | `{ok:true}`（简报阶段点选标题，S6；`title>200` → 400；项目不存在 404） |

- `setCurrent` 语义：校验项目与版本归属（版本属于该项目），否则 400。

---

## 5. 状态推进与展示字段（关键契约）

- **落版本必须补齐展示字段**：`title`/`version_label`/`style_tag`/`word_count`，否则前端版本卡片渲染 `undefined·undefined`、字数空白（09-10-versions-page-fix 教训）。
- **必须推进状态机 + 设 current**：只写产物表不推状态会让步骤导航锁死下游（`maxReachableStepOf`），前端卡在上一步。
- 深度批量 `/deep/generate` 与多版本 `VersionService.generate` 共享上述语义（前者追加不覆盖）。
- **事实手册按 `kind` 分组呈现（R5，09-27-tavily-extract-kind-hypotheses）**：`DeepWriterService.write` 取 `fact_sheet.entries` 转写作 prompt 时，任一条目带 `kind`（`param`/`background`）即分「【参数事实】(可逐字引用数值)」与「【背景素材】(仅用于叙事,不得据此新增数值)」两段（缺 kind 条目兜底进参数组，空组写 `- (无)`）；`kind` 语义与手册字段契约见 [brief-generation.md §5](brief-generation.md)。**全无 `kind`（历史 fact_sheet）时退化为原平铺行为**——prompt 逐字与旧实现等价。逐条仍保留 09-26 R1 的「证据:{snippet}」（≤200 字）透传。
- **排版分节档位自适应（09-27-shared-layout-rules R3）**：`VersionService.generateOne` 的 `layoutRules` 首行「全文用 X~Y 个「## 小标题」分节,每节 Z 段」按 `p.getWordCountTarget()` 经共享 `com.sparkora.service.LayoutRules.sectionSpec` 生成（分档与深度写作完全同档）；其余两条 bullet（加粗 / 单段 ≤5 行）逐字保留。此前写死「2~4 个」，与深度链路不一致；两链路只共享**分类结果**，文案格式不变（`VersionService` 仍为三段 bullet 列表、深度保持单行分号串）。

---

## 6. 关键实现路径

- 后端：`com.sparkora.service.VersionService`、`deep.service.DeepWriterService`（深度写作）、`service.ImitationService`（相似度自检）、`domain.entity.ArticleVersionEntity`、`mapper.ArticleVersionMapper`。
- 前端：`views/project/StepVersions.vue`（风格选择/版本卡片/相似度行）。
- 表：`sparkora_article_version`。

---

## 7. 已知限制

- 主题创作多版本接口已封死（410），仅仿写可用；主题正文为深度批量生成（`/deep/generate`）。
- 版本插图登记（`sparkora_article_version_image`）不参与渲染（正文插图落点只由 `content_md` 中的 `![](url)` 决定），详见 [image.md](image.md)「已知债务」。
