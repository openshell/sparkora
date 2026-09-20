# 项目生命周期（工作台 / 新建 / 项目详情）

> 回链：[系统说明总览](../README.md)

职责：创作项目的列表工作台、新建表单（含仿写入口）、项目详情向导与生成入口。字段级与接口契约如下（原 §3/§5/§6）。

---

## 1. 工作台 = 创作项目列表（§3）

### 1.1 页面 `/`（`frontend/src/views/ProjectList.vue`）

- 顶栏：品牌 `Sparkora` + 用户菜单（用户名/角色 + 登出）。
- 主区：项目卡片或表格（分页），每行含：主题、关键词、创建人、状态、更新时间、操作（进入详情）。
- 工具栏：**＋ 新建创作任务**按钮（角色 ≥ editor 可见）。
- 空态：无数据时提示「还没有创作任务，点击右上新建」。

### 1.2 字段级（`ArticleProjectEntity` → 表 `sparkora_article_project`）

| 字段 | 类型 | 表单 | 列表 | 说明 |
|---|---|---|---|---|
| id | Long | — | — | 自增主键 |
| topic | String(200) | ✅ 必填 | ✅ | 主题 |
| keywords | String(500) | 选填 | ✅ | 逗号分隔 |
| audience | String(200) | 选填 | — | 目标读者 |
| word_count_target | Integer | 选填 | — | 目标字数 |
| brand_voice_profile_id | Long | 选填 | — | 可选品牌语气（S0 先存不启用） |
| status | String(20) | — | ✅ | 见 [overview.md §4 状态机](overview.md)（S1/S1b 扩展后含 6 态） |
| current_brief_id | Long | — | — | S1：指向当前简报（`sparkora_article_brief.id`） |
| last_brief_error | String(1000) | — | — | S1：最近一次简报生成失败原因（成功后清空，前端详情页展示） |
| current_version_id | Long | — | — | S1b：指向选定版本（`sparkora_article_version.id`） |
| last_version_error | String(1000) | — | — | S1b：最近一次版本生成失败原因（成功后清空；部分成功时记录失败明细） |
| created_by | String | — | ✅ | 审计字段 |
| created_at / updated_at | Datetime | — | ✅ | 审计字段 |
| remark | String(500) | 选填 | — | 备注 |
| gen_source | String(20) | ✅（仿写单选） | — | 文章仿写（[imitation.md](imitation.md)）：`TOPIC`(默认)/`IMITATION`；仅仿写时随 create 提交 |
| imitation_text | TEXT | 仿写必填 | — | 参考原文全文（仅 IMITATION 非空，≤20000 字） |
| imitation_analysis | TEXT | — | — | 原文分析结果 JSON `{genre,structure,sentenceFeatures}`（展示冗余存储） |

> S5+ 发布/预览相关列（`publish_media_id` / `publish_theme` / `published_at` / `last_publish_error` / `author` / `source_url` / `preview_theme` / `preview_highlight` / `preview_mac_style` / `preview_footnote`）见 [preview.md](preview.md) 与 [publish.md](publish.md)。

### 1.3 接口契约（§3.3）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects` | 三角色 | `page,size,topic,status,orderBy,orderDir` | `{rows[],total,page,size}` |
| GET | `/api/projects/{id}` | 三角色 | — | `{project}`（S1/S1b 起含 `current_brief_id` / `current_version_id` / `last_*_error`；[imitation.md](imitation.md) 起含 `gen_source`/`imitation_text`/`imitation_analysis`） |
| POST | `/api/projects` | ADMIN/EDITOR | 上面表单 JSON（仿写增 `genSource`/`imitationText`；IMITATION 时原文必填非空，否则 `R.fail(400)`；非法 genSource 400；TOPIC 行为与现状一致） | `{id}` |
| PUT | `/api/projects/{id}` | ADMIN/EDITOR | 表单 JSON | `{ok:true}` |
| DELETE | `/api/projects/{ids}` | ADMIN | — | `{ok:true}` |
| POST | `/api/projects/{id}/generate/brief` | ADMIN/EDITOR | — | **2026-09-09 起封死**：恒 `R.fail(410, "生成流程已升级为深度模式,请使用深度生成(/deep/clarify)")`（存量 FAST 项目产物可读，重新生成走深度） |
| GET | `/api/projects/{id}/brief` | 三角色 | — | `{brief}`（无则 `data:null`） |
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | **2026-09-09 起封死（主题创作）**：恒 `R.fail(410, ...)`；**仿写项目例外**（[imitation.md](imitation.md)）：`genSource=IMITATION` 时本接口复用为仿写生成（每风格一版，产出含 `similarity_score`/`similarity_report`） |
| GET | `/api/projects/{id}/versions` | 三角色 | — | `{versions[]}`（全量，按 id 升序；仿写版含 `similarity_score`/`similarity_report`） |
| PUT | `/api/projects/{id}/current-version` | ADMIN/EDITOR | `?versionId=` | `{ok:true}` |
| POST | `/api/projects/{id}/imitation/analyze` | ADMIN/EDITOR | — | `ArticleBriefEntity`（`gen_mode=IMITATION`，含 `styleRecommendations`；409=状态冲突；失败回 DRAFT 写 `last_brief_error`；契约见 [imitation.md](imitation.md)） |
| GET | `/api/projects/{id}/imitation` | 三角色 | — | `{briefId,titleCandidates,coreViewpoints,outline,styleRecommendations,analysis}`（无则 `data:null`；契约见 [imitation.md](imitation.md)） |
| GET | `/api/settings` | ADMIN, EDITOR | — | `{id, kbEnabled, webSearchEnabled, updatedBy, updatedAt}`（单行；首访自动初始化；契约见 [settings.md](settings.md)） |
| PUT | `/api/settings` | **仅 ADMIN** | `{kbEnabled?, webSearchEnabled?}`（null 不改） | 同 GET（写后刷缓存；契约见 [settings.md](settings.md)） |
| GET | `/api/styles` | 三角色 | `?enabledOnly=` | `{styles[]}`（契约见 [style-library.md](style-library.md)） |
| POST | `/api/styles/extract` | ADMIN/EDITOR | `{name, sourceText}` | `{style}`（契约见 [style-library.md](style-library.md)） |
| POST | `/api/styles/extract/preview` | ADMIN/EDITOR | `{name?, sourceText}` | `R<StyleProfileEntity>`（AI 提炼**不入库**，`id=null`；两步式提炼预览，人工修改后入库走 `POST /api/styles`；09-10-style-library-enhance） |

