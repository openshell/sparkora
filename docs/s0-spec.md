# Sparkora · S0 可审规格（屏幕清单 + 字段级 + 接口契约 + 状态机）

**范围**：S0 项目骨架 + Spring Security 登录 + 流程化工作台（项目列表）+ 新建创作任务 + 项目详情（生成入口占位）。
**当前进度**：S0~S4 已实现（登录、项目 CRUD、简报生成、多版本正文、风格库、配图三来源、wenyan 同核预览 + 七牛图床）；**S5 发布模块为当前开发阶段**（本文档 §4/§11 已同步：PUBLISHED_DRAFT 终态 + 可重发，发布通道 = wenyan-server 上传 JSON + 发布）。
**2026-08-28 决策**：原六步流程中的「校验」步骤**彻底取消**（不做事实核查步骤，SEARXNG/CRAWL4AI 联网核查不启用），流程改为五步：简报→版本→配图→预览→发布。
**2026-08-31 S5 决策**：发布成功进入 **PUBLISHED_DRAFT**（公众号草稿箱已收），**可重发覆盖**（再次发布刷新 media_id/published_at），状态为终态、不再回退。
**2026-09-03 S6 决策**：**配图并入预览步骤**，流程改为四步：简报→版本→预览→发布；**彻底移除 IMAGES_READY 状态**，`VERSIONS_READY` 后直接可预览/发布；预览内提供图库插入 + AI 生图配图能力。车型库图片接入**预留**（暂不开发）。
**技术栈**：Spring Boot 3 + MyBatis-Plus + Spring Security + Vue3/Element Plus（流程化创作工作台，不套重型 admin 外壳）。包结构 `com.sparkora`。
**前端适配**：**移动端适配**（响应式，移动优先）。Element Plus 响应式栅格 + 断点（xs/sm/md/lg）；移动端单列、汉堡顶栏、表格转卡片、表单单列堆叠、触控目标≥44px。不引 Vant 等额外移动端框架。
**用途**：在真机上逐条打勾验收。S0 目标——能登录、能建项目、能看到生成入口。

> 2026-08-18 起放弃若依，本规格已按轻量栈重写。不再有 `sys_*` 复用、不再有 `@SaCheckPermission`/`v-hasPermi`，改用 Spring Security 原生。

---

## 0. 自建 vs 复用总览

S0 是从零搭骨架，以下能力**全部自建**（不引入若依等重型后台）：

| 能力 | 实现方式 | 说明 |
|---|---|---|
| 登录 / 会话 | Spring Security + **JWT**（已定） | 自建 `AuthController` + `SecurityConfig`；密钥/过期读 `.env` |
| 用户 / 角色 | `sparkora_user` + `sparkora_role`（最简：admin/editor/viewer） | MVP 单 workspace，先不做部门树 |
| 工作台布局 | Vue3 + Element Plus，**向导式**而非侧栏 admin | 贴合"主题→brief→版本→编辑→预览→发布"流程 |
| 移动端适配 | Element Plus 响应式栅格 + 断点，**移动优先** | 移动单列/汉堡栏/表格转卡片/触控≥44px；不引 Vant |
| 项目列表（工作台首页） | 自建 CRUD + 分页 | `ArticleProjectController` |
| 新建创作任务 | 自建表单 + 校验 | Element Plus `el-form` |
| 项目详情 / 生成入口 | 自建详情页 + 步骤条 | S0 仅第一步可点 |
| 审计 | S0 先用日志文件（`logs/`）；`sparkora_audit_log` 表后置 | 记录生成/编辑/发布关键动作 |
| 配置来源 | 全部从 `.env` 读取（`.env.example` 为模板） | 数据库/JWT/AI/微信/wenyan 均已预留 |

---

## 1. 路由 / 权限结构

后端 REST 路径前缀 **`/api`**，Spring Security 保护 `/api/**`，登录走 `/api/auth/login`。

```
前端路由（Vue3）
/login                         登录页
/                              工作台 = 创作项目列表（首页）
/projects/new                  新建创作任务（表单）
/projects/:id                  项目详情（向导步骤条，S0 仅 step1 可点）

后端 API（/api 前缀，Spring Security；接口清单截至 S3b）
POST /api/auth/login           登录（permitAll）
POST /api/auth/logout          登出（JWT 无状态，前端丢弃 token）
GET  /api/auth/me              当前用户
GET   /api/projects            列表（分页）          权限 ADMIN/EDITOR/VIEWER
GET   /api/projects/{id}       详情                  权限 ADMIN/EDITOR/VIEWER
POST  /api/projects            新建                  权限 ADMIN/EDITOR
PUT   /api/projects/{id}       编辑                  权限 ADMIN/EDITOR
DELETE /api/projects/{ids}     删除                  权限 ADMIN
POST  /api/projects/{id}/generate/brief    简报生成（已封死:410 引导深度模式,2026-09-09）  权限 ADMIN/EDITOR
GET   /api/projects/{id}/brief             取当前简报           权限 ADMIN/EDITOR/VIEWER
POST  /api/projects/{id}/generate/versions 多版本生成（已封死:410 引导深度模式,2026-09-09） 权限 ADMIN/EDITOR
GET   /api/settings                        系统检索设置（读）    权限 ADMIN/EDITOR
PUT   /api/settings                        系统检索设置（写）    权限 ADMIN
GET   /api/projects/{id}/versions          版本列表             权限 ADMIN/EDITOR/VIEWER
PUT   /api/projects/{id}/current-version   设定当前版本         权限 ADMIN/EDITOR
GET   /api/images              图库列表(tag 多值 AND 筛选) 权限 ADMIN/EDITOR/VIEWER
POST  /api/images/upload       上传图库图(支持预选标签) 权限 ADMIN/EDITOR
POST  /api/images/generate-text    文生图(支持预选标签)  权限 ADMIN/EDITOR
POST  /api/images/generate-from-image  图生图(支持预选标签) 权限 ADMIN/EDITOR
GET   /api/images/tags         全库标签清单(含引用数)  权限 ADMIN/EDITOR/VIEWER
GET   /api/images/{id}/source  图片来源追溯(新闻标题/日期/原文) 权限 ADMIN/EDITOR/VIEWER
POST  /api/images/search       图片语义检索(自然语言查图) 权限 ADMIN/EDITOR/VIEWER
POST  /api/images/embeddings/rebuild  图片向量全量重建 权限 ADMIN/EDITOR
PUT   /api/images/{id}/tags    单图标签全量覆盖        权限 ADMIN/EDITOR
POST  /api/images/tags/batch   批量补打/移除标签        权限 ADMIN/EDITOR
POST  /api/projects/{id}/images/{imageId}/cover   选封面   权限 ADMIN/EDITOR
POST  /api/projects/{id}/images/{imageId}/body     选/取消正文插图 权限 ADMIN/EDITOR
POST  /api/projects/{id}/illustration-suggestions  配图建议(按段落锚点语义检索,零副作用) 权限 ADMIN/EDITOR/VIEWER
POST  /api/projects/{id}/illustration-suggestions/dismiss  忽略某锚点建议组(幂等) 权限 ADMIN/EDITOR
GET   /api/styles              风格库列表            权限 ADMIN/EDITOR/VIEWER
GET   /api/styles/{id}         风格详情              权限 ADMIN/EDITOR/VIEWER
POST  /api/styles              新建风格              权限 ADMIN/EDITOR
PUT   /api/styles/{id}         编辑风格              权限 ADMIN/EDITOR
DELETE /api/styles/{id}        删除风格              权限 ADMIN
POST  /api/styles/extract      样文提炼风格入库      权限 ADMIN/EDITOR
GET   /api/projects/{id}/publish-options   发布参数与通道状态  权限 ADMIN/EDITOR/VIEWER
POST  /api/projects/{id}/publish           发布公众号草稿箱    权限 ADMIN/EDITOR
PUT   /api/projects/{id}/preview-style     保存预览页样式(项目级) 权限 ADMIN/EDITOR
PUT   /api/projects/{id}/publish-meta      保存发布元信息(作者/原文地址) 权限 ADMIN/EDITOR
```

- 角色：`ADMIN` / `EDITOR` / `VIEWER`。权限矩阵（S1/S2 实现并真机验证）：**读接口（GET）三角色放行（viewer 只读），写接口（POST/PUT）限 ADMIN/EDITOR，DELETE 仅 ADMIN**。
- 生成接口并发防护（S2a 补）：项目处于 GENERATING_BRIEF / GENERATING_VERSIONS 时再次触发，返回 `R.fail(409, "该项目正在生成中…")`，不重复调 AI；若生成中状态已陈旧（updated_at 超过 10 分钟，如 JVM 中途死亡/重启遗留），原子条件更新放行重新生成以自愈。brief 未就绪时触发版本生成返回 `R.fail(400)`。
- 前端用 `v-if`/路由守卫判断角色（从 `/api/auth/me` 取），不做若依那种菜单权限点。

---

## 2. 登录（Spring Security）

- 前端 `/login` → 后端 `POST /api/auth/login`（用户名/密码）。
- 登录成功签发 **JWT**（密钥 `JWT_SECRET`、过期 `JWT_EXPIRE_MINUTES` 从 `.env` 读），返回 token + 当前用户信息。
- 前端后续请求带 `Authorization: Bearer <token>`；后端 `JwtAuthenticationFilter` 校验。
- 验收：未登录访问 `/api/**` → 401（已注册 `AuthenticationEntryPoint`，实测通过；已认证但角色不足仍 403）；前端未登录访问受保护路由 → 跳 `/login`；登出（前端丢弃 token）后再次访问需重新登录。

---

## 3. 工作台 = 创作项目列表（自建）

### 3.1 页面 `/`
- 顶栏：品牌 `Sparkora` + 用户菜单（用户名/角色 + 登出）。
- 主区：项目卡片或表格（分页），每行含：主题、关键词、创建人、状态、更新时间、操作（进入详情）。
- 工具栏：**＋ 新建创作任务**按钮（角色 ≥ editor 可见）。
- 空态：无数据时提示「还没有创作任务，点击右上新建」。

### 3.2 字段级（ArticleProject 实体 → 表 `sparkora_article_project`）

| 字段 | 类型 | 表单 | 列表 | 说明 |
|---|---|---|---|---|
| id | Long | — | — | 自增主键 |
| topic | String(200) | ✅ 必填 | ✅ | 主题 |
| keywords | String(500) | 选填 | ✅ | 逗号分隔 |
| audience | String(200) | 选填 | — | 目标读者 |
| word_count_target | Integer | 选填 | — | 目标字数 |
| brand_voice_profile_id | Long | 选填 | — | 可选品牌语气（S0 先存不启用） |
| status | String(20) | — | ✅ | 见状态机 §4（S1/S1b 扩展后含 5 态） |
| current_brief_id | Long | — | — | S1：指向当前简报（sparkora_article_brief.id） |
| last_brief_error | String(1000) | — | — | S1：最近一次简报生成失败原因（成功后清空，前端详情页展示） |
| current_version_id | Long | — | — | S1b：指向选定版本（sparkora_article_version.id） |
| last_version_error | String(1000) | — | — | S1b：最近一次版本生成失败原因（成功后清空；部分成功时记录失败明细） |
| created_by | String | — | ✅ | 审计字段 |
| created_at / updated_at | Datetime | — | ✅ | 审计字段 |
| remark | String(500) | 选填 | — | 备注 |
| gen_source | String(20) | ✅（仿写单选） | — | 文章仿写(§14)：TOPIC(默认)/IMITATION；仅仿写时随 create 提交 |
| imitation_text | TEXT | 仿写必填 | — | 参考原文全文（仅 IMITATION 非空，≤20000 字） |
| imitation_analysis | TEXT | — | — | 原文分析结果 JSON `{genre,structure,sentenceFeatures}`（展示冗余存储） |

### 3.3 接口契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects` | 三角色 | `page,size,topic,status,orderBy,orderDir` | `{rows[],total,page,size}` |
| GET | `/api/projects/{id}` | 三角色 | — | `{project}`（S1/S1b 起含 current_brief_id / current_version_id / last_*_error；§14 起含 gen_source/imitation_text/imitation_analysis） |
| POST | `/api/projects` | ADMIN/EDITOR | §3.2 表单 JSON（仿写增 `genSource`/`imitationText`；IMITATION 时原文必填非空，否则 `R.fail(400)`；非法 genSource 400；TOPIC 行为与现状一致） | `{id}` |
| PUT | `/api/projects/{id}` | ADMIN/EDITOR | 表单 JSON | `{ok:true}` |
| DELETE | `/api/projects/{ids}` | ADMIN | — | `{ok:true}` |
| POST | `/api/projects/{id}/generate/brief` | ADMIN/EDITOR | — | **2026-09-09 起封死**：恒 `R.fail(410, "生成流程已升级为深度模式,请使用深度生成(/deep/clarify)")`（存量 FAST 项目产物可读，重新生成走深度） |
| GET | `/api/projects/{id}/brief` | 三角色 | — | `{brief}`（无则 `data:null`） |
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | **2026-09-09 起封死(主题创作)**：恒 `R.fail(410, ...)`；**仿写项目例外(§14)**：genSource=IMITATION 时本接口复用为仿写生成（每风格一版，产出含 similarity_score/similarity_report） |
| GET | `/api/projects/{id}/versions` | 三角色 | — | `{versions[]}`（全量，按 id 升序；仿写版含 similarity_score/similarity_report） |
| PUT | `/api/projects/{id}/current-version` | ADMIN/EDITOR | `?versionId=` | `{ok:true}` |
| POST | `/api/projects/{id}/imitation/analyze` | ADMIN/EDITOR | — | `ArticleBriefEntity`（gen_mode=IMITATION，含 styleRecommendations；409=状态冲突；失败回 DRAFT 写 last_brief_error；契约见 §14） |
| GET | `/api/projects/{id}/imitation` | 三角色 | — | `{briefId,titleCandidates,coreViewpoints,outline,styleRecommendations,analysis}`（无则 `data:null`；契约见 §14） |
| GET | `/api/settings` | ADMIN, EDITOR | — | `{id, kbEnabled, webSearchEnabled, updatedBy, updatedAt}`（单行;首访自动初始化;契约见 §6b-s） |
| PUT | `/api/settings` | **仅 ADMIN** | `{kbEnabled?, webSearchEnabled?}`（null 不改） | 同 GET（写后刷缓存;契约见 §6b-s） |
| GET | `/api/styles` | 三角色 | `?enabledOnly=` | `{styles[]}` |
| POST | `/api/styles/extract` | ADMIN/EDITOR | `{name, sourceText}` | `{style}` |
| POST | `/api/styles/extract/preview` | ADMIN/EDITOR | `{name?, sourceText}` | `R<StyleProfileEntity>`（AI 提炼**不入库**,id=null;两步式提炼预览,人工修改后入库走 POST /api/styles;09-10-style-library-enhance） |

> `orderBy` 白名单:`updatedAt`(默认)/`createdAt`;`orderDir`:`desc`(默认)/`asc`;非法值静默回退默认。其余参数语义不变。

> S3b 配图接口（`/api/images/**`、`/api/projects/{id}/images/**`）字段级契约见 §10。S6 起 `complete-images` 已删除。

> 所有响应统一 `R<T>` = `{code, msg, data}`，`code=0` 成功。**注意：业务失败（含登录失败、生成失败）均为 HTTP 200 + `R.fail`，前端不能只依赖 axios 错误拦截器，必须检查 `code`**（S2a 已在 store.login / ProjectEdit / StepBrief / StepVersions 逐处落实）。

---

## 4. 状态机（ArticleProject.status）

S3b 起共 6 态（S2a 前 5 态照旧；「校验」步骤已取消，不设 FACT_CHECK 态，实现见 BriefService / VersionService / ImageService）：

```
DRAFT ──(生成简报)──▶ GENERATING_BRIEF ──(落库 brief)──▶ READY
              ▲                    │
              └────(失败回退+写 last_brief_error)
READY ──(多版本生成)──▶ GENERATING_VERSIONS ──(落库版本+默认选第一版)──▶ VERSIONS_READY
              ▲                       │
              └────(失败回退+写 last_version_error；部分成功也进 VERSIONS_READY 并记录明细)
VERSIONS_READY ──(发布成功,S5)──▶ PUBLISHED_DRAFT(终态,可重发)
              ▲                        │
              └───(重新选版本回到 VERSIONS_READY？否——配图为增量编辑，不回退)
                                       └──(预览不改状态;发布失败状态原样保留+写 last_publish_error)
```

- **DRAFT**：刚创建（`add` 即 DRAFT）；简报生成失败也回退到此态。
- **GENERATING_BRIEF**：简报生成进行中（先落库再调 AI，前端可观察；再次触发返回 409）。
- **READY**：简报就绪（`current_brief_id` 指向最新简报；S0 语义「记录创建成功」已由 S1 取代）。
- **GENERATING_VERSIONS**：多版本生成进行中（每风格一版；再次触发返回 409）。
- **VERSIONS_READY**：至少一版成功（`current_version_id` 默认指向本次第一版；全部失败才回退 READY）。**S6 起：版本就绪后直接可预览/发布**（配图已并入预览步骤，不再有 IMAGES_READY）。深度单版生成（`/deep/generate`）同样推进 READY→VERSIONS_READY（2026-09-10 修复：落版本后由 DeepController 成功分支推状态 + 首版设 current）。**存量数据自愈（09-10-versions-page-fix）**：schema.sql 启动时把历史「有版本但仍 READY/DRAFT」的项目推到 VERSIONS_READY，current_version_id 为空时设首版（幂等，与 R3 字段回填同段）。
- **PUBLISHED_DRAFT**（S5 新增,终态）：发布成功（渲染 HTML 经 wenyan-server 写入公众号草稿箱,拿到 media_id）。可重发：再次 `POST /publish` 重新渲染并覆盖草稿,刷新 publish_media_id/published_at/publish_theme;发布失败状态原样保留并写 `last_publish_error`(成功后清空);`publish` 仅在 VERSIONS_READY/PUBLISHED_DRAFT 可调用,否则 `R.fail(400)`(错误经状态校验文案提示,如「尚未生成正文版本,无法预览」)。
- 前端状态映射唯一事实源：`frontend/src/constants/project.js`（文案/标签色/步骤推进/生成中判定/发布判定 isPublishable/isPublished）。**S6 起 `statusMeta` 对历史残留 `IMAGES_READY` 归一为 `VERSIONS_READY`**（兼容旧数据，避免历史项目无法预览/发布）。
- **状态守护（2026-09-01 定稿）：下游步骤已触发后，上游生成动作前后端双重拦截，禁止状态机回退。**
  - 后端：`generate/brief` 仅在 DRAFT/READY、`generate/versions` 仅在 READY/VERSIONS_READY 放行（条件更新 WHERE 白名单；「生成中且陈旧超 10 分钟」分支自愈但同样限定生成中状态，防 updated_at 较旧的下游状态被误放行）；违反返回 `R.fail(409, 中文原因「…下游步骤已触发，不支持回退重做」)`。
  - 前端：StepBrief「重新生成」仅 READY 可见；StepVersions「再生成其他风格」仅 VERSIONS_READY 可见——下一步已触发后不再显示上一步的生成按钮。

