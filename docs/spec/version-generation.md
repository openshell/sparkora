# 版本生成（多版本正文）

> 回链：[系统说明总览](../README.md)

职责：基于简报（brief）+ 用户选择的风格，产出多版本正文，并维护「当前版本」与版本展示字段。

- 主题创作的**多版本生成接口已封死**（2026-09-09，恒 410）；主题正文由深度链路的 `/deep/generate` 单版生成（见 [brief-generation.md](brief-generation.md)）。`POST /generate/versions` 仅保留给**文章仿写**（见 [imitation.md](imitation.md)）。
- 项目状态机推进（READY→GENERATING_VERSIONS→VERSIONS_READY / 失败回退）见 [overview.md §4](overview.md)。

---

## 1. 版本字段级（`ArticleVersionEntity` → 表 `sparkora_article_version`）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 自增主键 |
| project_id | BIGINT | 所属项目 |
| brief_id | BIGINT | 基于哪个 brief 生成 |
| title | VARCHAR(200) | 该版本标题（可不同于 brief 候选） |
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
| body_image_ids | VARCHAR(1000) | S3b：正文插图 id 列表（逗号分隔，有序） |
| created_at | TIMESTAMP | 创建时间 |

- 版本-图片关联**挂版本，不挂项目**：多版本各有排版，预览/发布按「当前版本」取图；项目级关联无法表达版本间差异（见 [image.md](image.md)）。
- 索引：`idx_version_project (project_id)`。
- 幂等迁移：`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（`rag_status` / `fact_risks` / `rag_citations` / `cover_image_id` / `body_image_ids`；仿写字段见 [imitation.md](imitation.md)）。

---

## 2. `POST /api/projects/{id}/generate/versions` 契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | **主题创作恒 `R.fail(410, "生成流程已升级为深度模式,版本生成请使用深度生成(/deep/generate)")`**；**仿写项目例外**（`genSource=IMITATION`）：每风格一版，产出含 `similarity_score`/`similarity_report`，契约详见 [imitation.md](imitation.md) |

- `styleIds` 为风格库 id 列表；空 → `IllegalArgumentException`（400「至少选择一个风格」）；超过 10 个 → 400「一次最多生成 10 版」。
- 所选风格查无 → 400「所选风格不存在」。

---

## 3. `VersionService.generate` 语义

`com.sparkora.service.VersionService`：

1. **入参校验**：`styleIds` 非空、≤ `LABELS.length()`（10）。
2. **项目/简报就绪校验**：项目不存在 → 400；`current_brief_id` 为空 → `NotReadyException`「尚未生成 brief，无法生成版本」；brief 不存在 → `NotReadyException`。
3. **并发防护 + 原子抢占**（消除 check-then-set 竞态）：
   - 项目处于生成中（`stuckGenerating(p)`，`updated_at` 在 10 分钟内）→ `IllegalStateException`「该项目正在生成中，请稍候（刷新页面可查看进度）」。
   - 条件更新置 `GENERATING_VERSIONS`：仅当 `status ∈ {READY, VERSIONS_READY}`（首生成/追加）**或**「生成中且已陈旧（超 `STALE_GENERATING_MS=10min`，进程已死，自愈）」才生效；陈旧分支必须限定生成中状态，否则任何 `updated_at` 较旧的下游状态都会被误放行、状态机回退。`PUBLISHED_DRAFT` 之后已触发下一步，再生成版本会把状态机拉回 VERSIONS_READY，拒绝（`projectStatusGuardMsg` =「…下游步骤已触发，不支持回退重做」）。
   - 同时清 `last_version_error`。
4. **风格与 RAG**：`styleMapper.selectBatchIds(styleIds)`；RAG 检索 `CarRagService.retrieveForGeneration(topic, 8, modelIds)`（`modelIds` 由 `ArticleProjectCarService.listModelIds` 取，S8 起仅作**写作锚点加权**，未关联也全库检索）。仿写模式跳过 RAG（`RagResult.EMPTY`，`ragStatus=NO_KNOWLEDGE`）。
5. **逐风格生成**：每风格一版，`label = A/B/C…`；单版失败仅 warn 并计入 `perVersionErrors`（**部分成功也继续**）；全部失败 → `AiException("全部版本生成失败: …")`。
6. **成功落库**：插入各版本 → 默认选**第一版**为当前（`p.setCurrentVersionId(first.getId())`）→ `status=VERSIONS_READY` → `last_version_error` 记录部分失败明细（无失败则清空）→ `projectMapper.updateById(p)`。
7. **整体失败**：重取项目 → `status=READY` + `last_version_error`（截断 1000 字）→ 抛 `AiException("版本生成失败: …")`（接口 500）。

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
- 深度单版 `/deep/generate` 与多版本 `VersionService.generate` 共享上述语义（前者追加不覆盖）。

---

## 6. 关键实现路径

- 后端：`com.sparkora.service.VersionService`、`deep.service.DeepWriterService`（深度写作）、`service.ImitationService`（相似度自检）、`domain.entity.ArticleVersionEntity`、`mapper.ArticleVersionMapper`。
- 前端：`views/project/StepVersions.vue`（风格选择/版本卡片/相似度行）。
- 表：`sparkora_article_version`。

---

## 7. 已知限制

- 主题创作多版本接口已封死（410），仅仿写可用；主题正文为深度单版。
- 版本插图 `body_image_ids` 不参与渲染（正文插图落点只由 `content_md` 中的 `![](url)` 决定），详见 [image.md](image.md)「已知债务」。