> `orderBy` 白名单：`updatedAt`(默认)/`createdAt`；`orderDir`：`desc`(默认)/`asc`；非法值静默回退默认。其余参数语义不变。

> S3b 配图接口（`/api/images/**`、`/api/projects/{id}/images/**`）字段级契约见 [image.md](image.md)。S6 起 `complete-images` 已删除。

---

## 2. 新建创作任务 `/projects/new`（§5）

- 表单 = 上面「表单」列字段，字段级校验：`topic` 必填、长度限制。
- 操作：保存（DRAFT）或「创建并生成 Brief →」（DRAFT→GENERATING_BRIEF→READY，S0 只落库）。
- 校验错误逐字段 `el-form` 提示，后端 `@Validated` 兜底。
- **思考深度（2026-09-09 模式收敛修订，09-09-brief-gen-redesign R2；2026-09-11 clarify 异步化修订，09-11-brief-gen-flow-refactor）**：创建表单**不再含模式单选**——快速模式（FAST）已下线，所有生成必走深度流程。创建成功后**先 `await` 直发 `/deep/clarify`（202 异步语义，毫秒级落 PLANNING 占位行）再跳详情页**，消除「导航早于落库」竞态；跳转**不再携带 `?gen=deep` 路径意图参数**，详情页据 brief 侧 `plan_status=PLANNING` 展示「研究计划生成中」并自轮询 `/deep/status`，完成后自动展开澄清表单。失败写 `project.last_brief_error` 并回可重试引导态。FAST 生成接口 `/generate/brief`、`/generate/versions` 保留路由但返回 `R.fail(410, "生成流程已升级为深度模式...")`（封死不删，存量 FAST 项目产物可读，重新生成走深度）。
- 仿写入口（`genSource=IMITATION`）见 [imitation.md](imitation.md)：`ProjectEdit.vue` 区块 00「创作方式」radio-button。

---

## 3. 项目详情 / 生成入口 `/projects/:id`（§6）

- 顶部**四步**步骤条（简报→版本→预览→发布，2026-08-28 决策：删「校验」步；2026-09-03 S6 决策：配图并入预览，删「配图」步）；简报/版本/预览三步为子路由（`ProjectLayout.vue` 外层步骤导航 + `Step*.vue` 子路由），「发布」为 S5 子路由 `StepPublish.vue`（2026-08-31 起已实现）。
- 步骤推进与可达范围由 `frontend/src/constants/project.js` 依据 `project.status` 计算；生成中停留当前步骤（`maxReachableStepOf` 放开到 index=3，发布步对 VERSIONS_READY/PUBLISHED_DRAFT 解锁）。
- 「生成简报」→ `POST /api/projects/{id}/generate/brief`（S1 起真实 AI，同步调用，前端 loading + 以 `project.status` 为事实源轮询恢复；2026-09-09 起该接口封死 410，实际走深度链路 `/deep/clarify`，见 [brief-generation.md](brief-generation.md)）。
- 「预览」步（S4 + S6 配图并入）：左编辑右预览；工具栏「配图」面板提供**图库插入**（全量图库选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，产物进图库后插入正文）两种配图来源；封面走 frontmatter `cover` 元信息。车型库图片接入**预留**（暂不开发）。契约见 [preview.md](preview.md)。
- 「发布」步（S5）：发布摘要 + 参数 + 确认弹层 → `POST /api/projects/{id}/publish` → 成功进 PUBLISHED_DRAFT（可重发），契约见 [publish.md](publish.md)。

---

## 4. 关键实现路径

- 后端：`com.sparkora.web.controller.ArticleProjectController`（CRUD + 生成入口 + 配图/发布元信息端点）、`com.sparkora.service.ArticleProjectCarService`、`domain.entity.ArticleProjectEntity`、`mapper.ArticleProjectMapper`。
- 前端：`views/ProjectList.vue`（工作台）、`views/ProjectEdit.vue`（新建/编辑，含仿写分支）、`views/project/ProjectLayout.vue`（步骤条 + 子路由）、`constants/project.js`（状态映射唯一事实源）。
- 表：`sparkora_article_project`（`src/main/resources/db/schema.sql` 幂等建表）。

---

## 5. 已知限制

- 审计表 `sparkora_audit_log` 后置未建（S0 范围），关键动作仅日志文件。
- 车型库图片接入预留（暂不开发）。
- 存量 FAST 项目产物可读但不可再生成（接口 410），重新生成必须走深度链路。