---

## 5. 新建创作任务 `/projects/new`

- 表单 = §3.2 中「表单」列字段，字段级校验：`topic` 必填、长度限制。
- 操作：保存（DRAFT）或「创建并生成 Brief →」（DRAFT→GENERATING_BRIEF→READY，S0 只落库）。
- 校验错误逐字段 `el-form` 提示，后端 `@Validated` 兜底。
- **思考深度（2026-09-09 模式收敛修订,09-09-brief-gen-redesign R2;2026-09-11 clarify 异步化修订,09-11-brief-gen-flow-refactor）**：创建表单**不再含模式单选**——快速模式（FAST）已下线，所有生成必走深度流程。创建成功后**先 `await` 直发 `/deep/clarify`（202 异步语义，毫秒级落 PLANNING 占位行）再跳详情页**，消除「导航早于落库」竞态；跳转**不再携带 `?gen=deep` 路径意图参数**，详情页据 brief 侧 `plan_status=PLANNING` 展示「研究计划生成中」并自轮询 `/deep/status`，完成后自动展开澄清表单。失败写 `project.last_brief_error` 并回可重试引导态。FAST 生成接口 `/generate/brief`、`/generate/versions` 保留路由但返回 `R.fail(410, "生成流程已升级为深度模式...")`（封死不删，存量 FAST 项目产物可读，重新生成走深度）。

---

## 6. 项目详情 / 生成入口占位 `/projects/:id`

- 顶部**四步**步骤条（简报→版本→预览→发布，2026-08-28 决策：删「校验」步；2026-09-03 S6 决策：配图并入预览，删「配图」步）；简报/版本/预览三步为子路由（`ProjectLayout.vue` 外层步骤导航 + `Step*.vue` 子路由），「发布」为 S5 子路由 `StepPublish.vue`（2026-08-31 起已实现）。
- 步骤推进与可达范围由 `frontend/src/constants/project.js` 依据 `project.status` 计算；生成中停留当前步骤。
- 「生成简报」→ `POST /api/projects/{id}/generate/brief`（S1 起真实 AI，同步调用，前端 loading + 以 project.status 为事实源轮询恢复）。
- 「预览」步（S4 + S6 配图并入）：左编辑右预览；工具栏「配图」面板提供**图库插入**（全量图库选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，产物进图库后插入正文）两种配图来源；封面走 frontmatter `cover` 元信息。车型库图片接入**预留**（暂不开发）。
- 「发布」步（S5）：发布摘要 + 参数 + 确认弹层 → `POST /api/projects/{id}/publish` → 成功进 PUBLISHED_DRAFT(可重发),契约见 §12。

### 6b. 知识库「必查+降级可见」（S6.1，2026-09-03）

**语义**：项目**已关联车型**时，生成简报与生成正文**必须发起**一次车型知识库 RAG 检索；检索失败或整体置信度过低**不阻断生成**（硬阻断会把创作绑死在 embedding 服务可用性上），但必须降级可见——AI 被要求在 `factRisks` 标注数据缺失，检索状态随产物落库并展示于前端。未关联车型视为「已查、无知识对象」，不算失败。

**检索状态枚举**（`brief.rag_status` / `version.rag_status`，VARCHAR(20)）：

| 状态 | 含义 | prompt 注入 | 前端展示 |
|---|---|---|---|
| `OK` | 命中且最高相似度 ≥ 整体门槛 | 权威数据注入，严格依据不得编造 | 「知识库 · 已引用」(绿) |
| `LOW_CONFIDENCE` | 有命中但最高相似度 < 整体门槛，**全部抛弃** | 不注入；提示 AI 不得臆造参数、factRisks 标注(建议 high) | 「知识库 · 低置信已抛弃」(橙)；版本卡片加「参数未经知识库核实」 |
| `FAILED` | 检索异常（embedding 服务等），**降级继续** | 不注入；要求 factRisks 标注数据缺失(建议 high)，不得臆造参数 | 「知识库 · 检索失败·已降级」(红)；版本卡片同上 |
| `NO_KNOWLEDGE` | 无车型关联对象或逐块过滤后无命中 | 不注入、不提示（与 S6 现状一致） | 「知识库 · 未引用」(灰) |
| `DISABLED` | **系统设置停用知识库（09-09-brief-gen-redesign，2026-09-09 增补）**：设置页 `kbEnabled=false` 时本地检索不发起 | 不注入任何本地知识块；外部搜索按 `webSearchEnabled` 独立启用（优先外部资料）；双关时 prompt 明确要求标注「未检索任何外部资料,数据未核实」 | 「知识库 · 知识库已停用(全局设置)」(灰)；不得与 NO_KNOWLEDGE 混淆 |

- `FAILED` 优先级高于其余状态：多车型检索时任一车型异常即标 `FAILED`（其余车型照常尝试）。
- 抛弃/失败**不得与「无命中」混淆**：`LOW_CONFIDENCE`/`FAILED` 必须显式落库，前端据此提示。
- `DISABLED` 是**主动停用**语义（设置页可随时切回），与失败/低置信的被动降级不同；仅深度链路产生（快速模式已下线，见 §7 修订）。

**字段级**：`sparkora_article_brief.rag_status`、`sparkora_article_version.rag_status` — `VARCHAR(20)`，可空（历史行为数据为 NULL，前端不展示）；GET brief/versions 响应自然携带该字段，无独立接口。

**知识引用明细（R3，2026-09-05 增补）**：`sparkora_article_brief.rag_citations`、`sparkora_article_version.rag_citations` — `TEXT`（JSON 数组 `[{source:"CAR|KB|NEWS", modelName, chunkType, score, chunkText, docId}]`，`docId` 为 09-15 qa-auto-illustrate 起的可空域内块 id：CAR=car_doc.id / KB=kb_chunk.id / NEWS=news_doc.id），检索 OK 且有命中时随生成落库（与注入 prompt 的 context 同源，上限 24 条、单条文本截断 120 字符，序列化超 8000 字符整体置 null）；`LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE` 为 null。前端简报页「知识库引用」区（`CitationList` 组件）与版本卡片「引用 N」标签（点击展开）展示；空态按 ragStatus 显示降级文案（**前端不读 `docId`，纯增量不影响展示**）。**WEB 搜索来源并入（2026-09-05 增补）**：深度模式简报页的引用面板另将 `brief.fact_sheet.entries` 中条目派生为引用条目并入展示——**全部类型（KB/WEB/MULTI，2026-09-06 修订）**：KB 条目（置信 0.9/0.6）与本地 `rag_citations` 同款「通用知识」标签展示（修复「深度模式内容引用了知识库、页面却显示未引用」的展示断链，项目 29 实测）；WEB 带域名、MULTI 标多源交叉；上限 24 条。快速模式无 fact_sheet，行为不变（**2026-09-09 注:快速模式已下线,本句仅存量语义**）。版本卡片保持「本版生成时的本地知识库检索」语义，不重复展示 WEB 引用。

**检索门槛**（粗调值，**待按真实 query 分数分布校准**；`REJECT` 须 ≥ `MIN`）：

| `.env` 变量 | 默认 | 代码用途 |
|---|---|---|
| `AI_RAG_MIN_SCORE` | `0.3` | 逐块相似度门槛，低于不注入（沿用 S6 原硬编码值） |
| `AI_RAG_REJECT_SCORE` | `0.5` | 整体置信度门槛：全部命中块的最高相似度低于该值 → `LOW_CONFIDENCE` 全部抛弃 |
| `AI_RAG_KB_TOPK` | `4` | 通用知识库生成检索注入块数上限（与车型域配额独立；§6c） |
| `AI_RAG_KB_ENABLED` | `true` | 通用知识库总开关，false 时统一检索排除 KB 块（§6c） |
| `AI_RAG_ANCHOR_BOOST` | `1.15` | 统一检索锚点车型块分数加权系数（§6c S8） |

**诚实边界**：相似度衡量**相关性**而非事实正确性——知识库本身存错的数据会以高相似度被当作权威注入；防错依赖入库源头（比亚迪同步 + 人工清洗），检索门槛不承诺拦截知识库错误数据。

### 6b-s. 系统检索设置（09-09-brief-gen-redesign，2026-09-09）

**语义**：页面控制生成链路的资料检索来源——内部知识库与外部搜索两个独立开关，运行时读取（`SettingService` 单行表 + 内存缓存，写后刷缓存），**非 .env 部署级配置**。设置变更仅影响之后的生成，已生成产物不追溯。

**存储**（schema.sql 幂等单行表，固定 id=1，首次读取自动初始化默认行）：

| 列 | 类型/默认 | 语义 |
|---|---|---|
| `kb_enabled` | `BOOLEAN NOT NULL DEFAULT FALSE` | 内部知识库（CAR 车型域 + KB 通用域）启用；**默认停用**（知识库数据质量治理中，停用期间优先外部搜索资料） |
| `web_search_enabled` | `BOOLEAN NOT NULL DEFAULT TRUE` | 外部搜索（SEARXNG→Tavily 降级链）启用 |
| `updated_by` / `updated_at` / `deleted` | `BIGINT` / `TIMESTAMP NOT NULL DEFAULT now()` / `BOOLEAN NOT NULL DEFAULT FALSE` | 审计（手工赋值）/逻辑删除惯例 |

**API 契约**（全部 `R<T>`；路径前缀 `/api`）：

| 接口 | 方法 | 角色 | 请求/响应 |
|---|---|---|---|
| `/settings` | GET | ADMIN, EDITOR | `data: {id, kbEnabled, webSearchEnabled, updatedBy, updatedAt, deleted}`（首次访问自动插默认行） |
| `/settings` | PUT | **仅 ADMIN** | `@Valid {kbEnabled?, webSearchEnabled?}`（null 不改）；响应同 GET（写后刷缓存） |

**生效点**（深度链路，快速模式已下线）：

| 开关 | 生效行为 |
|---|---|
| `kbEnabled=false` | `DeepResearchService.applySettingGates` 剔除 KB 工具（子代理不装配本地检索）；产物 `rag_status=DISABLED`；锚点车型仅保留写作偏好语义，不触发本地检索 |
| `webSearchEnabled=false` | 剔除 WEB 工具（SEARXNG/Tavily 不调用） |
| 双关 | 子代理无资料工具，LLM prompt 注入「未检索任何外部资料,不得编造,数据未核实」；生成继续不阻断（沿用不硬阻断决策），factRisks/gaps 标注 |
| 两者全开 | 维持 S9 现状：KB 优先（R2 冲突裁决 KB>WEB），WEB 单源 0.4 进 warnings |

**前端**：`/settings` 路由（TopBar「设置」，EDITOR 及以上可见）；双 `el-switch` + 说明文案 + 双关警示；写入口仅 ADMIN（`user.isAdmin` 隐藏保存按钮，后端 `@PreAuthorize` 兜底）。

**与 `.env` 的关系**：`AI_RAG_KB_ENABLED`（§6c，部署级）仅在本地统一检索通道内继续生效（`kbEnabled=true` 时）；深度链路的工具装配以设置页为准。`SEARCH_WEB_ENABLED`（`sparkora.deep.search-web-enabled`）同理仅作 SEARXNG/Tavily 的部署级可用性控制。

**检索策略升级（S6.2，2026-09-03；修复海狮08 文章价格/续航错误暴露的检索精度缺陷）**：

| 缺陷（S6.1 现状） | S6.2 修复 |
|---|---|
| 「XX参数表及配置表」零信息表头块（仅标题行）得分最高挤占 topK | 切块层：有效参数 <2 的分组不入库（`CarDocService`）；检索层兜底丢弃仅含标题行的参数块 |
| 权益块与主题措辞相似挤占配额 | 分层配额 `applyQuota`：PARAM_GROUP/MODEL_INFO 优先，RIGHTS/FEATURE 合计 ≤ 总配额 1/3 |
| 单查询整句 topic 与参数级子问题不对齐 | 参数级子查询 `deriveSubQueries`：query 含价格/续航/油耗等参数词时逐词派生子查询，主/子查询结果按 chunkText 去重合并 |
| AI 在知识块未覆盖的参数处编造数值 | 覆盖度声明：`RagResult.coveredText` 携带「参数名→值」清单注入 prompt；清单外参数禁止写具体数值，要求定性表述 + factRisks 标注 |

- 修复生效前提：**重新同步车型**（旧表头块仍在库中，检索层已兜底过滤，但建议重同步清理）。
- 生成时后端须运行 S6.2 代码（历史教训：S6.1 合入后进程未重启，生成仍走旧链路）。

**数据清洗链路治理（S6b，2026-09-04；kb-clean-audit 任务，修复清洗可观测与切块质量）**：

| 项 | 契约 |
|---|---|
| 清洗方式三态 | `car_param_clean.clean_method` ∈ `RULE`(规则引擎命中) / `AI`(LLM 兜底) / `FALLBACK`(双失败 STRING 原样,需人工关注)；**不再出现把兜底误标 RULE 的旧行为**,旧数据需重清洗刷新口径 |
| 清洗统计 | `CleanStats`(RULE/AI/FALLBACK 计数)：随 `cleanForModel` 日志汇总、同步任务聚合日志(`fallbackPct`)、`GET /api/car/models/{id}/clean-stats` 按 method/valueType 分组查询(三角色可读) |
| PARAM_GROUP 块首行 | 固定 `车型：<全名>`(消除 EV/DM-i 同系跨版本检索混淆,即 S6.2 P1 遗留项);块行文本 `参数名：清洗值` |
| 清洗值展示 | 优先 `car_param_clean.param_value`,缺失回退 `raw_value`;NUMBER/LIST 类型且值不含单位时拼接单位(如 `2820mm`);清洗与原始值均缺省跳过该行 |
| 向量重建 | `rebuildForModel`:embedding 并发(固定线程池 ≤4)+ 单块失败重试 1 次;完成日志输出「成功 X/失败 Z」,失败块记 sortOrder(消除静默丢块) |
| 批量重建/对账 | `POST /api/car/models/rebuild-all`(ADMIN/EDITOR)逐车型重建汇总;`GET /api/car/models/vector-stats`(三角色)返回 {modelCount, chunkCount, embeddedCount, missingCount, missingTopN}(仅统计 deleted=0;2026-09-04 实测全库 380/380 缺失 0) |
| 入库去重 | `persistVersions`/`persistParams` 同名版本/同名分组去重(官网接口历史上曾按模块重复推送,防再发);重同步车型39 复测 clean 与参数版本值 1:1 精确对齐 |
| AI 兜底空值防线 | `AiParamCleaner` 对 AI 返回 value 空白视为失败返回 null(走 FALLBACK 兜底),「无值清成空串」不再落库 |
| 摊平核查结论 | 6432 清洗行疑云 = 历史上游重复推送 + @TableLogic 逻辑删先清后插堆积(非清洗层摊平);详见 `archive/2026-09/09-04-clean-followup/research/flatten-findings.md` |
| 生效前提 | 切块口径变更**仅对新重建的车型生效**;存量 56 车型需逐个重建向量(体检发现 408/1293 块历史向量缺失,重建一并补齐) |

体检报告（量化）见 `.trellis/tasks/09-04-kb-clean-audit/research/clean-audit-report.md`：规则引擎覆盖 98.8%+（口径可信度受旧误标影响，重清洗后复测）；AI 兜底 9 行中 4 行「无值清成空串」属错误输出（P2 建议：AI 返回空值视为失败不落库）；**31.6% 文档块无向量（历史静默丢失）**；单车型 39 清洗行 6432（占 60%）疑似多版本摊平，待核查。

### 6c. 通用汽车知识库「KB 双源检索」（S7，2026-09-04；S8 统一检索升级 2026-09-04）

**语义**：知识来源从「仅车型域」扩展为**双源**——车型域（BYD 同步，既有）+ 通用域（手工录入知识，新增）。项目**未关联车型时生成（简报/正文）仍必查通用域**，不再零注入；已关联车型时双源独立配额合并。四态状态机与降级语义（§6b）不变。

**数据层（schema.sql S7 区块，幂等）**：

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_kb_doc` | id / title(≤200) / domain(默认「通用」) / content / enabled / created_by / 审计字段 / deleted | 手工知识条目;逻辑删 |
| `sparkora_kb_chunk` | id / doc_id FK / seq / chunk_text / created_at | 检索块;chunk_text 首行固定「知识：<title>（<domain>）」 |
| `sparkora_kb_chunk_embedding` | id / chunk_id FK / embedding vector(1024) / created_at | 向量;**C1 起 HNSW cosine（`idx_kb_chunk_emb_vec_hnsw`，与车型域 `idx_car_doc_emb_vec` 统一；旧 IVFFLAT 索引已幂等 DROP）** |

**服务与切块**：`com.sparkora.kb.service.KbDocService` — create/update/delete/list/get/rebuild；切块：空行分段、单段 ≤500 字符、超长按句读（。；；！？）切分合并、段内换行转空格；重建幂等（先物理清 chunk+embedding 再重嵌）；embedding 单块失败 warn+计数（EmbedStats total/success/failed），块缺失用 rebuild 补齐。

**API（`/api/kb`，@PreAuthorize：读=三角色,写=ADMIN/EDITOR）**：

| Method | Path | 说明 |
|---|---|---|
| GET | `/api/kb/docs` | 列表(含 chunkCount) |
| GET | `/api/kb/docs/{id}` | 详情(含 content) |
| POST | `/api/kb/docs` | 新建(@Valid KbDocSaveDto,自动切块向量化) |
| PUT | `/api/kb/docs/{id}` | 编辑(自动重建;enabled=false 清块) |
| DELETE | `/api/kb/docs/{id}` | 删除(逻辑删文档+物理清块) |
| POST | `/api/kb/docs/{id}/rebuild` | 手动重建,返回 {total,success,failed} |

**检索契约（S8 统一检索，2026-09-04 升级；S7 双源语义由本节取代）**：

| 项 | 行为 |
|---|---|
| 统一检索 | `searchTopKUnified(queryVec, limit)`：车型域与 KB 域 **UNION ALL 同向量空间全库检索**，按余弦分排序；返回行带 source(CAR/KB)/modelId/chunkType/modelName。「项目关联车型」**不再是检索门禁**——未关联车型也全库检索（修复文章18 类误伤：数据在库却因未关联查不到） |
| 锚点加权 | 项目关联车型降为**写作锚点**：CAR 块 modelId∈anchor → score × `AI_RAG_ANCHOR_BOOST`(默认 1.15，上限 1.0 截断)重排；~~前端项目编辑页改「写作锚点车型」文案~~（**2026-09-09:创建页车型选择入口已移除**——创作不与车型绑定,知识库停用期间该字段无生效点;后端关联逻辑与锚点加权保留,存量项目不受影响;新项目无 anchor 即全库无加权,dec-dd4ba6e1c6bfb7e7） |
| 配额 | 核心块(PARAM_GROUP/MODEL_INFO)优先、RIGHTS/FEATURE ≤1/3、KB_CHUNK 独立配额 `AI_RAG_KB_TOPK`；`AI_RAG_KB_ENABLED=false` 时 KB 块在配额层排除（等价 S6 行为，检索仍跑） |
| 来源标注 | 行内前缀「【车型数据：名称】」/「【通用知识：标题】」；首行「知识来源：…」按命中构成生成 |
| 子查询 | S6.2 参数级子查询保留，子查询同走统一检索 |
| 状态判定 | 检索异常（单路统一检索）→ FAILED；rawHit==0 → NO_KNOWLEDGE；maxScore<reject → LOW_CONFIDENCE；其余 OK（S6.1 四态语义不变） |
| 覆盖度声明 | coveredText 仅统计 CAR 域 PARAM_GROUP 块 |

**前端**：`/kb` 知识库页（列表卡片/新建编辑抽屉/删除确认/重建向量含失败提示；移动端单列），TopBar「知识库」入口。

**配置**：`AI_RAG_KB_TOPK`(默认 4) / `AI_RAG_KB_ENABLED`(默认 true)，见 §9 配置表。

### 6d. 车型库数据基座加固（C1，2026-09-11）

| 项 | 契约 |
|---|---|
| `car_model.intro_images` | **语义 = 图库 `image_asset.id` 列表 JSON**（非 URL）；存量旧数据可能为 URL 数组，双读兼容 |
| `introImageUrls` | 非持久化派生字段（`@TableField(exist=false)`）：`list()`/`detail()` 由 `introImages` 实时解析——数字 id → `ImageService.publicUrl`，`http` 开头原样保留，解析失败跳过；前端 `CarLibrary.vue` 缩略图取 `introImageUrls[0]` |
| 删除车型清理 | 逻辑删主表/版本/分组/参数/文档块，并按 `model_id` 物理清理 `sparkora_car_doc_embedding`（兜底历史逻辑删除残留）；**不删全局共享图库资产**（`project_id=null`、`source=byd`、内容哈希去重） |
| 同步触发 | 手动 `POST /api/car/sync/jobs`（`job_type=SELECTED/RETRY`）+ 定时 `@Scheduled`（`job_type=SCHEDULED`，以官网目录全量幂等刷新，**未过期** RUNNING 任务存在则跳过；陈旧 RUNNING（`started_at` 超 60 分钟）先置 FAILED 自愈后继续，见 §15）；默认关闭 |
| 配置 | `CAR_SYNC_ENABLED`（默认 false）/ `CAR_SYNC_CRON`（默认 `0 0 3 * * ?`） |
| KB 索引 | `sparkora_kb_chunk_embedding` 由 IVFFLAT 统一为 HNSW cosine（§6c） |

---

## 7. 已定决策（S0 落地依据）

- **会话方式**：Spring Security + **JWT**（`JWT_SECRET` / `JWT_EXPIRE_MINUTES` 从 `.env` 读）。
- **数据库**：**PostgreSQL**，连接参数从 `.env` 的 `SPARKORA_DB_*` 读取（host/port/name/user/password）；schema 初始化脚本幂等可重复。
- **前端工程位置**：`/dockerData/code/sparkora/frontend/`，单独 Vue3 + Element Plus 工程。
- **模型入口**：**axonhub 统一入口 `https://axo.caiqz.cn`**（OpenAI 兼容），`AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL` 从 `.env` 读；S0 不调 AI，但骨架预留 `AiClient` 配置读取位。
- **wenyan-server**：S4/S5 双通道;发布通道可用性只看 `WENYAN_MCP_SERVER_URL`+`WENYAN_MCP_SERVER_API_KEY`(旧 `WENYAN_MCP_ENABLED`/`WENYAN_MCP_BIN` stdio 模式已于 S5 废弃移除)。
- **审计**：S0 先用日志文件（`logs/`），`sparkora_audit_log` 表后置。

---

## 7b. 配置来源（`.env` → Spring Boot）

S0 骨架用 `spring-dotenv` 或启动时读 `.env`，映射到 `@ConfigurationProperties`：

| `.env` 变量 | 代码用途 | S0 是否启用 |
|---|---|---|
| `SPARKORA_DB_HOST/PORT/NAME/USER/PASSWORD` | 数据源（PostgreSQL） | ✅ 启用 |
| `JWT_SECRET` / `JWT_EXPIRE_MINUTES` | JWT 签发与校验 | ✅ 启用 |
| `SERVER_PORT` | 后端端口（默认 8080） | ✅ 启用 |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` | axonhub 统一入口 | ⏸ S0 仅预留配置类，不调 AI |
| `AI_IMAGE_MODEL` / `AI_IMAGE_MODELS` | 文生图 / **图生图**（axonhub，多模型逗号分隔轮询） | ✅ S3b 启用 |
| `AI_RAG_MIN_SCORE` / `AI_RAG_REJECT_SCORE` | 知识库 RAG 检索门槛(逐块/整体;契约见 §6b) | ✅ S6.1 启用 |
| `AI_RAG_KB_TOPK` / `AI_RAG_KB_ENABLED` | 通用知识库检索配额/总开关(契约见 §6c) | ✅ S7 启用 |
| `AI_RAG_ANCHOR_BOOST` | 统一检索锚点车型加权系数(契约见 §6c) | ✅ S8 启用 |
| `AI_IMAGE_MIN_SCORE` | 图片语义检索相似度门槛(契约见 §10「图片语义检索」;默认 0.3) | ✅ 09-15 img-semantic-search 启用 |
| `IMAGE_STORAGE_DIR` | 数据盘目录（S6 起图片不再落本地；仅 wenyan 渲染临时文件落位） | ✅ S3b 启用 |
| `WECHAT_*` | 公众号草稿发布 | ⏸ **S5 经 wenyan-server 发布(微信凭据配在 server 端,Sparkora 不直连微信)** |
| `WENYAN_MCP_*` | wenyan 预览/发布 | ✅ S5 启用(SERVER_URL/SERVER_API_KEY/PUBLISH_TIMEOUT_MS;发布通道 = 远程 wenyan-server) |
| `SEARXNG_*` / `CRAWL4AI_*` | 搜索/抓取素材 | ✖ 随「校验」步骤取消（2026-08-28 决策），不启用 |

---

## 8. 验收清单（真机逐条打勾）

> 验证日期：2026-08-18。后端 `SERVER_PORT=5661`（见 `.env`），前端 5173，代理 `/api → localhost:5661`。
> 验证方式：curl 直连后端 + 经前端代理 5173 端到端。

- [x] 后端 `mvn -f pom.xml spring-boot:run` 启动，6.8s 出现 `Started SparkoraApplication`；Hikari 连上 PG；schema 初始化幂等（`CREATE TABLE IF NOT EXISTS` + 启动重复无报错）
- [x] 前端 `npm run dev` 启动（Vite 5.4），5173 返回 200、`<div id="app">`、`main.js` 挂载
- [x] admin/admin123 登录成功（返回 JWT + userId=1 + role=ADMIN + `editorOrAbove=true`），进入工作台
- [~] 未登录访问 `/api/**` 返回 401（已修复，见说明①）；前端未登录跳 `/login` 由路由守卫保证
- [x] viewer 角色限制：`@EnableMethodSecurity` + `@PreAuthorize` 矩阵已真机验证（见说明②）—— viewer GET 允许、POST 403；editor 创建允许、删除 403；admin 全放
- [x] 工作台分页列表空态正常（`{rows:[],total:0}`）
- [x] 新建创作任务返回 id；存入后列表出现该行，`status=DRAFT`
- [x] `POST /api/projects/{id}/generate/brief` → 状态 DRAFT→READY，`updatedAt` 推进（S0 占位，S1 起接 AI）
- [x] 进入详情：六步 `el-steps` 步骤条渲染，第一步「生成 Brief」可点触发占位接口
- [x] 登出（`POST /api/auth/logout` → `code:0`）；前端丢弃 token 后需重新登录
- [ ] 审计日志记录新建/状态变更 —— **S0 暂未实现** `sparkora_audit_log`（spec 第 63 行列为验收项但 S0 范围未建表，挪至 S1）
- [~] **移动端适配**：代码层已做（`@media (max-width:768px)` 单列、表格→卡片、表单堆叠、步骤条字号收缩）；真机浏览器宽度回归待用户在浏览器中目视确认

### 说明

① **401 已修复**：`SecurityConfig` 注册 `HttpStatusEntryPoint(UNAUTHORIZED)`，未携带/无效 token 访问受保护接口现在返回 **401**；已认证但角色不足仍返回 403（符合 viewer→403 的预期）。2026-08-18 真机验证通过。

② **viewer/editor 账号已补**：`DataInitializer` 现预置三账号——admin/admin123(ADMIN)、editor/editor123(EDITOR)、viewer/viewer123(VIEWER)。真机角色矩阵验证：viewer GET 200 / POST 403；editor 创建 200 / 删除 403；admin 全放。默认密码仅用于本地/内网验证，正式部署应改密或关闭。

---

## 9. 交付物

- Spring Boot 3 + MyBatis-Plus + Spring Security 后端骨架（`com.sparkora`）。
- Vue3 + Element Plus 流程化工作台前端（`frontend/`），**移动端响应式适配**。
- 业务表 `sparkora_article_project`、`sparkora_user`、`sparkora_role`（最简）。
- 登录 + 工作台列表 + 新建任务表单 + 项目详情（步骤条占位）。

---

## 10. 配图模块（S3b，正式规格）

> 2026-08-28 升格为正式字段级规格。文章配图支持**三种来源**：

| 来源 | 说明 | 接口形态（axonhub / OpenAI 兼容） |
|---|---|---|
| 图库选图 | 用户上传图进图库，从图库选用 | 不调 AI（上传即转存图床） |
| 文生图 | prompt → 生成封面/插图 | `images/generations` |
| **图生图** | 上传参考图 + prompt → 基于参考图生成 | `images/edits`（multipart 传参考图；若 axonhub/当前候选模型不支持则明确报错并提示改用文生图） |

> **2026-09-03 S6 决策**：配图并入预览步骤，不再有独立「配图」步与「完成配图」状态推进。配图入口在预览工具栏「配图」面板，提供**图库插入**（全量图库选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，产物进图库后插入正文）两种来源。车型库图片接入**预留**（暂不开发）。

### 数据模型（`sparkora_image_asset`，S3b 新表；S10 增量见文末）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 主键 |
| project_id | Long | 关联项目（workspace 单租户 MVP，不单设 workspace_id；可空=全局图库） |
| file_name | String(255) | 原始文件名（生成图为 prompt 摘要命名） |
| source | String(20) | `upload` / `ai-text2img` / `ai-img2img` / `byd`（车型介绍图）/ `byd-news`（新闻封面图，09-13 image-tags 新增） |
| prompt_text | String | 生成 prompt（AI 来源时） |
| ref_image_id | Long | **图生图**的参考图 id（自引用 sparkora_image_asset.id，可空） |
| width / height | Integer | 尺寸（px；取不到时为空） |
| storage_key | String(300) | 图床 key（**入库即转存，非空**；URL 由图床域名实时拼） |
| created_by | String(64) | 审计：上传/生成操作人 |
| created_at | Datetime | 创建时间 |

- **S6 图库完全依赖图床，本地不留**：`storage_path` 字段已移除（历史本地图不迁移，作废）；`qiniu_key` 语义通用化为 `storage_key`。图片入库即直接转存图床，`/images/**` 静态映射已删除。
- 非持久化字段 `url`：由 `storage_key` 实时拼图床公网 URL，供前端直接展示/引用（`@TableField(exist=false)`）。

**S10 增量字段（幂等 ALTER；存量行为 NULL，旧代码兼容）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| content_hash | VARCHAR(64) | 内容哈希（sha256 hex）。入库去重：命中已有记录**不重复上传图床**，返回已有记录（`dedupeHit=true`，前端提示「复用」）。仅新增入库必填；存量回填明确不做。索引 `idx_image_asset_hash` |
| gen_model | VARCHAR(100) | 生成留档：实际命中的模型名（AI 来源；上传/BYD 为空） |
| gen_size | VARCHAR(20) | 生成留档：请求尺寸（`auto`/未指定为 NULL）；regenerate 用它复现尺寸 |

- 非持久化字段新增：`thumbUrl`（七牛 imageView2/2/w/360/format/webp 派生；非七牛实现降级为 url）、`dedupeHit`（Boolean，去重命中标记）、`tags`（`List<String>`，09-13 image-tags 起由标签服务回填，按名称排序；无标签为空列表）。
- **去重管线（五来源统一）**：upload / 文生图 / 图生图 / byd（车型介绍图）/ byd-news（新闻封面）均走 `ImageService.persistOrReuse`（算哈希→查命中→复用或上传图床）。BYD 额外收益：车型同步幂等重跑不重复占图床对象。并发同哈希双写容忍（先查后插，竞态窗口最多多传一份对象）。

**09-15 img-classify 增量字段（幂等 ALTER；存量行为 NULL，旧代码兼容）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| source_ref | VARCHAR(200) | **来源引用串**（通用，一个字段承载所有来源）：新闻图 = 官方 `news_id`（如 `/page/byd-cn/news-2026/detail632`），其他来源留空（未来可扩车型 goods_id 等）。索引 `idx_image_asset_source_ref`。**不建外键**（沿用图库表应用层维护惯例） |

- **写入语义**：增量（`NewsService.upsertOne`）随封面入库透传 `sourceRef`；去重命中已有图时**仅当已有 `source_ref` 为空才补写**（同一图片被不同新闻引用保留首次值，不覆盖，见 `ImageService.applyPresetSourceRef`）。该补写是**原子条件更新**（`WHERE id=? AND (source_ref IS NULL OR source_ref='')`，同 database-guidelines「原子抢占」范式）：WHERE 命中 0 行说明并发已写入，此时以库中现有值为准回填实体——只靠 Java 端先读后判存在 check-then-set 竞态（两条新闻并发同步同一张图会互相覆盖）。
- **存量回溯**：`ImageTagBackfillRunner` 启动任务按文件名 `detail<数字>` → `news_id` **精确后缀匹配**（先 LIKE 粗筛候选，再 Java 端精确比对，防 `detail63` 误配 `detail632`）反查新闻回填；只处理 `source='byd-news' AND source_ref IS NULL`，幂等可重跑。
- **追溯读取**：`GET /api/images/{id}/source`（见下方 API 表）。

**新闻-图库封面关联（`sparkora_news`，09-15 img-classify 幂等补列）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| cover_image_id | BIGINT | 封面图对应的图库资产 id（可空，**不建外键**）。列表/详情返回非持久化 `coverImageUrl`（图床公网 URL，由图库实时拼），前端**优先用它展示**，官网 `image_url` 保留为回退（未同步封面的 10 条新闻走回退链路） |

- 回填时机：新闻增量入库（`upsertOne` 拿到 asset id 后）与存量回溯（`ImageTagBackfillRunner`）两处；**仅在新闻侧 `cover_image_id` 为空（或指向已失效图）时才写**，重同步不覆盖已同步的有效值（`NewsService.coverImageValid`）。

### 图片标签（09-13 image-tags，独立标签表）

**数据模型（`sparkora_image_tag`，schema.sql S-tags 段幂等 `CREATE TABLE IF NOT EXISTS`）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| image_id | BIGINT NOT NULL | → `sparkora_image_asset.id`（应用层维护，**不建强外键**） |
| tag_name | VARCHAR(50) NOT NULL | 标签名（trim 后 1~50 字符；超长 `R.fail(400)`） |
| created_by | VARCHAR(64) NOT NULL | 操作人（用户名或 system） |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |

- 标签**按名称使用**（不建标签字典表），同一图片同一标签不重复：`UNIQUE(image_id, tag_name)` 数据库级防重，应用层捕 `DuplicateKeyException` 静默吞（幂等语义）。
- 索引：`idx_image_tag_name (tag_name)`（按标签查图）；image_id 走唯一约束前缀（按图查标签）。
- **无 `deleted` 逻辑删除列**：关系行生命周期 = 图片生命周期，物理删（`ImageService.delete` 先清 tag 行再物理删图行；图库表本身也无逻辑删除）。
- 实体 `ImageTagEntity` / mapper `ImageTagMapper` / 服务 `ImageTagService` 三件套；`ImageAssetEntity` 仅加非持久化 `tags` 字段（主表**不加列**）。

**打标语义**：

| 场景 | 行为 |
|---|---|
| 新图入库（上传/AI 生图/车型图/新闻封面） | `saveTags`：preset.tags 批量写 tag 行 |
| 去重命中（`dedupeHit=true`） | `mergeTags`：已有图标签 ∪ 本次预选，只插差集（**用户预选必须生效**）；不传标签时保留已有标签 |
| 重生成（regenerate） | `copyTags(源图→新图)`：新图继承源图标签（同主题成组）；不读全局预选 |
| 单图编辑 | `replaceTags`：**全量覆盖**（先 delete 后 insert，事务内），空数组=清空 |
| 批量管理 | `batchApply`：`action=add` 逐图 merge；`action=remove` 逐图按名删；逐张幂等 |
| 删图 | `deleteByImageId`：物理删该图全部 tag 行（同 KB embedding 兜底清理先例） |

**BYD 图片自动分类（R2b）**：

| 来源 | 自动标签 | 实现 |
|---|---|---|
| 车型介绍图（source=`byd`） | `车型-<车型名>`（如 `车型-大唐EV`） | `CarModelService.persistIntroImages` preset 传 tags，走统一管线自动落标；**存量追溯**由 `ImageTagBackfillRunner` 启动一次性遍历 `car_model.intro_images` asset id 列表 mergeTags（幂等可重跑，异常不阻断启动；URL 旧格式跳过） |
| 新闻封面图（source=`byd-news`） | `新闻` + **`主题/<主题名>`** + **`年份/<年>`**（09-15 img-classify） | `NewsService.upsertOne` 下载 `imageUrl` 字节走 `saveExternalImage` 入库（相对 URL 拼 `https://www.byd.com`，带 `sourceRef=news_id`）；标签由 `NewsImageClassifier.toTagsFrom(title, publishDate)` 派生（零 AI）。单图下载失败仅告警**不阻断新闻入库**；`sparkora_news.image_url` 保留原 URL 留痕 |

- 新闻正文内嵌图**不入库**（图片型新闻多为装饰长图，量级/噪音风险，范围外）。
- 来源白名单 `SOURCES` = `upload` / `ai-text2img` / `ai-img2img` / `byd` / **`byd-news`**（非法值 400）。

### 新闻图片主题分类（09-15 img-classify，子A）

**分类器**：`com.sparkora.news.classify.NewsImageClassifier`（纯静态、无 Spring 依赖、零 AI 调用、可单测）。词表是**受控产品定义**，以代码常量 `LinkedHashMap<String, Pattern>` 固化（保序 = 展示序；改词表 = 改代码发版，不入 DB 字典表）。输入新闻标题 → 命中主题集合（保序 0~n，**允许重叠**，如「海外销售再创新高」同时命中「销量」+「出海」）；无命中不打主题标签（不强制归「其他」，避免噪音标签）。

| 顺序 | 主题 | 关键词（正则） |
|---|---|---|
| 1 | 销量 | `销售` |
| 2 | 出海 | `海外\|出海\|出口\|全球化\|国际化\|欧洲\|拉美\|东南亚\|巴西\|泰国\|印尼\|印度\|日本\|澳洲\|澳大\|乌兹\|匈牙利\|墨西哥\|文莱\|哥伦比亚\|香港\|德国\|慕尼黑\|东京\|曼谷\|首尔\|韩国\|英国\|智利\|罗马尼亚\|瑞士\|尼日利亚\|柬埔寨\|贝宁\|加蓬` |
| 3 | 合作签约 | `合作\|签约\|携手\|战略` |
| 4 | 技术发布 | `发布\|技术\|刀片\|云辇\|智驾\|平台\|闪充\|芯片\|系统` |
| 5 | 里程碑 | `下线\|里程碑\|万辆\|纪录` |
| 6 | 荣誉 | `荣\|获\|奖\|榜\|500强\|冠军` |
| 7 | 财报ESG | `财报\|业绩\|ESG` |
| 8 | 车展上市 | `上市\|首发\|车展\|亮相` |
| 9 | 社会责任 | `捐赠\|慈善\|公益\|驰援\|救灾\|基金` |

> 词表以**真实 167 条新闻标题回归**校准（`NewsImageClassifierTest` + 任务 implement.md「实测词表调整」）：实测命中 销量 45 / 出海 65 / 合作签约 18 / 技术发布 22 / 里程碑 25 / 荣誉 22 / 财报ESG 6 / 车展上市 16 / 社会责任 3，无命中 21。design.md 草案的出海词表未覆盖「进入/登陆 <国> 市场」类标题，回归时补入国别词（智利/罗马尼亚/瑞士/尼日利亚/柬埔寨/贝宁/加蓬/首尔/韩国/英国）并将「国际化」并入；宽泛词「荣/获/榜」「发布」回归确认无误命中，保留。

**标签命名空间**（与用户自由标签隔离，筛选下拉可按前缀分组）：

| 标签 | 命名 | 说明 |
|---|---|---|
| 主题标签 | `主题/<主题名>`（如 `主题/销量`） | 受控词表命中，0~n 个 |
| 年份标签 | `年份/<年>`（如 `年份/2026`） | 由来源新闻 `publish_date` 派生；取不到日期则不打 |

- 命名空间前缀的目的：与用户自由标签（用户自建的「销量」）隔离；`GET /api/images/tags` 返回的**名称即含前缀**（响应结构不变，仍是 `[{name,count}]`），前端按 `/` 前缀 `el-option-group` 分组展示（`主题` / `年份` / `其他`）。
- 人工修正：复用既有单图编辑（`PUT /{id}/tags` 全量覆盖）与批量打标（`POST /tags/batch`），无需新 UI。
- **存量回溯**：`ImageTagBackfillRunner` 新闻分支只处理 `source='byd-news' AND source_ref IS NULL` 的图（分批 200，避免全量内存），解析文件名 → 反查新闻 → `mergeTags`（只插差集，幂等）+ 回填 `source_ref`/`cover_image_id`；异常仅 warn 不阻断启动，日志汇总「处理 X / 跳过 Y / 失败 Z」。2026-09-16 实测：157 张全部处理（跳过 0 / 失败 0），重跑零新增。

### 图片语义检索（09-15 img-semantic-search，子B）

**语义**：让图片可被**自然语言检索**（「销量海报」「出海签约的照片」），为子C（文章自动配图）与子D（问答语义配图）提供检索能力。图片本身没有可嵌入文本，用**描述性文本代理**（来源新闻标题 / 标签 / AI prompt / 文件名）向量化。

**数据模型（`sparkora_image_embedding`，schema.sql 09-15 img-semantic-search 段幂等 `CREATE TABLE IF NOT EXISTS`）**：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| image_id | BIGINT NOT NULL | → `sparkora_image_asset.id`（应用层维护，**不建强外键**；与 `sparkora_image_tag` 同惯例） |
| embedding | VECTOR(1024) NOT NULL | **与 car/kb/news 三域同模型（Qwen3-Embedding-8B）同维度（1024）同向量空间**——硬约束，否则跨域检索无意义，故**不存模型名/维度列** |
| source_text | TEXT NOT NULL | 嵌入原文（调试 + 重建可追溯） |
| created_at | TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP | 创建时间 |

- **一图一向量**：`CREATE UNIQUE INDEX IF NOT EXISTS uk_image_emb_image ON sparkora_image_embedding(image_id)` —— 唯一约束即幂等保证（重建先物理删后插，重复插入不可能；并发重复嵌入第二插入报唯一冲突由 `embedQuietly` 吞掉并 warn）。
- 向量索引 `idx_image_emb_vec_hnsw`：`USING hnsw (embedding vector_cosine_ops)`（与三域统一 HNSW cosine）。
- **不加 `deleted` 列**（物理表，同三域 embedding 表）；**不建 FK**：删图时应用层同事务物理清向量（`ImageService.delete` → `embeddingService.deleteByImageId`），防残留向量命中已删图。
- 实体：本表**无 entity**（VECTOR 类型 MyBatis-Plus `BaseMapper` 无法处理），用注解 SQL mapper `ImageEmbeddingMapper`（`insert`/`deleteByImageId`/`findImageIdsWithoutEmbedding`/`searchTopK`，参照 `CarDocEmbeddingMapper` 先例）。

**嵌入文本构造（`com.sparkora.image.embed.ImageEmbeddingTextBuilder`，纯静态可单测）**：

| source | 文本构成 |
|---|---|
| `byd-news` | 来源**新闻标题**（优先，由 `source_ref` 反查 `sparkora_news.news_id`）+ 标签（含 `主题/*`、`年份/*`）；查不到标题退化为只用标签 |
| `ai-text2img` / `ai-img2img` | `prompt_text` + 标签 |
| `upload` | 文件名（去扩展名）+ 标签 |
| `byd`（车型图） | 文件名（去扩展名）+ 标签（含 `车型-*`） |

- 各段**空格连接、去空段**；整体 trim 后为空 → 兜底 `(图片 <id>)`，**仍写向量**（避免图库里出现永远搜不到的缺向量图，低质命中由检索门槛过滤）。
- 标签**原样拼**（保留 `主题/` 前缀）：「销量」等关键词本身就是检索信号，剥前缀反而丢信息。
- 文本长度上限 **2000 字符**截断（防超长输入打爆 embedding；标题+标签实际远小于此）。
- 嵌入文本构造在 `ImageEmbeddingService` 内按需拼（新闻标题反查），**重建路径自给自足**——无需调用方补上下文。

**向量化时机**：

| 时机 | 行为 |
|---|---|
| 增量（入库） | `ImageService.persistOrReuse` 在**新图 insert 成功后**与**去重命中分支**均调 `embeddingService.embedQuietly(id)`（best-effort：捕获全部异常仅 warn，**绝不影响图片入库**——图片可用性优先于可检索性；钩子在入库成功之后，不掩盖入库本身异常） |
| 重生成继承标签后 | `ImageService.regenerate` 复制源图标签后重嵌（嵌入文本与最终标签保持一致） |
| 存量补齐 | `ImageEmbeddingBackfillRunner`（`ApplicationRunner`，`@Order(20)`）启动调 `rebuildMissing()`——只处理 `LEFT JOIN` 差集为空向量的图；异常仅 warn **不阻断启动**；日志 `图片向量补齐完成:total=X success=Y failed=Z(耗时Nms)`。2026-09-16 首启实测 166/166、0 失败；重跑「无缺失,跳过(total=0)」。**独立守护线程执行**（实测 166 图串行 embedding 约 173s，不占启动主线程；应用就绪不被拖慢） |
| 全量重建 | `POST /api/images/embeddings/rebuild`（ADMIN/EDITOR）：遍历全部图片逐图重新嵌入（**先物理清旧向量再插**，幂等），单图失败跳过并计数 |

**启动补齐顺序（09-15 修订，`@Order` 硬约束）**：`ImageTagBackfillRunner`（`@Order(10)`，补 `主题/*`/`年份/*`/`车型-*` 标签与 `source_ref`）必须**先于** `ImageEmbeddingBackfillRunner`（`@Order(20)`）——嵌入文本依赖标签信号，先嵌入后补标会让存量图拿到「无标签」低质向量，且因「已有向量」`rebuildMissing()` 不再修（静默、需人工调全量重建）。两个 runner 都显式标注 `@Order` 固定该契约。

**事务隔离（09-15 修订，关键契约）**：向量写入（`ImageEmbeddingService.embedOne` → `persistVector`）经自注入代理走 `@Transactional(REQUIRES_NEW)`，**绝不加入调用方的环境事务**：
- 入库链路（新闻同步 `NewsService.upsertOne`、车型同步 `persistModel`）自身是事务性的；若向量 SQL 在其中失败（维度不符 / 唯一索引并发冲突），PostgreSQL 会把**整个调用方事务**置为 aborted——此后调用方任何 SQL 都抛 `current transaction is aborted`，Java 侧 `catch` 无法挽回，「嵌入失败不阻断图片入库」契约即被打破（图片 INSERT 也会随事务回滚）。独立事务后向量失败只回滚自身，调用方照常提交。
- 独立事务同时让「先删后插」**原子化**：重嵌失败回滚保留旧向量，不留「删了没插上」的空洞。
- embedding 网络调用放在事务之外（不长时间占连接）。


**检索实现（`ImageEmbeddingService.searchImages`）**：`query` 空校验 → **标签 AND 预过滤**（复用图库列表的 `resolveTagIds` 交集语义；交集为空**直接返回空列表且不调用 embedding**，省一次调用）→ `EmbeddingClient.embed(query)` → `ImageEmbeddingMapper.searchTopK`（HNSW cosine 排序，**门槛写在 SQL 的 WHERE**，不传输注定被丢弃的行；白名单候选集 ≤500 截断保底，与 `GET /api/images` 一致）→ 批查主表回填 `fileName/source/sourceRef` + 派生 `url/thumbUrl`（复用 `ImageService.fillDerived` 静态实现，同一派生规则只此一处）+ `ImageTagService.fillTags` 回填 `tags`。

**接口契约（全部 `R<T>`，HTTP 200 业务失败；§1 已登记）**：

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/images/search` | 三角色 | `{query, topK?, minScore?, tags?[]}`（`@Valid ImageEmbedDTO`；`tags` AND 语义，与 `GET /api/images` 一致） | `data = [{imageId, score, sourceText, fileName, source, sourceRef, url, thumbUrl, tags[]}]`，按 `score` 降序 |
| POST | `/api/images/embeddings/rebuild` | ADMIN/EDITOR | 无 body | `data = {total, success, failed}`（幂等：重复调用结果稳定；失败图不阻断整体，原因见后端日志） |

**错误矩阵**：

| 条件 | 行为 |
|---|---|
| `query` 空/空白/缺失 | 400 `R.fail(400,"检索内容不能为空")`（DTO `@NotBlank` 中文消息） |
| `topK` > 50 / < 1 | 收敛为 50 / 默认 10（**不报错**） |
| `minScore` 为 null | 用 `AI_IMAGE_MIN_SCORE`（默认 0.3） |
| `tags` 无交集成空集 | 200 `data: []`（**不调用 embedding**） |
| `AI_EMBEDDING_MODEL` 未配置 | 500（消息含「未配置」，来自 `EmbeddingClient.embedList`） |
| embedding 调用失败 | 500 `R.fail(500,"图片语义检索失败: …")` |
| VIEWER 调 search | 200（三角色可读） |
| VIEWER 调 rebuild | 403（`@PreAuthorize`） |

**配置**：`AI_IMAGE_MIN_SCORE`（默认 0.3，对齐 `AI_RAG_MIN_SCORE` 口径）→ `sparkora.ai.image-min-score`。

**已知限制**：标签变更**不触发实时重嵌**——`source_text` 会与当前标签漂移（检索仍能命中旧文本）；用重建接口修正即可（实时重嵌留给后续任务）。

### 版本-图片关联（挂版本，不挂项目）

`sparkora_article_version` 增列（幂等 ALTER）：

| 字段 | 类型 | 说明 |
|---|---|---|
| cover_image_id | BIGINT | 该版本封面（sparkora_image_asset.id，可空；每版本一张） |
| body_image_ids | String(1000) | 正文插图 id 列表（逗号分隔，有序） |

> 理由：多版本各有排版，预览/发布按「当前版本」取图；项目级关联无法表达版本间差异。

**配图建议「忽略」记录（09-15 article-auto-illustrate 子C 新表，幂等建表）**：

`sparkora_illustration_dismiss`（**只有用户的「忽略」决策落库**；建议候选本身不落库）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGSERIAL | 主键 |
| project_id | BIGINT | → `sparkora_article_project.id`（应用层维护，不建强 FK） |
| version_id | BIGINT | → `sparkora_article_version.id`（忽略记录不跨版本） |
| anchor_key | VARCHAR(200) | 锚点指纹（`headingPath` + 归一化文本前 80 字符的 sha256 前 12 位 hex） |
| created_by | VARCHAR(64) | 操作人（用户名或 system） |
| created_at | TIMESTAMP | 默认 `CURRENT_TIMESTAMP` |

- `UNIQUE (version_id, anchor_key)` 数据库级防重（重复忽略幂等，不报错）；索引 `idx_illustration_dismiss_version`。
- 无 `deleted` 逻辑删除列：关系行生命周期 = 版本生命周期，物理删（同 `sparkora_image_tag` 惯例）；不建强外键（沿用图库表应用层维护惯例）。
- 语义见下文「配图建议」。

### 配图 API（全部 `R<T>` 包装；HTTP 200；S10 起检索/生成契约升级）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images` | 三角色 | `?projectId=&source=&keyword=&tag=&page=1&size=24` 组合查询（source 白名单 upload/ai-text2img/ai-img2img/byd/byd-news，非法值 400；keyword 命中 file_name/prompt_text，ILIKE；**09-15 起 `tag` 支持多值**——重复参数或单值内逗号分隔，语义为 **AND**（图片须同时具备所有指定标签），逐标签查 `idx_image_tag_name` 取 id 集求交集，交集为空直接返回空页；其余筛选照常组合；单值行为与旧版单标签等价） | `PageResult`：`{rows[], total, page, size}`；rows 内每条含 url + thumbUrl + **tags[]**（按名称排序）+ **sourceRef**。**S10 起不再返回全量列表** |
| GET | `/api/images/{id}/source` | 三角色 | —（09-15 img-classify 新增） | `data = {sourceRef, news, imageUrl}`：`news` 为 `{id, newsId, title, publishDate, url}`（source_ref 为官方 news_id 且能反查到新闻时）；**非新闻图（upload / AI 生成图 / 车型图）或查无新闻 → `news: null`**（显式输出，HTTP 200 不报错）；图片不存在 `R.fail(400)` |
| GET | `/api/images/tags` | 三角色 | — | `data` = `[{name, count}]`（全库标签 + 引用数量，count 降序「常用优先」；预选控件与筛选联想同源复用；**09-15 起名称含 `主题/`、`年份/` 前缀**，响应结构不变） |
| POST | `/api/images/search` | 三角色 | `{query, topK?, minScore?, tags?[]}`（09-15 img-semantic-search 新增；`tags` AND 语义同 `GET /api/images`；`topK` 默认 10 上限 50，超限收敛不报错；`minScore` null 时用 `AI_IMAGE_MIN_SCORE` 默认 0.3） | `data = [{imageId, score, sourceText, fileName, source, sourceRef, url, thumbUrl, tags[]}]`（`score` 余弦相似度降序）。`query` 空 → `R.fail(400,"检索内容不能为空")`；`tags` 交集空 → `data:[]`（不调 embedding）；模型未配置/调用失败 → `R.fail(500,…)`。契约详解见下文「图片语义检索」 |
| POST | `/api/images/embeddings/rebuild` | ADMIN/EDITOR | 无 body（09-15 img-semantic-search 新增） | `data = {total, success, failed}`（全量重建图片向量：先物理清旧向量再插，**幂等**；单图失败不阻断整体、原因见日志）。VIEWER 调用 403 |
| POST | `/api/images/upload` | ADMIN/EDITOR | multipart `file` + `projectId?`（可空=全局图库）+ `tags?`（同名多值或单值内逗号分隔均可） | `{image}`（含 `dedupeHit` 与 `tags`）；类型限 png/jpg/webp，≤10MB（`IMAGE_MAX_UPLOAD_MB`），超限 `R.fail(400)` |
| DELETE | `/api/images/{id}` | ADMIN/EDITOR | — | `{ok:true}`；被封面/插图引用时 `R.fail(400, 提示引用方)`；删记录 + 图床对象 + **标签行物理清** + **向量行物理清**（09-15 img-semantic-search：防残留向量命中已删图） |
| POST | `/api/images/generate-text` | ADMIN/EDITOR | `{projectId?, prompt, size?, n?, tags?[]}`（`@Valid` DTO；n 1~4 默认 1） | **S10 起响应为数组** `{images[]}`：n 张候选逐张入库（后端循环 n 次单张调用，单张失败跳过，全部失败 `R.fail(500)` 含候选模型错误明细）；每张含 genModel/genSize/dedupeHit/tags |
| POST | `/api/images/generate-from-image` | ADMIN/EDITOR | `{projectId?, refImageId, prompt, size?, n?, tags?[]}`（`@Valid` DTO） | **S10 起响应为数组** `{images[]}`（同上）；provider 不支持 edits 时 `R.fail(500, 明确提示)` |
| POST | `/api/images/{id}/regenerate` | ADMIN/EDITOR | —（S10 新增） | `{images[]}`（1 张）：用源图 prompt/gen_size 重新生成**新图**（不覆盖源图）。源图须 source∈{ai-text2img,ai-img2img} 且 prompt 非空，img2img 复用源图 ref_image_id（参考图已删则 400）；**09-13 起新图继承源图标签** |
| PUT | `/api/images/{id}/tags` | ADMIN/EDITOR | `{tags:[...]}`（**全量覆盖**语义，空数组=清空；单项 1~50 字符，超长 400） | `{ok:true, tags[]}`；图片不存在 `R.fail(400)`（防写孤儿标签行） |
| POST | `/api/images/tags/batch` | ADMIN/EDITOR | `{ids:[...], tags:[...], action:"add"\|"remove"}`（逐张执行，全部幂等） | `{ok:true}`；ids 空 `R.fail(400)`；tags 空 `R.fail(400)`；action 非 add/remove `R.fail(400)` |
| GET | `/api/projects/{id}/images` | 三角色 | — | `{images[], coverImageId, bodyImageIds[], coverImage?, bodyImages[]}`。**S10 语义改写**：`images` 从全量图库收缩为**当前版本引用的图**（封面+插图）；新增服务端解析的 `coverImage`（对象含 url）/`bodyImages`（按 bodyImageIds 顺序）。全量图库浏览改走 `GET /api/images` 分页接口 |
| POST | `/api/projects/{id}/images/{imageId}/cover` | ADMIN/EDITOR | — | `{ok:true}`（version.cover_image_id）；重复选同一张幂等 |
| POST | `/api/projects/{id}/images/{imageId}/body` | ADMIN/EDITOR | `?action=add/remove` | `{ok:true}`（增删 version.body_image_ids）；重复添加幂等 |
| POST | `/api/projects/{id}/illustration-suggestions` | 三角色 | `{tags?[], minScore?}`（09-15 article-auto-illustrate 子C 新增）；`tags` 为**标签 AND 预过滤**（同 `GET /api/images`）；`minScore` null → `AI_IMAGE_MIN_SCORE`（默认 0.3），须在 [0,1] 否则 400 | `data = [{anchorKey, anchorIndex, headingPath, anchorText, candidates[]}]`，`candidates` 为 `ImageSearchHit`（同 `/api/images/search`，按 score 降序）。**零副作用**：只读正文 + 图库，**不修改 `content_md` / `body_image_ids`**。无候选的锚点不出现在结果中（不报错）；单锚点检索失败仅跳过该锚点（其余照常返回）。无当前版本 → `R.fail(400,"尚未生成正文版本，无法生成配图建议")`；正文空 → `R.fail(400,"正文为空，无法生成配图建议")`；项目不存在 → `R.fail(400,"项目不存在")`。契约详解见下文「配图建议」 |
| POST | `/api/projects/{id}/illustration-suggestions/dismiss` | ADMIN/EDITOR | `{anchorKey}`（09-15 article-auto-illustrate 子C 新增） | `{ok:true}`；写 `sparkora_illustration_dismiss`（`UNIQUE(version_id, anchor_key)`），**幂等**（重复忽略不报错、不重复插入）。`anchorKey` 空/超长(>200) → `R.fail(400)`；无当前版本 → `R.fail(400)`。VIEWER 调用 403 |

- 图片访问：**图床公网 URL**（`url` 字段，由 `storage_key` 实时拼）。`/images/**` 静态映射已删除（S6 本地不留）。
- **缩略图交付（S10）**：列表/网格用 `thumbUrl`（七牛 imageView2/2/w/360/format/webp，交付层转换零转码成本）；大图预览、正文插入、wenyan 拉图、公众号发布均用原图 `url`。非七牛图床实现降级 thumbUrl=url（`ObjectProvider` 可选注入，`ImageStorage` 接口不掺七牛特性）。
- 文生图/图生图返回的 axonhub URL **必须转存图床**（临时 URL 会过期），转存失败则该次生成报错（不留死链）。
- 请求体数字字段（projectId/refImageId）统一健壮解析：兼容数字与字符串形式（前端路由参数为字符串）。
- **S6 起 `complete-images` 接口已删除**（配图并入预览，不再有「完成配图」状态推进）。

### 配图建议（2026-09-16，09-15 article-auto-illustrate 子C 新增）

把图库从「手动选图」升级为「**系统建议、用户定夺**」：正文生成后按段落语义检索图库，产出配图**建议**。

> **硬约束（不可违背）**：系统**只产出建议，绝不自动写入**。配图进入正文的唯一路径是用户在预览页显式操作（单张「插入到此段」/ 整组「全部采用」）。**不存在任何自动插入开关**（无 `AUTO_ILLUSTRATE_ENABLED` 之类配置），从设计上排除无人值守自动配图。生成建议本身**零副作用**：不写 `content_md`、不写 `body_image_ids`。

**锚点切分（`AnchorExtractor`，纯静态可单测）**

- 按 ATX 标题（`##`/`###+`）分段：每个标题到下一个标题之间为一个锚点；标题前的前言也算一个锚点（`headingPath` 为空串）；`#` H1 视为文章标题（不产生锚点、不计入正文）。`###` 挂到最近的 `##` 下（`headingPath` 形如「续航实测 > 高速工况」）。
- 无标题时（罕见）退化为按空行切分段落，每段一个锚点。
- **跳过**：纯列表段落（非空行全部是 `-`/`*`/`+`/`1.` 列表项）、引用块行（`>`）、代码块（``` 围栏内）、图片/链接-only 段落（避免给配图建议区自己推荐）、去空白后 **< 30 字**的过短段落。
- `text` 剔除 markdown 标记（标题符号/加粗/行内代码；链接保留文字），单空格连接。
- **上限** `AI_ILLUSTRATION_MAX_ANCHORS`（默认 5），保序取前 N 个（优先靠前段落，避免配图过密）。
- **锚点指纹 `anchor_key`** = `headingPath` + 归一化（剥标记 + 去全部空白）文本前 80 字符的 **sha256 前 12 位 hex**。用指纹而非序号：正文编辑后序号会漂移，忽略记录会错位到别的段落；指纹在正文未编辑时稳定，纯格式调整（加粗/换行）不改变指纹。

**建议生成（`IllustrationSuggestionService.suggest`）**

1. 取项目当前版本（`current_version_id`）→ 无版本/正文空 → 400（见接口表）。
2. `AnchorExtractor.extract(contentMd, maxAnchors)`。
3. 过滤**已忽略**锚点（按 `version_id` 查 `sparkora_illustration_dismiss` 的 `anchor_key` 集合）。
4. 逐锚点调 `ImageEmbeddingService.searchImages(anchorText, topN=AI_ILLUSTRATION_TOP_N, minScore, tags)`（子B 图片语义检索；串行 ≤5 次，无并发复杂度）。**单锚点失败仅 warn 跳过**，其余照常返回（图库/模型偶发失败不应让整页建议不可用）；门槛以下无候选 → 该锚点不出现。
5. 组装 `{anchorKey, anchorIndex, headingPath, anchorText, candidates[]}`。

- **建议候选不落库**：建议是「当前正文 + 当前图库」的**派生视图**，按需重算且结果稳定（检索确定性 + 无随机）；落库只会引入「建议陈旧」问题。只有用户的「忽略」决策需要持久化。
- **可重算/幂等**：同一版本同一正文 + 同一图库 → 相同结果；正文变更后结果随锚点变化（符合预期）。

**「采用」的写入（用户批准后，唯一写入路径）**

采用必须**两处都写**（2026-09-16 勘察修正）：

1. **编辑器插入 markdown `![](图床原图URL)` 到锚点位置**（`MarkdownEditor.insertMdAtAnchor(headingPath, text)`：按标题文本**首次出现**定位插到该标题行之后；找不到标题则**退回光标处**，保证不丢内容）——保证**真正渲染**；
2. **调既有 `POST /api/projects/{id}/images/{imageId}/body?action=add`** 登记 `body_image_ids`——保证**发布页「插图 N 张」计数正确 + 图片受删图引用保护**。

二者均幂等（`addBodyImage` 幂等；重复插入 markdown 用户可见可自行编辑）。不新增关联模型。

**「忽略」的语义**

- 「忽略此段」→ `POST /{id}/illustration-suggestions/dismiss` body `{anchorKey}` 写 dismiss 表；后续生成建议该锚点被跳过（不再反复打扰）。
- **不提供「取消忽略」的 UI**（非目标）；如需恢复，删表记录即可。
- 忽略记录与版本耦合（`version_id + anchor_key`），版本切换后不跨版本（符合语义：不同版本正文不同）。

**可关闭（R6）**

- `AI_ILLUSTRATION_SUGGEST_ENABLED`（默认 `true`）→ `sparkora.ai.illustration-suggest-enabled`：关闭后 `suggest` 直接 `R.fail(400,"配图建议功能已关闭")`，**不产生建议、不调 embedding**。
- **关闭的是「建议的生成」，与 R3「禁止自动写入」是两件事**：本开关关闭后系统仍然不会自动插入任何配图（系统本就无自动写入能力）。**不存在**任何自动插入开关。

**与「AI 不写图」约束的边界（R5）**

- **保留** `VersionService` 的仿写 prompt 禁图片约束与 `stripImages` 二次清洗——AI **仍不生成图片占位**（避免 AI 编造必 404 的图 URL、且无法保证与图库一致）。
- 配图由「系统建议 → 用户批准」在生成后补入，与「AI 不写图」不冲突；本项目**不存在**「AI 写占位标记 → 系统替换」方案。

**已知债务（本任务不修复，记录在案）**

- `body_image_ids` **不参与渲染**：`PreviewService.buildMarkdown()` 的 `bodyImageUrls` 参数完全未被使用，正文插图落点只由 `contentMd` 中的 `![](url)` 决定。
- **手动插图（预览页图库/AI 生图面板）只写 markdown、不登记 `body_image_ids`**（前端 `insertBodyImage` 仅调 `editorRef.insertMd()`）；仅「智能建议采用」两处都写。历史 34 个版本中 4 个 `body_image_ids` 非空且正文 `![` 出现 0 次，两者本就脱节。
- 修复方向是「`body_image_ids` 改为基于正文解析」，波及 `delete` 引用保护、`projectImages`、发布页计数，超出本任务范围（用户选择「markdown + 登记」双写，非大规模修复）。
- 另注：`ImageService.modifyBodyImage` 清空 `body_image_ids` 时用 `updateById`（MyBatis-Plus `NOT_NULL` 策略）会把 `null` 跳过，导致**移除最后一张插图后字段不清空**（`add` 正常）。既有缺陷，与本任务无关。


### 页面职责（2026-08-30 调整；2026-09-03 S6 配图并入预览；2026-09-06 S10 检索/生成升级；2026-09-13 image-tags 标签能力）

- **图库独立页 `/images`**（`ImageLibrary.vue`，TopBar 入口）：上传、浏览、删除（ADMIN/EDITOR）。**S10 起**：筛选（来源下拉/关键字 300ms 防抖/项目）全部走服务端分页接口（size=24，el-pagination 翻页）；网格缩略图走 thumbUrl（imageView2/webp），点开大图预览用原图；上传内容哈希命中时提示「复用」；AI 来源图卡提供**一键重生成**；**AI 生图抽屉**（文生图/图生图，EDITOR 及以上；图生图从当前列表选参考图；n(1/2/4) 张候选生成，projectId 传空=全局图库，产物即进图库）。**UI 重设计（S10+）**：卡片瘦身——默认仅缩略图+来源小标，元数据/操作入 hover 浮层（移动端常显文件名行+「···」更多操作）；工具条两段式（主操作|浏览控制）；大图预览支持当前页连续浏览；筛选状态 chip 条（单独清除/一键全清）；批量选择模式（多选→单次确认删除，被引用图后端拒绝逐张提示）；舒适/紧凑密度切换（localStorage 记忆）。素材管理归图库，不在文章流程内。
  - **标签能力（09-13 image-tags）**：工具条「上传标签」预选控件（multiple allow-create，上传与 AI 生图共读，不持久化）；工具条标签筛选下拉（数据源 `GET /api/images/tags`，与 chip 条联动，可与其他筛选组合）；卡片 hover 层/移动端常显区展示标签，**点标签直接触发筛选**；卡片 hover 操作区/移动端 ··· 菜单「编辑标签」→ 对话框全量覆盖（`PUT /{id}/tags`）；批量选择态「打标签」→ 对话框（标签多选 + add/remove 单选 → `POST /tags/batch`）。**R5 交互修复**：AI 抽屉文生图/图生图 prompt 拆为独立 ref（切换 tab 不再互相污染）；参考图选择弹窗独立数据源 + 页内搜索（300ms 防抖）+ 分页（不再只看主列表第一页）；来源标签补「比亚迪新闻」（`byd-news`，红色点）。
  - **主题分类筛选与来源展示（09-15 img-classify）**：标签筛选改 **multiple**（`tagFilter` 由字符串改数组，多标签 **AND**），chip 条**逐个展示可单独清除**（点已选标签再点即取消）；下拉按 `/` 前缀用 `el-option-group` **分组展示**（`主题` / `年份` / `其他`）；卡片 hover 层（移动端常显行）显示**来源行**「来源：<新闻标题> · <日期>」，点击跳新闻原文（走 `GET /api/images/{id}/source`，页内批查懒加载，非新闻图不显示）；支持外部入口 `/images?tag=主题/销量`（预置筛选，供新闻卡片点主题标签跳转）。**路由与筛选双向同步**：挂载时按 `route.query.tag` 预置筛选；chip 单独清除 / 全清 / 点卡片标签后 `router.replace` 把 URL 同步为当前选中（`syncRouteTag`）——否则清掉 chip 后 URL 仍留旧 tag，再次从新闻页点同一主题时 query 未变、vue-router 判定重复导航、watch 不触发，出现「点了没反应」。
  - **语义检索能力（09-15 img-semantic-search，后端就绪）**：图库图片已完成向量化（`sparkora_image_embedding`，与 car/kb/news 三域同向量空间），可被 `POST /api/images/search` 用自然语言检索（如「销量海报」）；支持叠加标签 AND 预过滤在「`主题/销量` + `年份/2026`」范围内语义搜。**本任务纯后端**（前端检索入口与自动配图 UI 由子C/子D 承载）。
- **新闻知识页封面与主题标签（09-15 img-classify）**：`NewsKnowledgePanel.vue` 封面 URL 取 `coverImageUrl || resolveUrl(imageUrl)`（图库图优先，官网原始 URL 回退，未同步封面不报错）；卡片/详情展示**主题标签**（`news.themes`，后端用同一分类器按标题重算，不查图库避免 N+1），**点标签跳图库并按 `主题/<名>` 筛选**。
- **预览步配图面板（项目向导 Step3 并入 Step4）**：工具栏「配图」面板提供**图库插入**（**S10 起走分页接口 + 来源/关键字筛选 + 触底加载**，选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，**S10 起可一次生成 n(1/2/4) 张候选，逐张插入/设封面/重生成**；产物进图库后展示候选列表）两种来源。图不够时引导去图库页。车型库图片接入**预留**（暂不开发）。
- **预览页「智能建议」tab（09-15 article-auto-illustrate 子C）**：配图抽屉第 3 个 tab（`imgTab='suggest'`）。顶部：相似度门槛（默认 0.3）+ 标签预过滤多选（AND，数据源 `GET /api/images/tags`）+「生成建议/重新生成」按钮 + 提示「系统只给建议，点采用才写入正文」。按锚点分组卡片：锚点标题（`headingPath` 或「开头段落」）+ 锚点文本摘要 + 候选网格（缩略图/相关度百分比/标签）。每张候选「插入到此段」；每组「全部采用」/「忽略此段」。**空态三态**：未生成（引导点生成）/ 生成后无候选（提示调低门槛、换标签或先去图库补图）/ 全部被忽略。**建议不自动触发**——须用户点「生成建议」（避免打开抽屉即产生 embedding 调用）。移动端单列、触控目标 ≥44px。

---

## 11. 排版预览模块（S4，正式规格）

> 2026-08-30 定稿，方案 A：预览与发布同核算子和图片通道，preview HTML = 发布排版真值。

### 融合架构（wenyan 双通道）

| 通道 | 实现 | 用途 |
|---|---|---|
| 预览 | 本机 `wenyan CLI`（`@wenyan-md/cli`，`WENYAN_CLI_PATH`）`render` 命令 | 纯排版输出 HTML，不碰微信 |
| 发布(S5) | 远程 `wenyan-server`（`WENYAN_MCP_SERVER_URL`，微信凭据配在 server 端） | `POST /upload`(+`x-api-key`)→fileId;`POST /publish`(fileId+.json)→`{media_id}` |

- 图片正文/封面**全部为图床公网 URL**（不再走 `asset://fileId` 通道，fileId 10 分钟 TTL 复杂度归零）。
- **S6 图库完全依赖图床，本地不留**：图片入库即直接转存图床（`ImageStorage.upload`），`storage_key` 非空；预览/发布组装时直接取 `storage_key` 拼公网 URL，**不再懒转存**。图床供应商抽象层 `ImageStorage`（当前实现七牛 `QiniuService`），切换供应商只需新增实现类 + 改配置。
- **配图组装规则（2026-09-01 定稿；2026-09-11 修订 R3，预览到发布衔接）**：`buildMarkdown` 后端发布链路统一组装为 frontmatter(`title` + 有封面时 `cover: <图URL>` + 手填 `author`/`source_url`) + 正文；**预览页/复制排版只渲染纯正文**（`renderMarkdownHtml(contentMd)`），不再把 frontmatter 拼进前端 markdown（`@wenyan-md/core` 不解析/剥离 frontmatter，会导致 `<hr>`+`<h2>title:…</h2>` 残留）；frontmatter 组装职责完全移到后端发布链路。**插图落点完全由正文 markdown 引用决定**——正文中引用了哪张图（图床公网 URL）、出现在哪里，就是最终文章的落点；未被正文引用的选定插图**不自动追加文末**（所见即所得）。`cover` 仅进公众号草稿封面元信息，不在正文渲染——正文里看不到封面图属预期。
- **插图落点（2026-09-01 交互定稿）**：预览页工具栏「插图」面板按选定顺序列出已选插图，点击即以 markdown 图片语法插入编辑器光标处（左栏 md 可见可编辑，正文已引用的在面板内标绿 ✓）；正文里没引用的插图不会出现在文章中（不自动追加文末），口径在面板内明示。
- **配图建议的落点（2026-09-16，09-15 article-auto-illustrate）**：预览页配图抽屉「智能建议」tab 的候选，用户点「插入到此段」/「全部采用」时（用户批准后才执行）：① 经 `MarkdownEditor.insertMdAtAnchor(headingPath, md)` 把 `![](原图URL)` 插到**锚点标题行之后**（按标题首次出现定位；标题找不到退回光标处）；② 调 `POST /api/projects/{id}/images/{imageId}/body?action=add` 登记 `body_image_ids`。**插图落点仍完全由正文 markdown 决定**（与上式一致）；系统绝不自动写入。详见 §10「配图建议」。
- 删除图：`ImageService.delete` 落库删除 + 图床对象（非阻塞，失败仅 warn）。
- 降级链：wenyan CLI 不可达/超时/失败 → 简化保底渲染（degraded=true + 中文原因）；主题名按后端权威目录校验防 CLI 参数注入；CLI 超时 `WENYAN_RENDER_TIMEOUT_MS`（默认 30s）。
- **主题目录（09-11-wenyan-themes）**：权威清单由 `WenyanThemeCatalog` 固定，共 **15 个** = 8 个 wenyan 内置（`default/orangeheart/rainbow/lapis/pie/maize/purple/phycat`）+ 7 个 mdnice 社区主题（`custom:chazi 姹紫 / custom:mohei 墨黑 / custom:nenqin 嫩青 / custom:hongfei 红绯 / custom:lanqing 兰青 / custom:shanchui 山吹 / custom:quanzhanlan 全栈蓝`）。`.env WENYAN_THEME_NAMES` 已废弃，不再参与校验/下发。详见 `docs/wenyan.md`。

### 数据模型增量（幂等 ALTER）

| 表.列 | 类型 | 说明 |
|---|---|---|
| sparkora_image_asset.storage_key | VARCHAR(300) | 图床 key（**入库即转存，非空**；原 qiniu_key 语义通用化） |
| sparkora_article_project.publish_media_id | VARCHAR(128) | S5 公众号草稿箱 media_id |
| sparkora_article_project.publish_theme | VARCHAR(64) | 发布所用主题 |
| sparkora_article_project.published_at | TIMESTAMP | 发布时间 |
| sparkora_article_project.last_publish_error | VARCHAR(1000) | 最近一次发布失败原因 |
| sparkora_article_project.author | VARCHAR(100) | S5+ 发布 frontmatter author（手填，可空） |
| sparkora_article_project.source_url | VARCHAR(500) | S5+ 发布 frontmatter source_url（手填，可空） |
| sparkora_article_project.preview_theme | VARCHAR(64) | S5+ 预览页当前主题（跨会话保持） |
| sparkora_article_project.preview_highlight | VARCHAR(64) | S5+ 预览页当前高亮主题 |
| sparkora_article_project.preview_mac_style | BOOLEAN | S5+ 预览页 Mac 代码块开关 |
| sparkora_article_project.preview_footnote | BOOLEAN | S5+ 预览页链接转脚注开关 |

### 接口契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images/preview-options` | 三角色 | — | `{themes[], highlights[], defaultTheme, highlight, macStyle, footnote}`；`themes` 为**对象数组**（09-11-wenyan-themes）：`[{id, name, group, color, bright}]`，`group` 为 `builtin`/`community`，前端下拉按组呈现（内置主题 / 社区主题） |
| POST | `/api/projects/{id}/preview` | 三角色 | `?theme=&highlight=&macStyle=&footnote=`（query） | `{html, theme, highlight, macStyle, footnote, degraded, degradedReason?}`；业务失败 HTTP 200 + `R.fail`，未知主题 `R.fail(400)`（按权威目录校验防 CLI 参数注入）；前置未就绪 `R.fail(400)` |
| PUT | `/api/projects/{id}/preview-style` | ADMIN/EDITOR | `{theme?, highlight?, macStyle?, footnote?}`（只更新非 null 字段） | `R<Void>`；项目不存在 `R.fail(404)`；主题/高亮非目录内 `R.fail(400)`（校验复用 `PreviewService`/`WenyanThemeCatalog`） |
| PUT | `/api/projects/{id}/publish-meta` | ADMIN/EDITOR | `{author?, sourceUrl?}`（只更新请求中出现的字段，空串清空） | `R<Void>`；项目不存在 `R.fail(404)`；author>100/sourceUrl>500 `R.fail(400)` |

- **依赖顺序**：项目状态 VERSIONS_READY 才可预览（`POST /preview` 校验，否则 `R.fail(400)`）；
- 七牛配置开关 `QINIU_ENABLED`（AK/SK 兼容旧裸名 `${AK}` `${SK}` 回退）：关闭时 `preview` 直接 `R.fail("图床未配置…")`；已配置但上传失败 `R.fail(500,"图床上传失败: …")`。
- 状态推进：预览不改变项目状态。
- 发布(S5)：与预览同参同源渲染,preview HTML = 发布真值;字段级契约与验收状态见 §12。

### 前端

- `StepPreview.vue`（`/projects/:id/preview` 子路由，步骤三）：`preview-options` 下拉/开关控件读后端配置；iframe `srcdoc` 顶部标注「排版引擎:文颜(与发布同源)」；degraded=true 顶部黄条；移动端适配。
- 四步流程 `maxReachableStepOf` 扩到 `index=3`（发布），发布步对 VERSIONS_READY/PUBLISHED_DRAFT 解锁。
- 配图并入预览：工具栏「配图」面板提供图库插入 + AI 生图（文生图/图生图，产物进图库后插入正文）两种来源；封面走 frontmatter `cover` 元信息。
- 预览到发布衔接（09-11-preview-publish-bridge）：预览页/复制排版只渲染**纯正文**（无 frontmatter 残留）；「去发布」时若正文 dirty 自动先保存再跳转（失败停留并提示）；预览主题/高亮/Mac/脚注变更防抖落库项目级（`PUT /preview-style`），刷新/跨会话保持；工具栏宽度档位 phone/tablet/full 仅作用于预览容器视觉；`ctrl-bar` 粘性 + 间距打磨。
- **主题选择范围（09-11-wenyan-themes）**：预览页主题下拉按「内置主题 / 社区主题」两组列出全部 15 个主题，社区主题显示中文名与色点；社区主题 `custom:*` 通过本机 CLI `--custom-theme <本地CSS绝对路径>` 渲染（CSS 随后端包内置），与内置主题一样可选、可预览、可发布。
- **主题渲染分支（09-11-wenyan-themes）**：内置主题传 `--theme <id>`；社区主题传 `--custom-theme <abs css>` 且**不传 `--theme`**（同传时 `--theme` 覆盖 `--custom-theme`）；`--custom-theme` 不支持网络 URL。CSS 由 `WenyanThemeCatalog` 启动时从 classpath 物化到数据盘 `{IMAGE_STORAGE_DIR}/../tmp/wenyan-themes/`（jar 安全），物化失败该主题走降级链。

### 已知限制与风险（登记)

- `pic.caiqz.cn` 仅有 http（https 证书未配）：预览从 localhost 拉不成问题；公众号内显示的是微信端上传后的 URL，不受影响。后续可加 https。
- wenyan-server 2.0.11 鉴权中间件对错误 key 挂起（不返回 401）：客户端超时不宜过长，且建议 server 升级。
- theme 清单由后端 `WenyanThemeCatalog` 权威固定（15 个，见 §11），不依赖 server 端注册：主题只在本机 CLI 渲染阶段应用，wenyan-server 只收渲染后的 HTML，不感知主题。`.env WENYAN_THEME_NAMES` 已废弃。
- **社区主题 CSS 禁止外链图片（09-11-quanzhanlan-broken-image）**：发布时 wenyan-server 会下载渲染 HTML 中引用的所有图片，任一外链失效即整次发布失败（报错「下载图片失败 URL」）。新增/维护社区主题必须自检 `grep -nE "url\(https?://" src/main/resources/wenyan-themes/*.css frontend/src/assets/wenyan-themes/*.css` 为空；历史事故：全栈蓝 `quanzhanlan.css` 曾引用失效图壳图标（`imgkr.cn-bj.ufileos.com`，HTTP 400）致发布失败，已移除。详见 `docs/wenyan.md`。

### 12. 公众号草稿发布模块（S5，正式规格）

> 2026-09-01 定稿,方案 A 发布侧:与预览同渲染核,**preview HTML = 发布真值**。
> 2026-09-01 实测勘误(@wenyan-md/cli 2.0.11):`/verify` 为 **GET** 探针;`/upload` multipart 字段名 `file`(限 md/css/json/图片,≤10MB);`/publish` 收 **JSON `{fileId, appId?}`**(fileId 须为上传的 .json);当前部署无效 key 即刻 401。上传文件 TTL 10 分钟。

#### 发布链路（同步,一次调用完成）

```
PublishService.publish
 → PreviewService.preview(同参同源:状态校验 + 取图床 URL + frontmatter + wenyan render)
 → 非 degraded 校验(降级 HTML 不进公众号)
 → gzhContent JSON { title(≤64,必填), content=渲染HTML, cover=封面图床URL?, author?, source_url? }   ← asset:// 不用,图片全为图床 http URL;cover 与预览 frontmatter 同源,缺失时 server 退化用正文首图当封面;author/source_url 手填项目级字段,非空才发送(对齐 wenyan frontmatter→微信 author/content_source_url)
 → wenyan-server POST /upload (multipart file=.json) → fileId
 → wenyan-server POST /publish (JSON {fileId}) → {media_id}
 → 原子落库 status=PUBLISHED_DRAFT + publish_media_id/publish_theme/published_at,清 last_publish_error
```

- 封面与正文 `<img src="http(s)…">` 由 server 端 fetch 后转传微信(七牛 http URL 可用);无封面时 gzhContent 不带 cover,草稿封面由 server 退化取正文首图(可能无封面图,不阻塞发布)。
- 发布元信息（09-11-preview-publish-bridge）：`author`/`source_url` 由发布页手填、项目级落库（`preview-style`/`publish-meta` 端点），非空才进 gzhContent；留空不发送，行为与旧版一致。**风险登记**：远程 wenyan-server 2.0.11 是否透传 `author`/`source_url` 未实测（未知 JSON 键通常被忽略）；如实测被拒，降级为不发送这两键（保留落库与前端展示）。
- 失败语义:任何一步失败 → `last_publish_error` 落库、状态原样保留、`R.fail(400|500, 中文原因)`;可重试整链。

#### 接口契约(全部 `R<T>` 包装;HTTP 200)

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects/{id}/publish-options` | 三角色 | — | `{themes[], highlights[], defaultTheme, highlight, macStyle, footnote, previewTheme?, previewHighlight?, previewMacStyle?, previewFootnote?, author?, sourceUrl?, publishEnabled, publishConfigOk, publishDisabledReason?, wenyanServer, publishMediaId?, publishTheme?, publishedAt?, lastPublishError?}`(探针失败不阻塞页面;preview* 为项目级预览样式,发布页优先据此初始化;`themes` 为对象数组 `[{id, name, group, color, bright}]`,与 §11 preview-options 同源) |
| POST | `/api/projects/{id}/publish` | ADMIN/EDITOR | `?theme=&highlight=&macStyle=&footnote=`(query,与 preview 同形) | 成功 `{mediaId, theme, publishedAt}`;前置不满足/渲染参数非法/通道未配置 `R.fail(400)`;链路失败 `R.fail(500)`;失败均回写 last_publish_error |

- 配置:`WENYAN_MCP_SERVER_URL`(带 scheme)/`WENYAN_MCP_SERVER_API_KEY`/`WENYAN_MCP_PUBLISH_TIMEOUT_MS`(默认 30s);未配置时 publish-options 返回 publishEnabled=false + 中文原因,publish 返回 `R.fail(400)`。
- 前端:`StepPublish.vue`(第 5 步子路由 `/projects/:id/publish`):摘要(标题/封面缩略/插图数)+ **排版参数只读回显**(主题/高亮/Mac/脚注,值来自预览页落库的 `preview*`,不在此编辑;发布时原样传给 wenyan-server)+ 作者/原文地址手填(项目级落库)+ 发布确认弹层 + 成功态(mediaId/时间/重发)+ 失败黄条;viewer 只读;`maxReachableStepOf` 放开到 index=4,`StepPreview` 状态判断修正为 PUBLISHED_DRAFT 并加「去发布」衔接。

#### 验收状态

- [x] `GET /verify`(GET)真 key 200 / 假 key 401;`POST /upload` 真实 JSON 探针 → fileId(2026-09-01 实测)
- [x] 三角色冒烟:viewer publish 403;DRAFT 项目 publish `R.fail(400)`;publish-options 探活 publishEnabled=true
- [x] `mvn test`(空测试集)/ `npm run build` 通过
- [ ] 真实发布进公众号草稿箱(publish 全链)→ **留用户真机验收**
### 13. 深度生成模式（S9，正式规格，2026-09-04）

> 六阶段流程：①理解（研究计划）→ ②一次性澄清表单 → ③并行子代理研究（KB+WEB）→ ④事实手册 → ⑤深度写作 → ⑥数值回查。
> 深度模式仅作用于 brief 层（`gen_mode=DEEP`），**项目状态机（§4）不变**；`CLARIFYING/RESEARCHING` 为 /deep/status 展示态，非项目状态。
> 断点续跑：每阶段产物落库（research_plan→clarify_questions→clarify_answers→research_notes→fact_sheet），从任意阶段恢复。

#### 数据模型（schema.sql 幂等，已同步 entity）

- `sparkora_article_brief` 增列：`gen_mode TEXT DEFAULT 'FAST'`（2026-09-09 模式收敛:新 brief 恒为 DEEP,FAST 默认值仅存量语义;存量行不迁移）、`clarify_questions TEXT`、`clarify_answers TEXT`、`research_plan TEXT`、`research_notes TEXT`、`fact_sheet TEXT`、`rag_citations TEXT`（R3 知识引用明细）、`plan_status VARCHAR(20)`（2026-09-11 clarify 异步化：DEEP 行 `PLANNING`=研究计划生成中 / `READY`=已就绪；FAST/IMITATION/存量行 null）。配部分唯一索引 `uq_brief_planning ON sparkora_article_brief(project_id) WHERE plan_status='PLANNING'`——同一项目同时至多一条 PLANNING，双击/双开触发的数据库级并发兜底（撞索引转 409）。
- `sparkora_article_version` 增列：`fact_risks TEXT`（数值回查结果，JSON 数组 `[{claim,riskLevel,suggestion}]`）、`rag_citations TEXT`（R3 知识引用明细）。

#### 接口契约（全部 `R<T>` 包装；方法级 `@PreAuthorize`；前缀 `/api/projects/{projectId}/deep`）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/deep/clarify` | ADMIN/EDITOR | `{topic(必填), extraInfo?}` | **2026-09-11 异步化**：`{briefId, stage:"PLANNING"}`，毫秒级返回（不再携带计划内容）；同步落 PLANNING 占位 brief(gen_mode=DEEP)，后台 `@Async` 生成研究计划与澄清问题，成功回写 plan/questions + `plan_status=READY`；失败删除占位行 + 写 `project.last_brief_error`。并发/陈旧冲突 → `R.fail(409,...)` |
| POST | `/deep/clarify-answer` | ADMIN/EDITOR | `{briefId, answers:{问题:答案}}` | `{briefId, locked}`（锁定 JSON 落库） |
| POST | `/deep/run` | ADMIN/EDITOR | `{briefId}` | `{briefId, agents, done}`（同步阻塞；前端轮询 status） |
| POST | `/deep/generate` | ADMIN/EDITOR | `{briefId, styleId?}`（09-10-style-library-enhance:styleId 优先,后端回查风格表取 toneGuidance/name 注入 system prompt;查无 → 400「风格不存在或已删除」;旧 `stylePrompt`/`styleName` 保留兼容,deprecated） | `{versionId}`（版本 fact_risks 落库；09-10-versions-page-fix：落版本补齐 title/version_label/style_tag/word_count，成功后推进状态机 READY→VERSIONS_READY、首版设 current（追加不覆盖）） |
| POST | `/deep/brief` | ADMIN/EDITOR | `{briefId}` | `ArticleBriefEntity`（基于事实手册生成简报，落同一条 DEEP brief 行并推状态机到 READY；研究完成后自动触发一次，此处为手动重试入口；409=状态冲突） |
| GET | `/deep/status` | 三角色 | `?briefId`(缺省取最新 DEEP brief) | `{briefId, genMode, stage, planStatus, researchPlan?, questions?, answers?, agents?, factSheet?, toolHealth:{KB,SEARXNG,TAVILY}}` |

- stage 判定（brief 层展示态）：`PLANNING`（plan_status=PLANNING，clarify 占位生成中，2026-09-11 新增，优先于其余判定）> `RESEARCH_DONE`（fact_sheet 非空）> `RESEARCHING`（research_notes 非空）> `CLARIFIED`（answers 非空）> `CLARIFYING`（questions 非空）> `NONE`。
- toolHealth（2026-09-15 契约升级，值由布尔改状态码 `OK|DISABLED|UNCONFIGURED|FAILED`）：`KB` = `kb_enabled ? OK : DISABLED`（反映设置页运行时门控，不再恒 true）；`SEARXNG`/`TAVILY` 先判 `SEARCH_WEB_ENABLED && webSearchEnabled`（false → `DISABLED`），再按 `configured()`（密钥/地址就绪）→ `UNCONFIGURED`、`lastCallOk()`（最近一次调用健康态，初值乐观）→ `FAILED`/`OK`。优先级 `DISABLED > UNCONFIGURED > FAILED > OK`。前端未拿到该字段时渲染 `--`（未知态，不谎报可用）。
- 权限冒烟：viewer 访问写接口 403（`hasAnyRole('ADMIN','EDITOR')`）。

#### 工具层（SearchTool 抽象，`com.sparkora.deep.tool`）

| 工具 | 实现 | 来源 | 降级语义 |
|---|---|---|---|
| KB | `KnowledgeSearchTool` | 委托 `CarRagService.retrieveForGeneration` 统一检索（§6c，S8） | 异常 warn，不抛出 |
| SEARXNG | `SearxngSearchTool` | GET `{SEARXNG_BASE_URL}/search?q=&format=json&language=zh-CN` | 超时/空结果静默空列表 + `lastCallOk()=false`（仅供健康展示）；`available()` 仅判地址就绪，失败不闩锁 |
| TAVILY | `TavilySearchTool` | POST `api.tavily.com/search` `{api_key,query,max_results,search_depth}` | 密钥未配置 → `available()/configured()=false`；调用失败仅置 `lastCallOk()=false`，下次研究自动重试 |
- WEB 选择顺序：SEARXNG → Tavily（拿到结果即止）；每子代理 webQuota=`max(1, 8/n)`，`SEARCH_WEB_ENABLED=false` 时为 0（纯 KB）。
- **KB 锚点感知检索（R1，2026-09-06）**：`KnowledgeSearchTool.search(query, maxResults, anchors)` 委托 `retrieveForGeneration`（锚点加权 + 参数级子查询 + 核心块/权益块分层配额）；锚点由 `DeepResearchService.resolveAnchors` 解析（项目关联车型为准 → `CarModelMatcherService` 按主题识别兜底，失败不阻断）；子代理 KB 检索 query 用「主题 + 问题」复合语料（纯问题如「价格对比」缺车型上下文相似度必散）。非 OK 状态返回空列表归 gaps（行为同旧）。
- **WEB gap 驱动（R1 同批）**：KB 已命中车型域权威块（命中含 MODEL_INFO/价格区间文本）时跳过 WEB 补查——WEB 只补 KB 缺口，不与 KB 平行全问题重搜、不得覆盖 KB 结论。
- **同 claim 冲突裁决（R2，2026-09-06）**：`FactSheetService.merge` 聚合时同 claim 同时含 KB 与 WEB 来源 → **KB 胜出**（不比较相似度/置信度，量纲不同不可比；按来源身份定优先级：本系统知识库（比亚迪同步清洗）> 外部 WEB）。WEB 条目降级为该条目 `alternatives`（URL 列表）留证据，并写 warnings「以知识库为准；外部来源(N 条)有异说,未采用」。纯 KB / 纯 WEB 条目维持原置信规则（KB 0.9 / 多源交叉 0.85 / 单一 WEB 0.4 + 待核实）。
- `SearchHit.web(type=工具名→展示源)`：type 统一为 `WEB`（计数依据），工具名记 modelName 字段。
- 密钥链：`DEEP_TAVILY_API_KEY`(System property/env) → `TAVILY_API_KEY` → `sparkora.deep.tavily-api-key`（`DeepProperties` 绑定前缀 `sparkora.deep`，2026-09-15 修正；dotenv 注入 System property，嵌套占位符 `${A:${B:}}` Spring 不支持，故 yml 只挂 `TAVILY_API_KEY`）。

#### 研究笔记 / 事实手册结构

- research_notes：`[{agentId, question, status(DONE/FALLBACK/FAILED), factsJson, webCount}]`；factsJson=`{facts:[{claim,value,source:{type:"KB|WEB",url,modelName,docId},confidence}],gaps:[...]}`。
- fact_sheet（FactSheetService.merge，按 claim 去重聚合）：`{entries:[{key,claim,value,sources:{type,url,modelName,docId},crossCount,confidence}],gaps:[...],warnings:[...]}`。
- 置信度规则：多源交叉(≥2) 0.85 交叉标注 / KB 0.9 / 单一 WEB 0.4 + warnings「仅单一 WEB 源,待核实」/ 冲突 0.3。
- 已知限制：WEB 命中为摘要级（snippet），不做正文抓取；低置信条目以 warnings 提示人工核实。

#### 深度写作与数值回查

- `DeepWriterService.write`：fact_sheet 条目进 prompt + 铁律「数值必须逐字出自手册」→ `AiClient.chat`（非 JSON 方法）→ 落 version（复用版本链路）。
- 数值回查（正则，0 次 LLM）：抽取正文数值（万/千分位/百分比/带单位 km|kWh|kW|mm|L/100km|s）与手册比对，手册外数值 → `fact_risks` `[{claim,riskLevel:"high",suggestion:"发布前必须人工核实或删除"}]` 落版本字段。
- 2026-09-04 实测：version 1917 字符，捕获手册外「25万」high 1 条。

#### 前端（`views/project/deep/` 四组件 + StepBrief 深度分支）

- 反问题型：`input` / `single`（radio）/ `multi`（checkbox，车型锚点多选；提交时以「、」拼接、回显时按「、」还原数组）。ClarifyForm 支持全部三型（2026-09-05 增补 multi）。
- **反问必须基于车库名录（2026-09-05 修复）**：`ClarifyService` 注入 `CarModelService.list()` 名录（名称+价格区间）进 prompt；规则：车型/竞品/对比类问题的 options 只能从名录选、不得编造；主题指向某款/某系列车型时必须有一道 multi 锚点车型题（options 覆盖名录中含该系列词的全部车型）。车库获取失败降级为不注入并提示不编造车型。
- **竞品对比题强制多选（R1，2026-09-05）**：prompt 明确「对比/竞品/比较/竞对类问题 type=multi（选项 2~4 个竞品 + 「不对比」兜底）」；后端 `ClarifyService.normalizeQuestions` 确定性归一化兜底（不依赖 LLM 遵守）：问题文本含竞品信号词（对比/竞品/比较/竞对/竞争）的选项题强制 `type=multi` 并补「不对比」选项（缺省时）；无选项的竞品题归 `input`（自由填写）；解析失败原样保留不阻断。
- **「其他(自行填写)」（R2，2026-09-05）**：`ClarifyForm.vue` 对 single/multi 题渲染「其他(自行填写)」入口——single 选中后切文本框（提交取文本框内容），multi 勾选后文本并入答案（「、」拼接）；锁定回显时不在 options 中的答案自动归「其他」并回填。
- `DeepPlanCard`（研究计划）/`ClarifyForm`（生成↔锁定回显两态）/`ResearchProgress`（2s 轮询 status + 工具健康行 toolHealth 徽标）/`FactSheetSummary`（手册摘要 + 来源徽标 KB 蓝/WEB 紫 + 置信度条 + gaps/warnings）。
- **研究完成 → 自动生成简报（2026-09-05 修复）**：`DeepResearchService.runAsync` 落 fact_sheet 后自动调 `BriefService.generateFromFactSheet`（LLM 一次，以事实手册为唯一事实来源 + 锁定需求 → 简报五字段落同一条 DEEP brief 行，`currentBriefId` 指向该行，状态机 GENERATING_BRIEF→READY）；失败不回滚研究产物（回 DRAFT + lastBriefError，深度面板可手动重试 `/deep/brief`，也可「跳过简报直接生成正文」）。修复「确定研究计划/研究完成后没有简报页面」的结构性缺陷。
- StepBrief.vue（2026-09-11 单一状态机收敛，09-11-brief-gen-flow-refactor）：**无 FAST/DEEP 模式切换**——唯一生成路径为深度流程，无简报区间由唯一 `deepStage` 状态机驱动（值域 `NONE|PLANNING|CLARIFYING|CLARIFIED|RESEARCHING|RESEARCH_DONE`），同一状态恒渲染同一 UI，与进入路径（创建直发/重新进入/仅存草稿）无关；**删除 `deepMode` 路径意图布尔与 6s 有界重探测**。project 就位后 `syncDeepStatus()` 单次拉 `/deep/status` 断点恢复（PLANNING 则续起 2.5s 自轮询），不再依赖 `?gen=deep`。「重新研究生成」直接 `startDeep()` 进 PLANNING（`restarting` 标志跳过旧简报正文分支，新简报落库后恢复）。无简报区间只保留**一个**主操作「开始深度研究」，删除「开始深度研究→生成研究计划」两步链与裸生成按钮。CLARIFYING/RESEARCHING 仅 brief 展示态，项目状态机不变（constants/project.js 注释）。RESEARCH_DONE 态下简报正常展示（自动简报完成即 READY）；失败显示「重新生成简报」+「跳过简报,直接生成正文」。ragStatus 展示增 `DISABLED`（知识库已停用·全局设置，灰，§6b）。
- 移动端：单列纵排、抽屉全屏、触控 ≥44px。

#### 配置（.env.example 已同步）

| 变量 | 默认 | 说明 |
|---|---|---|
| `SEARCH_WEB_ENABLED` | `true` | WEB 搜索总开关（SEARXNG+Tavily） |
| `TAVILY_API_KEY` / `DEEP_TAVILY_API_KEY` | 空 | Tavily 密钥（.env；DEEP_ 前缀可覆盖） |
| `SEARXNG_BASE_URL` | `http://localhost:5676` | SEARXNG 实例（本机/内网部署，2026-09-04 迁移至 192.168.3.108:5676） |
| `CRAWL4AI_BASE_URL` | 空 | 预留：正文抓取工具未接入（摘要级搜索的后续增强） |
| `DEEP_RESEARCH_TIMEOUT_MS` | `120000` | 单子代理超时（futures.get 兜底，超时→FAILED+gap） |
| `DEEP_MAX_AGENTS` | `4` | 子代理数上限（虚拟线程 per-task executor） |

#### 验收状态（2026-09-04）

- [x] AC1 深度模式端到端（项目20/briefId=18：clarify 5 问→锁定→run agents=4 done=4→fact_sheet→generate versionId=16）
- [x] AC2 并行研究：4 虚拟线程子代理并行，全部 DONE（首次 run 因 toolHints 序列化 bug 全 KB，修复后 webCalls=5/6）
- [x] AC3 WEB 来源进手册：fact_sheet 6 条含 2 条 WEB（海狮08 22.99万起/海狮06 12.99-19.98万），置信度 4×0.9+2×0.4，warnings 4 条
- [x] AC4 写作+数值回查：version 1917 字符；fact_risks 捕获手册外「25万」riskLevel=high
- [x] AC5 快速模式回归：项目21（FAST brief id=19 + version id=17/18）全通过，深度/快速互不影响
- [x] AC6 `mvn test-compile surefire:test` 44 全绿；`npx vite build` 绿（48s）
- [ ] AC7 研究过程可视化 UI 真机走查（计划→表单→进度→手册→生成）→ **留用户浏览器验收**

---

### 14. 文章仿写模块（09-09-article-imitation，正式规格，2026-09-09）

> 项目级新模式 `genSource=IMITATION`：粘贴参考原文 → AI 分析+风格推荐 → 选风格仿写（去图）→ 相似度自检 → 预览/发布（全链路与主题创作合流）。
> 设计原则同 §13 深度模式先例——**模式字段驱动，项目状态机（§4）不动**；仿写跳过 RAG（ragStatus=NO_KNOWLEDGE）。

#### 数据模型（schema.sql 幂等 ADD COLUMN，回滚仅需代码回退）

- `sparkora_article_project` 增列：`gen_source VARCHAR(20) NOT NULL DEFAULT 'TOPIC'`（TOPIC/IMITATION）、`imitation_text TEXT`（参考原文全文，仅 IMITATION 非空）、`imitation_analysis TEXT`（分析结果 JSON `{genre,structure,sentenceFeatures}`）。
- `sparkora_article_brief` 增列：`style_recommendations TEXT`（JSON `[{styleId,name,reason,matchScore}]`，仅 gen_mode=IMITATION brief 使用）。
- `sparkora_article_version` 增列：`similarity_score DOUBLE PRECISION`（0~1）、`similarity_report TEXT`（JSON `{maxRunLength,maxRunText?,repeatedRuns:[{text,length}],thresholds}`）。

#### 流程与状态机

```
创建(genSource=IMITATION, 粘贴原文≤20000字) → DRAFT
  → POST /imitation/analyze → GENERATING_BRIEF → READY   (brief.gen_mode=IMITATION: 原文分析+风格推荐)
  → StepVersions 选风格(推荐高亮/一键采用) → GENERATING_VERSIONS → VERSIONS_READY (仿写 prompt+去图+相似度自检)
  → 预览/发布（与主题创作完全复用，§10/§11/§12）
```
- 状态守护与原子抢占仿 BriefService：仅 DRAFT/READY 放行、生成中未过期拒绝（409）、陈旧超 10 分钟自愈；失败回 DRAFT 写 `last_brief_error`。

#### 仿写生成（R3）

- 复用 `POST /generate/versions`（主题创作已封死为 410，**仿写项目例外**，`VersionService.generate` 内 genSource 分支）。
- 仿写 prompt：style.toneGuidance + 仿写铁律（保留观点组织/严禁连续 10 字以上照搬原句/不得保留原文任何图片）+ 原文全文 + 结构大纲 + 字数目标。system 内 toneGuidance 后附统一强化句「以上语气、句式、结构与用词特征必须在正文中充分体现,不得只在部分段落贴合。」(09-10-style-library-enhance,主题/深度链路同款)。
- 双保险去图：prompt 约束 + 生成后正则清洗（`VersionService.stripImages`：Markdown `![..](..)`、HTML `<img>`、「配图/图注/示意图/图片来源:」占位行全剔除）。
- 仿写跳过 RAG：不检索车型库（任意题材原文与车型库强行匹配会注入无关数据约束），version.rag_status=NO_KNOWLEDGE。
- 相似度自检（`ImitationService.similarityCheck`，纯本地 0 次 LLM）：规范化（去 Markdown/HTML/标点/空白，小写化）→ 字符 5-gram 重合率 `|仿写 n-gram ∩ 原文 n-gram| / |仿写 n-gram|`（防照搬视角）→ 最长公共连续片段（朴素 DP）→ 连续 ≥10 字重复片段列表（贪心扩展去重，≤10 条单条截 120 字）。阈值常量：`SIM_WARN=0.40 / SIM_HIGH=0.60 / RUN_WARN=13`（ImitationService，实验性调参不进 .env）。仅警示不阻断、不自动改写。

#### 接口契约（全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/projects` | ADMIN/EDITOR | `genSource`(缺省 TOPIC)/`imitationText`(IMITATION 必填非空) | `{id}`；IMITATION 缺原文 `R.fail(400)`；仿写项目不关联车型（跳过 AI 自动匹配） |
| POST | `/api/projects/{id}/imitation/analyze` | ADMIN/EDITOR | — | `ArticleBriefEntity`（gen_mode=IMITATION；一次 AI 调用产出原文分析落 outline/coreViewpoints/titleCandidates + 风格推荐 ≤3 个附理由落 style_recommendations，只保留库内 styleId 防御截断）；非仿写项目 400；状态冲突 409；失败回 DRAFT 写 last_brief_error |
| GET | `/api/projects/{id}/imitation` | 三角色 | — | `{briefId, titleCandidates, coreViewpoints, outline, styleRecommendations, analysis:{genre,structure,sentenceFeatures}}`（无则 `data:null`） |
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | 仿写模式复用：每风格一版（含 similarity_score/similarity_report）；状态机同 §4 |

#### 前端

- `ProjectEdit.vue`：区块 00「创作方式」radio-button（主题创作默认/文章仿写）；仿写分支：topic 语义改「任务名」、原文 textarea 必填 ≤20000 字带字数统计、隐藏深度研究提示；「创建并分析原文」创建后跳详情页带 `?gen=imitation` 并直发 analyze（失败页面内重试）；切回主题创作清空原文。
- `StepBrief.vue`：仿写模式（project.genSource 判定）标题改「原文分析」；独立视图（分析中 skeleton/引导语含 lastBriefError/分析结果卡题材·结构·句式 + 风格推荐卡 ≤3 个附匹配度%与理由，点「采用」带 `?adoptStyle=` 跳版本步并自动预选）；「重新分析」仅 READY；空推荐时引导去风格库不阻断。
- `StepVersions.vue`：仿写分支——原文摘要折叠卡、推荐风格「推荐」角标（styleRecommendations）、生成走 `generateVersions`（非深度逐风格）、版本卡相似度行（`(score*100).toFixed(1)%` + 阈值色 ≥0.60 红「与原文过度相似，建议修改」/0.40~0.60 黄/<0.40 绿 + 重复片段明细可折叠含 maxRunLength≥13 提示）。
- `constants/project.js` **零改动**（状态机不动）。

#### 验收清单

- [ ] AC1 创建仿写项目成功/缺原文 400/TOPIC 回归一致
- [ ] AC2 分析后 READY、brief 含分析与推荐；失败回 DRAFT；生成中重触发 409
- [ ] AC3 仿写多版生成、全文无图片（正则验证）
- [ ] AC4 版本列表相似度数值+阈值色+重复片段明细；数值本地可复现
- [ ] AC5 仿写版本设当前→预览→发布链路一致
- [ ] AC6 viewer 调 analyze/generate 403；三角色可读 imitation
- [ ] AC7 空风格库：推荐空数组，前端引导提示，不阻断
- [x] AC8 `mvn -q -DskipTests compile` 与 `npm run build` 通过（2026-09-09 check 复验 ✓）

---

### 15. 新闻知识域（C2，正式规格，2026-09-11）

> 比亚迪官方新闻（列表 `/es/search` + `www.byd.com` 详情页 SSR HTML）→ 清洗入库 → 切块向量化，
> 作为与车型（CAR）/通用知识（KB）并列的**第三知识域 NEWS** 接入统一检索。**新闻与车型不关联**。
> 父任务：`09-11-knowledge-base-data-foundation`；依赖 C1（复用同步任务/调度范式与统一检索改动）。

#### 数据模型（schema.sql S11 区块，幂等 `CREATE TABLE IF NOT EXISTS`）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_news` | id / **news_id VARCHAR(200) UNIQUE**（官方字符串 id，业务唯一键）/ title(≤500) / url / image_url / publish_date / tags(JSON) / tag_names(JSON) / content / source(默认 byd-news) / sync_status(SUCCESS/FAILED) / last_sync_at / last_sync_error / **cover_image_id BIGINT**（09-15 img-classify 幂等补列：封面图对应的图库 asset id，可空不建外键）/ created_at / updated_at / deleted | 新闻主表；逻辑删。索引 `idx_news_publish(publish_date)` / `idx_news_status(sync_status)` |
| `sparkora_news_doc` | id / **news_id BIGINT FK→sparkora_news(id)**（内部 id）/ seq / chunk_type(NEWS_BODY/NEWS_TITLE) / chunk_text / token_count / created_at / updated_at / deleted | 检索块；首行固定「新闻：<title>（<publishDate>）」。索引 `idx_news_doc_news` |
| `sparkora_news_doc_embedding` | id / doc_id FK / news_id FK / embedding VECTOR(1024) / created_at | 物理表（无 deleted）；HNSW cosine `idx_news_doc_emb_vec` + `idx_news_doc_emb_news` |
| `sparkora_news_sync_job` | id / job_type(FULL/INCREMENT/SCHEDULED/RETRY) / status(RUNNING/SUCCESS/PARTIAL/FAILED) / total / success / failed / failed_items(JSON:[{newsId,title,error}]) / started_at / finished_at / error_msg / created_by / created_at / deleted | 同步任务表（复用车型任务表范式）。索引 `idx_news_sync_job_created` |

> 命名注意：`sparkora_news.news_id` 是**官方字符串 id**；`sparkora_news_doc.news_id` 是**内部 BIGINT 外键**。实体：`NewsEntity.newsId`(String) vs `NewsDocEntity.newsId`(Long)。

#### 采集与解析

- `com.sparkora.news.client.BydNewsClient`：`searchPage(page,size)` POST `{listUrl}`（body 固定 `{brandName:"byd",siteName:"cn",type:"news",page,size,sortField:"date",year:""}`，带 `Referer: https://www.byd.com/`，读 `data`）；`fetchDetailHtml(url)` GET `{detailBaseUrl}{url}`。RestClient，超时读 `NEWS_TIMEOUT_MS`，失败抛 `AiException`。
- `com.sparkora.news.service.NewsContentParser`（jsoup 1.18.1，选择器集中于此）：标题 `.cmp-news__detail-title`；日期 `.cmp-news__detail-date`（如「发布于 2026-09-01 17:08:31」）；正文容器 `.cmp-news__detail-content`，段落 `.news-text p`（`<br>`→换行），图片 `.news-image img`（仅记 src，以 `[图片] <src>` 并入正文，**不下载**）。解析失败降级空正文，不抛。
- 图片型/无正文新闻：`content` 为空仍入库元数据；切块保留标题锚点块（`NEWS_TITLE`），无标题则跳过切块。

#### 入库与向量化

- `com.sparkora.news.service.NewsDocService`（仿 CarDocService/KbDocService）：`rebuildForNews(newsId)` 先物理清 embedding+doc 再切块（首行「新闻：<title>（<publishDate>）」；空行分段、单段 ≤500、超长按句读切分合并）+ embedding 并发化（固定小线程池）+ 单块失败重试 1 次；`deleteByNews` 物理清块与向量；`chunkTypeOf(chunks)` 纯函数判定块类型（唯一块且无换行 → `NEWS_TITLE`，其余 `NEWS_BODY`）；块数由调用方 `docMapper.selectCount` 计算，不再提供 `chunkCount(newsId)`/`NewsDocEmbeddingMapper.countByNews()`（C2 死代码已删）。
- `com.sparkora.news.service.NewsService`：`syncFull()`（遍历 `data.pages` 全部页）/ `syncIncrement()`（列表按 date 倒序，本页全部「已存在且正文非空」即提前停止）；逐条抓正文 → 按 `news_id` 幂等 upsert → `rebuildForNews`；单条失败记 failedItems 不阻断；`list(page,size,keyword)`（分页 + title 模糊 + 块数）、`get(id)`、`existsWithContent(newsId)`。官方 date 解析失败置 null。**09-13 image-tags 起**：`upsertOne` 另下载 `imageUrl` 封面字节走统一入库管线转存图库（`source=byd-news`、标签「新闻」，`sparkora_news.image_url` 保留原 URL 留痕）；**单图下载失败仅告警不阻断新闻入库**（同车型图容错先例）。**09-15 img-classify 起**：封面入库携带 `NewsImageClassifier.toTagsFrom(title, publishDate)` 派生的 `主题/<名>` 与 `年份/<年>` 标签及 `sourceRef=news_id`；入库成功后回填 `cover_image_id`（`null` 或指向已失效图时才写，不覆盖有效值）；`list`/`get` 另填非持久化 `coverImageUrl`（图库公网 URL）与 `themes`（标题分类主题，同分类器重算不查图库）。

#### 同步任务与调度

- `com.sparkora.news.service.NewsSyncJobService`（仿 CarSyncJobService）：`createJob(jobType)`（`@Transactional` 落 RUNNING，created_by=SecurityUtil）；`@Async runJob(jobId)`（原子锁 status=RUNNING→RUNNING 影响行数=0 拒绝）；`finish`（SUCCESS/PARTIAL/FAILED + failed_items JSON）；`get`/`list`/`retry`/`hasFreshRunning`/`markStaleRunningAsFailed`。
- `com.sparkora.news.service.NewsSyncScheduler`：`@Scheduled(cron="${sparkora.news.sync-cron:0 30 3 * * ?}")`，`NEWS_SYNC_ENABLED=false` 直接返回、先 `markStaleRunningAsFailed()` 清理陈旧 RUNNING 再 `hasFreshRunning()` 防重叠、全程 try/catch，jobType=SCHEDULED。默认关闭。
- **定时同步陈旧自愈（kb-cleanup，2026-09-12）**：任务表无 `updated_at`，以 `started_at` 为存活时间戳，阈值 `SYNC_STALE_MS=60 分钟`（全量 56 车型 + 清洗 + embedding 实测可超 20 分钟，10 分钟会误判活任务）。`markStaleRunningAsFailed()` 将 `status=RUNNING 且 started_at < now-60min` 原子置 `FAILED` + `finished_at` + `error_msg='运行超时判定为陈旧,自动终止'`；`hasFreshRunning()` 只统计 `RUNNING 且 started_at >= now-60min`。JVM 中途死亡残留不再永久阻塞定时任务；未过期 RUNNING 仍阻塞（防重叠不回归）。车型（`CarSyncJobService`/`CarSyncScheduler`）与新闻两侧对称实现。手动 `createJob`/`runJob` 的 `status=RUNNING→RUNNING` 原子锁语义不变。

#### 统一检索接入（C2 跨层关键改动）

- `CarDocEmbeddingMapper.searchTopKUnified`：在既有 CAR + KB 两段 UNION 后**追加第三段 NEWS**（`source='NEWS'`、`modelId=NULL`、`modelName=n.title`、JOIN `sparkora_news_doc d ... d.deleted=0` 与 `sparkora_news n ... n.deleted=0`）；既有两段语义不变。**候选窗口按域隔离（C2 check 修复）**：CAR+KB 合并取 top-`limit`（与 C2 前完全一致），NEWS 单独取 top-`limit`；不可三者共用一个全局 `LIMIT`——新闻块（≈1300+）与车型/KB 同向量空间且语义邻近时会占满整个窗口，把 CAR/KB 完全挤出候选（实测 BYD 新闻类 query CAR 候选从 32 掉到 0），使下游独立配额失效。
- `CarRagService.retrieveForGeneration`：新增 NEWS 候选池 + 独立配额 `AI_RAG_NEWS_TOPK`（默认 4，`0` 关闭 NEWS 注入）；**NEWS 不受 `AI_RAG_KB_ENABLED` 控制**；行内标注「【官方新闻：<title>】」；首行 `sourceLine` 支持三域组合（车型数据 / 通用知识库 / 官方新闻）；**锚点加权仅对 `source=CAR` 生效**（NEWS/KB 不变）；coveredText 仅统计 CAR 参数块；citations 纳入 NEWS（source=NEWS）。**不改 `RagStatus` 四态语义与既有 CAR/KB 行为**；主查询过采样沿用 C2 前口径 `max(topK*4,32)`（候选窗口隔离由 mapper 负责，无需额外余量）。

#### 接口契约（`/api/news`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/news` | 三角色 | 分页列表 `?page&size&keyword`，`PageResult`（含 id/title/publishDate/tagNames/imageUrl/chunkCount；**09-15 img-classify 起另含 `coverImageUrl`**（图库公网 URL，无同步封面时为 null）**与 `themes`**（标题分类命中主题，保序数组）；列表/详情均携带） |
| GET | `/api/news/{id}` | 三角色 | 详情（含 content）；不存在 `R.fail(404)` |
| POST | `/api/news/sync/jobs` | ADMIN/EDITOR | body `{jobType:"FULL"\|"INCREMENT"}`（缺省 INCREMENT），返回 `{jobId}` |
| GET | `/api/news/sync/jobs/{id}` | 三角色 | 任务进度；不存在 `R.fail(404)` |
| GET | `/api/news/sync/jobs` | 三角色 | 任务历史（按 id 倒序） |
| POST | `/api/news/sync/jobs/{id}/retry` | ADMIN/EDITOR | 重试失败项，返回新任务 `{jobId}` |

#### 配置（三处同步）

| `.env` 变量 | 默认 | 用途 |
|---|---|---|
| `NEWS_LIST_URL` | `https://cms-api.byd.com/es/search` | 列表接口 |
| `NEWS_DETAIL_BASE_URL` | `https://www.byd.com` | 详情页站点前缀 |
| `NEWS_TIMEOUT_MS` | `30000` | 采集读超时 |
| `NEWS_PAGE_SIZE` | `12` | 列表分页大小 |
| `NEWS_SYNC_ENABLED` | `false` | 定时增量开关 |
| `NEWS_SYNC_CRON` | `0 30 3 * * ?` | 定时 cron |
| `AI_RAG_NEWS_TOPK` | `4` | 新闻域生成注入块数上限（0=关闭 NEWS 注入） |

#### 验收清单

- [ ] AC1 全量历史（≈167 篇）+ 增量可抓取，正文抽取入库，按官方 id 幂等重跑不重复
- [ ] AC2 新闻作为独立知识域进入向量库，可被统一检索命中
- [ ] AC3 检索来源标注能区分 NEWS（`【官方新闻：…】`）
- [ ] AC4 手动与定时增量同步均可用
- [ ] AC5 图片型/无正文新闻不阻断流程（元数据入库，切块容错）
- [x] AC6 `mvn -q -DskipTests compile` 通过；单元测试 59 全绿（2026-09-11 implement 复验 ✓）；创作生成链路回归：2026-09-12 check 真机跑 `/deep/run`+`/deep/generate` 成功（无 500，rag 输出 `car=2 news=4`，NEWS 参与注入未挤占车型域；见下「候选窗口按域隔离」修复）

---

### 16. 知识中心浏览页（C3，正式规格，2026-09-12）

统一「知识中心」入口，以 Tab 组织「车型」「新闻」两类知识浏览。**问答不是 Tab**（C4 独立入口）。现有 `/car`、`/car/:id`、`/car/sync`、`/kb` 路由与页面**保留不改**（Tab 为聚合浏览入口，非替换）。

#### 前端路由

| 路由 | 组件 | 说明 |
|---|---|---|
| `/knowledge` | `views/KnowledgeCenter.vue` | 知识中心，`meta.auth`；`el-tabs` 仅「车型」「新闻」两个 Tab |

- 现有路由不变：`/car`、`/car/sync`、`/car/:id`、`/kb`。
- TopBar 新增「知识中心」导航项（登录后可见）；「车型库」「知识库」链接保留。

#### 组件与数据流

| 文件 | 职责 |
|---|---|
| `views/KnowledgeCenter.vue` | 页头 + `el-tabs`；Tab 面板 `v-if` 懒挂载（首访加载、切回保留状态） |
| `views/knowledge/CarKnowledgePanel.vue` | 车型 Tab：`carApi.list()` + 关键词/网络/状态筛选 + 卡片网格；缩略图取 `introImageUrls[0]`（**非 `introImages` 原始 id**）；点卡片跳 `/car/:id`；编辑器以上「同步车型」跳 `/car/sync` |
| `views/knowledge/NewsKnowledgePanel.vue` | 新闻 Tab：`newsApi.list({page,size,keyword})` 分页 + 详情抽屉（`newsApi.get`，正文 pre-wrap / 官方原文 `https://www.byd.com`+`url` / 切块数）；`tagNames` JSON 容错；`content` 空显示图片型提示；编辑器以上 FULL/INCREMENT 同步 + 轮询 |
| `api/index.js` | 新增 `newsApi`（list/get/createJob/getJob/listJobs/retryJob）与 `kbApi`（list/get/create/update/remove/rebuild） |

- **API 分层约定**：页面一律经 `src/api/index.js` 具名导出调用，禁止 `.vue` 直调 `http`；`KbLibrary.vue` 已由直调 `http` 规范为 `kbApi`（方法/路径/参数等价，行为不变）。
- **`http.js` 拆包**：响应拦截已 `return resp.data`，调用方拿到的即 `R<T>`，读 `res.code`/`res.data`；分页读 `res.data.rows`/`res.data.total`。
- **URL 解析**：相对路径（新闻 `imageUrl`/`url`）统一 `resolveUrl`——`http(s)://` 开头原样，否则补 `https://www.byd.com`。

#### 验收清单（2026-09-12 check 实测）

- [x] AC1 `/knowledge` 可访问，恰含「车型」「新闻」两个 Tab（`router/index.js` + `KnowledgeCenter.vue`）
- [x] AC2 车型 Tab 列表/筛选/卡片跳 `/car/:id` 可用，缩略图用 `introImageUrls`
- [x] AC3 新闻 Tab 列表分页（`PageResult`）/详情正文/原文链接可用
- [x] AC4 `/car`、`/kb` 等既有路由与页面未改动（`git diff` 核实；KbLibrary 仅机械换 `kbApi`，6 处调用等价）
- [x] AC5 `npm run build` 通过（exit 0）
- 既有缺陷（非 C3 引入，未修）：`CarLibrary.vue` 批量重建直调 `http` 但未 import（ReferenceError 隐患），待后续任务修复

---

### 17. 多轮对话式知识问答（C4，正式规格，2026-09-12）

> 独立入口 `/qa`（**非知识中心 Tab**）：多轮对话 + 来源引用。答案基于统一检索（CAR 车型 / NEWS 官方新闻 / KB 通用知识）
> 三域结果由 LLM 合成，同一会话可追问、上下文连贯。父任务：`09-11-knowledge-base-data-foundation`。
> 开关契约：浏览/问答**不受** `kb_enabled` 控制，仅生成注入可开关（AC4）。

#### 数据模型（schema.sql S12 区块，幂等 `CREATE TABLE IF NOT EXISTS`）

| 表 | 字段 | 说明 |
|---|---|---|
| `sparkora_qa_session` | id / title(≤200，首问摘要，可空) / created_by(归属用户) / created_at / updated_at / deleted | 会话；逻辑删除，仅本人可见 |
| `sparkora_qa_message` | id / session_id FK→sparkora_qa_session(id) / role(user/assistant) / content / citations(JSON) / rag_status(OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE) / created_at / image_refs(JSON，可空) | 消息保留（无逻辑删除）；citations 为 Citation 数组 `[{source,modelName,chunkType,score,chunkText,docId}]`，user 消息为空；image_refs 为 QaImageRef 数组，**仅 assistant 消息非空**，历史行为 NULL |

- 索引 `idx_qa_message_session(session_id)`。无向量表。
- `image_refs TEXT` 由「09-15 qa-auto-illustrate」段幂等补列（`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`），纯增量、存量行 NULL。

#### 多轮上下文策略（明确，不做摘要压缩）

- **检索 query 构造**：`query = 当前问题`；若当前问题 ≤12 字（疑似指代）或会话已有历史，则拼接最近 2 轮 user 问题 + 当前问题（截断 ≤300 字）作为检索文本。
- **送入 LLM 的历史窗口**：最近 `HISTORY_MAX_TURNS=6` 轮（12 条消息），单条 content 截断 2000 字，总历史 ≤12000 字，超出丢最旧。
- **知识上下文**：`RagResult.context` 作 system 附加段，仅 OK 时注入；非 OK 时 system 标注降级原因（LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE），让模型回答「知识库未覆盖」。
- **AI 扩展**：`AiClient.chatMessages(List<Map<String,String>> messages, int maxTokens)`（C4 新增，不破坏既有 `chat`/`chatJson` 签名）；`temperature`/`model` 同 `chat`。

#### 接口契约（`/api/qa`，全部 `R<T>` + 方法级 `@PreAuthorize`）

| 方法 | 路径 | 权限 | 入参 | 返回 |
|---|---|---|---|---|
| POST | `/api/qa/sessions` | ADMIN/EDITOR/VIEWER | `{title?}` | `R<QaSessionEntity>`（title 可空，首问回填） |
| GET | `/api/qa/sessions` | 三角色 | — | `R<List<QaSessionEntity>>`（仅本人，updated_at 倒序） |
| GET | `/api/qa/sessions/{id}` | 三角色 | — | `R<Map>`：`{session, messages[]}`（messages 按 id 升序；越权/不存在 `R.fail(404)`） |
| POST | `/api/qa/sessions/{id}/messages` | 三角色 | `{question}`（`@Valid`，≤2000 字） | `R<Map>`：`{userMessage, assistantMessage}`（assistant 含 citations/ragStatus/**imageRefs**）；越权 404 / AI 失败 500 |
| DELETE | `/api/qa/sessions/{id}` | 三角色 | — | `R<Void>`（逻辑删，仅本人；越权 404） |

- 会话归属：`created_by = 当前用户名`；越权/不存在统一 404（不泄露存在性）。
- 检索调用 `CarRagService.retrieveForGeneration(searchQuery, 8, null)`（锚点 null）；**不改** CAR/KB/NEWS 检索语义，**不读** `SettingService.kbEnabled`。

#### 答案配图（09-15 qa-auto-illustrate，子D，2026-09-17）

**语义**：答案随带相关图库缩略图（**只读附加展示**）。两条来源路径，合并去重后随 assistant 消息返回：

| 路径 | 触发条件 | 链路 |
|---|---|---|
| 新闻关联图（便宜路径，始终执行） | 答案引用含 `source=NEWS` 且该引用 `docId` 非空 | `Citation.docId`(= `sparkora_news_doc.id`) → `news_id`(内部 BIGINT) → `sparkora_news.id` → `cover_image_id` → `sparkora_image_asset` |
| 语义检索图（语义路径，仅图片意图） | 问题命中图片意图关键词 | 子B `ImageEmbeddingService.searchImages(cleanQuery, limit, AI_IMAGE_MIN_SCORE, null)` |

- **`Citation` / `UnifiedHit` 加可空 `docId`**（09-15 补读）：`searchTopKUnified` SQL 本已 `SELECT docId`（CAR=`car_doc.id` / KB=`kb_chunk.id` / NEWS=`news_doc.id`），此前读行时丢弃，现补读并透传（**含锚点 boost 重排分支**，漏传会让 id 静默丢失）。`Citation` **保留 5 参兼容构造器**（docId=null），`BriefService.citationsJson`（简报）与 `KnowledgeSearchTool`（深度检索）行为不变。
- **图片意图判定**（`QaImageIntent`，纯静态关键词，不用 LLM）：命中 `看图/看图片/看张图/看照片/图片/海报/照片/配图/给我看/我想看/看一下` 任一即触发语义检索；**非图片意图不调 `searchImages`**（无 embedding 浪费）。
- **合并去重**：按 `imageId` 去重，**新闻关联图优先**（与答案引用强相关），上限 `QaService.IMAGE_REF_MAX = 3`（常量，不配置化）。
- **降级（绝不阻断答案）**：`QaImageRefService` 各路径与 `QaService.ask` 调用处均包 try/catch，异常仅 warn；配图解析失败/为空 → `image_refs` 落 **null**，答案照常落库。
- **`image_refs` 结构**：`[{imageId, url, thumbUrl, title, newsId, source}]`（`QaImageRef` record）；新闻关联图 `title`=新闻标题、`newsId`=官方 news_id；语义图 `newsId`=null、`title`=嵌入原文首段或文件名。
- **只读契约（用户 09-17 决策）**：配图**直接随答案展示**，无批准流程、无候选态；本任务**不提供任何写入用户内容的路径**（不插入文章、不改答案文本）——与子C「写入正文必须用户批准」的风险模型不同（子C 改用户内容，子D 只多显示几张图）。
- **`ImageService.loadDerived(List<Long>)`**：新增**只读**批量方法（`selectBatchIds` + 复用既有 `fillDerived`），避免问答侧二次实现「storageKey→url / 七牛 thumbUrl」派生规则导致漂移。
- **错误矩阵**：无 NEWS 引用/`docId` 空 → 新闻关联图为空（不报错）；news 无 `cover_image_id` → 跳过；图库记录已删/无 `url` → 跳过；语义检索失败 → 该路空 + warn；配图整体异常 → `image_refs=null` + warn；历史消息 `image_refs` NULL → 前端不展示图片区（零回归）。
- **权限**：无新接口，沿用 `/api/qa` 三角色矩阵（问答读写三角色均可）。

#### 前端

| 文件 | 职责 |
|---|---|
| `views/QaChat.vue` | 左侧会话列表（新建/删除/切换）+ 右侧对话流 + 底部输入（Enter 发送 / Shift+Enter 换行）；assistant 气泡下 `CitationList`；**其下图片缩略图行**（横向滚动，`el-image` 预览大图，标注新闻标题/来源）；三态；移动端单列、触控 ≥44px |
| `views/project/deep/CitationList.vue` | 新增 `NEWS` 分支（`官方新闻` / `danger`），纯增量，不影响既有 CAR/KB/WEB/MULTI |
| `api/index.js` | `qaApi`（createSession/listSessions/getSession/ask/removeSession；ask 超时 120s） |
| `router/index.js` | `/qa`（`meta.auth`），紧随 `/knowledge` |
| `layouts/TopBar.vue` | 新增「知识问答」导航（登录可见） |

- 配图展示契约（09-15）：`imgRefsOf(m)` **兼容 `imageRefs` 为 JSON 字符串或数组**两种形态（后端存字符串，同 `citations` 惯例）；字段名以 `QaImageRef` record 为准（`imageId`，非实体 `id`）；`el-image` 缩略用 `thumbUrl || url`，**`preview-src-list` 必须用 `url`（原图）**——`thumbUrl` 是七牛 webp 派生，既有教训；`preview-teleported` + 移动端横向滚动、触控目标 ≥44px；历史消息无 `imageRefs` → `v-if` 不渲染。

#### 验收清单

- [x] AC1 可创建会话、提问、得到带来源引用的答案（2026-09-12 check 真机：`POST /qa/sessions` → `POST /qa/sessions/1/messages` code=0 / ragStatus=OK / assistant.citations 含 CAR+NEWS；`sparkora_qa_message` 落库）
- [x] AC2 同一会话多轮追问上下文连贯（2026-09-12 check 真机：首问「海狮08续航配置」→ 追问「那它的价格呢？」正确解析为海狮08价格并答出 DM-i/EV 价格区间，未串到「海豹08」）
- [x] AC3 答案可引用车型/新闻/通用 KB 三类来源并正确标注（2026-09-12 check 真机：KB 问「家用充电桩怎么选」citations 含 KB(`通用知识`)/CAR/NEWS；CitationList NEWS 分支=官方新闻/danger）
- [x] AC4 `kb_enabled=false` 时问答仍可用（2026-09-12 check 真机：setting `kbEnabled=false` 下提问仍 code=0/ragStatus=OK；`grep` 确认 QaService/QaController 无 SettingService/kb_enabled 引用）
- [x] AC5 `mvn -q -DskipTests compile`、`mvn test`（71 全绿）、`npm run build` 通过（2026-09-12 implement 复验）

> 09-15 qa-auto-illustrate（子D，2026-09-17）AC 见该任务 `prd.md`；实现/验证结论见 `.trellis/tasks/09-15-qa-auto-illustrate/`（归档后位于 `archive/2026-09/`）。
