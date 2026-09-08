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
POST  /api/projects/{id}/generate/brief    简报生成（S1 起真实 AI）  权限 ADMIN/EDITOR
GET   /api/projects/{id}/brief             取当前简报           权限 ADMIN/EDITOR/VIEWER
POST  /api/projects/{id}/generate/versions 多版本生成           权限 ADMIN/EDITOR
GET   /api/projects/{id}/versions          版本列表             权限 ADMIN/EDITOR/VIEWER
PUT   /api/projects/{id}/current-version   设定当前版本         权限 ADMIN/EDITOR
GET   /api/images              图库列表               权限 ADMIN/EDITOR/VIEWER
POST  /api/images/upload       上传图库图             权限 ADMIN/EDITOR
POST  /api/images/generate-text    文生图             权限 ADMIN/EDITOR
POST  /api/images/generate-from-image  图生图          权限 ADMIN/EDITOR
POST  /api/projects/{id}/images/{imageId}/cover   选封面   权限 ADMIN/EDITOR
POST  /api/projects/{id}/images/{imageId}/body     选/取消正文插图 权限 ADMIN/EDITOR
GET   /api/styles              风格库列表            权限 ADMIN/EDITOR/VIEWER
GET   /api/styles/{id}         风格详情              权限 ADMIN/EDITOR/VIEWER
POST  /api/styles              新建风格              权限 ADMIN/EDITOR
PUT   /api/styles/{id}         编辑风格              权限 ADMIN/EDITOR
DELETE /api/styles/{id}        删除风格              权限 ADMIN
POST  /api/styles/extract      样文提炼风格入库      权限 ADMIN/EDITOR
GET   /api/projects/{id}/publish-options   发布参数与通道状态  权限 ADMIN/EDITOR/VIEWER
POST  /api/projects/{id}/publish           发布公众号草稿箱    权限 ADMIN/EDITOR
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

### 3.3 接口契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects` | 三角色 | `page,size,topic,status,orderBy,orderDir` | `{rows[],total,page,size}` |
| GET | `/api/projects/{id}` | 三角色 | — | `{project}`（S1/S1b 起含 current_brief_id / current_version_id / last_*_error） |
| POST | `/api/projects` | ADMIN/EDITOR | §3.2 表单 JSON | `{id}` |
| PUT | `/api/projects/{id}` | ADMIN/EDITOR | 表单 JSON | `{ok:true}` |
| DELETE | `/api/projects/{ids}` | ADMIN | — | `{ok:true}` |
| POST | `/api/projects/{id}/generate/brief` | ADMIN/EDITOR | — | `{brief}`；失败 `R.fail(500)`；生成中重触发 `R.fail(409)`（HTTP 均为 200，前端必须检查 `code`） |
| GET | `/api/projects/{id}/brief` | 三角色 | — | `{brief}`（无则 `data:null`） |
| POST | `/api/projects/{id}/generate/versions` | ADMIN/EDITOR | `{styleIds:[...]}` | `{versions[]}`（仅本次新增，部分失败跳过并在 last_version_error 记录）；brief 未就绪 `R.fail(400)`；生成中重触发 `R.fail(409)` |
| GET | `/api/projects/{id}/versions` | 三角色 | — | `{versions[]}`（全量，按 id 升序） |
| PUT | `/api/projects/{id}/current-version` | ADMIN/EDITOR | `?versionId=` | `{ok:true}` |
| GET | `/api/styles` | 三角色 | `?enabledOnly=` | `{styles[]}` |
| POST | `/api/styles/extract` | ADMIN/EDITOR | `{name, sourceText}` | `{style}` |

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
- **VERSIONS_READY**：至少一版成功（`current_version_id` 默认指向本次第一版；全部失败才回退 READY）。**S6 起：版本就绪后直接可预览/发布**（配图已并入预览步骤，不再有 IMAGES_READY）。
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
- **思考深度（S9 增补，2026-09-05）**：创建表单含「思考深度」单选（`genDepth: FAST|DEEP`，默认 FAST，前端专用字段不随 create 提交）。FAST=创建后直发 `/generate/brief`；DEEP=创建后直发 `/deep/clarify`（研究计划+反问），两者均 120s 超时并发起，**立即跳详情页**，生成过程由详情页按 `project.status` 轮询展示（不再在创建页等待 1~2 分钟）。跳转携带意图参数 `?gen=FAST|DEEP`（仅存草稿也带，DEEP 时 StepBrief 展开深度面板），StepBrief 读取后即清除。

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

- `FAILED` 优先级高于其余状态：多车型检索时任一车型异常即标 `FAILED`（其余车型照常尝试）。
- 抛弃/失败**不得与「无命中」混淆**：`LOW_CONFIDENCE`/`FAILED` 必须显式落库，前端据此提示。

**字段级**：`sparkora_article_brief.rag_status`、`sparkora_article_version.rag_status` — `VARCHAR(20)`，可空（历史行为数据为 NULL，前端不展示）；GET brief/versions 响应自然携带该字段，无独立接口。

**知识引用明细（R3，2026-09-05 增补）**：`sparkora_article_brief.rag_citations`、`sparkora_article_version.rag_citations` — `TEXT`（JSON 数组 `[{source:"CAR|KB", modelName, chunkType, score, chunkText}]`），检索 OK 且有命中时随生成落库（与注入 prompt 的 context 同源，上限 24 条、单条文本截断 120 字符，序列化超 8000 字符整体置 null）；`LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE` 为 null。前端简报页「知识库引用」区（`CitationList` 组件）与版本卡片「引用 N」标签（点击展开）展示；空态按 ragStatus 显示降级文案。**WEB 搜索来源并入（2026-09-05 增补）**：深度模式简报页的引用面板另将 `brief.fact_sheet.entries` 中条目派生为引用条目并入展示——**全部类型（KB/WEB/MULTI，2026-09-06 修订）**：KB 条目（置信 0.9/0.6）与本地 `rag_citations` 同款「通用知识」标签展示（修复「深度模式内容引用了知识库、页面却显示未引用」的展示断链，项目 29 实测）；WEB 带域名、MULTI 标多源交叉；上限 24 条。快速模式无 fact_sheet，行为不变。版本卡片保持「本版生成时的本地知识库检索」语义，不重复展示 WEB 引用。

**检索门槛**（粗调值，**待按真实 query 分数分布校准**；`REJECT` 须 ≥ `MIN`）：

| `.env` 变量 | 默认 | 代码用途 |
|---|---|---|
| `AI_RAG_MIN_SCORE` | `0.3` | 逐块相似度门槛，低于不注入（沿用 S6 原硬编码值） |
| `AI_RAG_REJECT_SCORE` | `0.5` | 整体置信度门槛：全部命中块的最高相似度低于该值 → `LOW_CONFIDENCE` 全部抛弃 |
| `AI_RAG_KB_TOPK` | `4` | 通用知识库生成检索注入块数上限（与车型域配额独立；§6c） |
| `AI_RAG_KB_ENABLED` | `true` | 通用知识库总开关，false 时统一检索排除 KB 块（§6c） |
| `AI_RAG_ANCHOR_BOOST` | `1.15` | 统一检索锚点车型块分数加权系数（§6c S8） |

**诚实边界**：相似度衡量**相关性**而非事实正确性——知识库本身存错的数据会以高相似度被当作权威注入；防错依赖入库源头（比亚迪同步 + 人工清洗），检索门槛不承诺拦截知识库错误数据。

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
| `sparkora_kb_chunk_embedding` | id / chunk_id FK / embedding vector(1024) / created_at | 向量;ivfflat cosine lists=100(与 car_doc_embedding 同参) |

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
| 锚点加权 | 项目关联车型降为**写作锚点**：CAR 块 modelId∈anchor → score × `AI_RAG_ANCHOR_BOOST`(默认 1.15，上限 1.0 截断)重排；前端项目编辑页改「写作锚点车型」文案 |
| 配额 | 核心块(PARAM_GROUP/MODEL_INFO)优先、RIGHTS/FEATURE ≤1/3、KB_CHUNK 独立配额 `AI_RAG_KB_TOPK`；`AI_RAG_KB_ENABLED=false` 时 KB 块在配额层排除（等价 S6 行为，检索仍跑） |
| 来源标注 | 行内前缀「【车型数据：名称】」/「【通用知识：标题】」；首行「知识来源：…」按命中构成生成 |
| 子查询 | S6.2 参数级子查询保留，子查询同走统一检索 |
| 状态判定 | 检索异常（单路统一检索）→ FAILED；rawHit==0 → NO_KNOWLEDGE；maxScore<reject → LOW_CONFIDENCE；其余 OK（S6.1 四态语义不变） |
| 覆盖度声明 | coveredText 仅统计 CAR 域 PARAM_GROUP 块 |

**前端**：`/kb` 知识库页（列表卡片/新建编辑抽屉/删除确认/重建向量含失败提示；移动端单列），TopBar「知识库」入口。

**配置**：`AI_RAG_KB_TOPK`(默认 4) / `AI_RAG_KB_ENABLED`(默认 true)，见 §9 配置表。

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
| source | String(20) | `upload` / `ai-text2img` / `ai-img2img` / `byd` |
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

- 非持久化字段新增：`thumbUrl`（七牛 imageView2/2/w/360/format/webp 派生；非七牛实现降级为 url）、`dedupeHit`（Boolean，去重命中标记）。
- **去重管线（四来源统一）**：upload / 文生图 / 图生图 / BYD 同步入库均走 `ImageService.persistOrReuse`（算哈希→查命中→复用或上传图床）。BYD 额外收益：车型同步幂等重跑不重复占图床对象。并发同哈希双写容忍（先查后插，竞态窗口最多多传一份对象）。

### 版本-图片关联（挂版本，不挂项目）

`sparkora_article_version` 增列（幂等 ALTER）：

| 字段 | 类型 | 说明 |
|---|---|---|
| cover_image_id | BIGINT | 该版本封面（sparkora_image_asset.id，可空；每版本一张） |
| body_image_ids | String(1000) | 正文插图 id 列表（逗号分隔，有序） |

> 理由：多版本各有排版，预览/发布按「当前版本」取图；项目级关联无法表达版本间差异。

### 配图 API（全部 `R<T>` 包装；HTTP 200；S10 起检索/生成契约升级）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images` | 三角色 | `?projectId=&source=&keyword=&page=1&size=24` 组合查询（source 白名单 upload/ai-text2img/ai-img2img/byd，非法值 400；keyword 命中 file_name/prompt_text，ILIKE） | `PageResult`：`{rows[], total, page, size}`；rows 内每条含 url + thumbUrl。**S10 起不再返回全量列表** |
| POST | `/api/images/upload` | ADMIN/EDITOR | multipart `file` + `projectId?`（可空=全局图库） | `{image}`（含 `dedupeHit`：内容哈希命中已有记录时 true，不重复传图床）；类型限 png/jpg/webp，≤10MB（`IMAGE_MAX_UPLOAD_MB`），超限 `R.fail(400)` |
| DELETE | `/api/images/{id}` | ADMIN/EDITOR | — | `{ok:true}`；被封面/插图引用时 `R.fail(400, 提示引用方)`；删记录+图床对象 |
| POST | `/api/images/generate-text` | ADMIN/EDITOR | `{projectId?, prompt, size?, n?}`（`@Valid` DTO；n 1~4 默认 1） | **S10 起响应为数组** `{images[]}`：n 张候选逐张入库（后端循环 n 次单张调用，单张失败跳过，全部失败 `R.fail(500)` 含候选模型错误明细）；每张含 genModel/genSize/dedupeHit |
| POST | `/api/images/generate-from-image` | ADMIN/EDITOR | `{projectId?, refImageId, prompt, size?, n?}`（`@Valid` DTO） | **S10 起响应为数组** `{images[]}`（同上）；provider 不支持 edits 时 `R.fail(500, 明确提示)` |
| POST | `/api/images/{id}/regenerate` | ADMIN/EDITOR | —（S10 新增） | `{images[]}`（1 张）：用源图 prompt/gen_size 重新生成**新图**（不覆盖源图）。源图须 source∈{ai-text2img,ai-img2img} 且 prompt 非空，img2img 复用源图 ref_image_id（参考图已删则 400） |
| GET | `/api/projects/{id}/images` | 三角色 | — | `{images[], coverImageId, bodyImageIds[], coverImage?, bodyImages[]}`。**S10 语义改写**：`images` 从全量图库收缩为**当前版本引用的图**（封面+插图）；新增服务端解析的 `coverImage`（对象含 url）/`bodyImages`（按 bodyImageIds 顺序）。全量图库浏览改走 `GET /api/images` 分页接口 |
| POST | `/api/projects/{id}/images/{imageId}/cover` | ADMIN/EDITOR | — | `{ok:true}`（version.cover_image_id）；重复选同一张幂等 |
| POST | `/api/projects/{id}/images/{imageId}/body` | ADMIN/EDITOR | `?action=add/remove` | `{ok:true}`（增删 version.body_image_ids）；重复添加幂等 |

- 图片访问：**图床公网 URL**（`url` 字段，由 `storage_key` 实时拼）。`/images/**` 静态映射已删除（S6 本地不留）。
- **缩略图交付（S10）**：列表/网格用 `thumbUrl`（七牛 imageView2/2/w/360/format/webp，交付层转换零转码成本）；大图预览、正文插入、wenyan 拉图、公众号发布均用原图 `url`。非七牛图床实现降级 thumbUrl=url（`ObjectProvider` 可选注入，`ImageStorage` 接口不掺七牛特性）。
- 文生图/图生图返回的 axonhub URL **必须转存图床**（临时 URL 会过期），转存失败则该次生成报错（不留死链）。
- 请求体数字字段（projectId/refImageId）统一健壮解析：兼容数字与字符串形式（前端路由参数为字符串）。
- **S6 起 `complete-images` 接口已删除**（配图并入预览，不再有「完成配图」状态推进）。

### 页面职责（2026-08-30 调整；2026-09-03 S6 配图并入预览；2026-09-06 S10 检索/生成升级）

- **图库独立页 `/images`**（`ImageLibrary.vue`，TopBar 入口）：上传、浏览、删除（ADMIN/EDITOR）。**S10 起**：筛选（来源下拉/关键字 300ms 防抖/项目）全部走服务端分页接口（size=24，el-pagination 翻页）；网格缩略图走 thumbUrl（imageView2/webp），点开大图预览用原图；上传内容哈希命中时提示「复用」；AI 来源图卡展示 gen_model/gen_size 并提供**一键重生成**；**AI 生图抽屉**（文生图/图生图，EDITOR 及以上；图生图从当前列表选参考图；n(1/2/4) 张候选生成，projectId 传空=全局图库，产物即进图库）。素材管理归图库，不在文章流程内。
- **预览步配图面板（项目向导 Step3 并入 Step4）**：工具栏「配图」面板提供**图库插入**（**S10 起走分页接口 + 来源/关键字筛选 + 触底加载**，选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，**S10 起可一次生成 n(1/2/4) 张候选，逐张插入/设封面/重生成**；产物进图库后展示候选列表）两种来源。图不够时引导去图库页。车型库图片接入**预留**（暂不开发）。

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
- **配图组装规则（2026-09-01 定稿，预览与发布同参）**：`buildMarkdown`/前端 `buildFullMd` 统一组装为 frontmatter(`title`+有封面时 `cover: <图URL>`，**含闭合 `---`**) + 正文；**插图落点完全由正文 markdown 引用决定**——正文中引用了哪张图（图床公网 URL）、出现在哪里，就是最终文章的落点；未被正文引用的选定插图**不自动追加文末**（预览与发布同规则，所见即所得）。`cover` 仅进公众号草稿封面元信息，不在正文渲染——正文里看不到封面图属预期。
- **插图落点（2026-09-01 交互定稿）**：预览页工具栏「插图」面板按选定顺序列出已选插图，点击即以 markdown 图片语法插入编辑器光标处（左栏 md 可见可编辑，正文已引用的在面板内标绿 ✓）；正文里没引用的插图不会出现在文章中（不自动追加文末），口径在面板内明示。
- 删除图：`ImageService.delete` 落库删除 + 图床对象（非阻塞，失败仅 warn）。
- 降级链：wenyan CLI 不可达/超时/失败 → 简化保底渲染（degraded=true + 中文原因）；主题名白名单防 CLI 参数注入；CLI 超时 `WENYAN_RENDER_TIMEOUT_MS`（默认 30s）。

### 数据模型增量（幂等 ALTER）

| 表.列 | 类型 | 说明 |
|---|---|---|
| sparkora_image_asset.storage_key | VARCHAR(300) | 图床 key（**入库即转存，非空**；原 qiniu_key 语义通用化） |
| sparkora_article_project.publish_media_id | VARCHAR(128) | S5 公众号草稿箱 media_id |
| sparkora_article_project.publish_theme | VARCHAR(64) | 发布所用主题 |
| sparkora_article_project.published_at | TIMESTAMP | 发布时间 |
| sparkora_article_project.last_publish_error | VARCHAR(1000) | 最近一次发布失败原因 |

### 接口契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images/preview-options` | 三角色 | — | `{themes[], highlights[], defaultTheme, highlight, macStyle, footnote}`（读 `.env` WENYAN_* 配置，前端下拉同源） |
| POST | `/api/projects/{id}/preview` | 三角色 | `?theme=&highlight=&macStyle=&footnote=`（query） | `{html, theme, highlight, macStyle, footnote, degraded, degradedReason?}`；业务失败 HTTP 200 + `R.fail`，未知主题 `R.fail(400)`（白名单防 CLI 参数注入）；前置未就绪 `R.fail(400)` |

- **依赖顺序**：项目状态 VERSIONS_READY 才可预览（`POST /preview` 校验，否则 `R.fail(400)`）；
- 七牛配置开关 `QINIU_ENABLED`（AK/SK 兼容旧裸名 `${AK}` `${SK}` 回退）：关闭时 `preview` 直接 `R.fail("图床未配置…")`；已配置但上传失败 `R.fail(500,"图床上传失败: …")`。
- 状态推进：预览不改变项目状态。
- 发布(S5)：与预览同参同源渲染,preview HTML = 发布真值;字段级契约与验收状态见 §12。

### 前端

- `StepPreview.vue`（`/projects/:id/preview` 子路由，步骤三）：`preview-options` 下拉/开关控件读后端配置；iframe `srcdoc` 顶部标注「排版引擎:文颜(与发布同源)」；degraded=true 顶部黄条；移动端适配。
- 四步流程 `maxReachableStepOf` 扩到 `index=3`（发布），发布步对 VERSIONS_READY/PUBLISHED_DRAFT 解锁。
- 配图并入预览：工具栏「配图」面板提供图库插入 + AI 生图（文生图/图生图，产物进图库后插入正文）两种来源；封面走 frontmatter `cover` 元信息。

### 已知限制与风险（登记)

- `pic.caiqz.cn` 仅有 http（https 证书未配）：预览从 localhost 拉不成问题；公众号内显示的是微信端上传后的 URL，不受影响。后续可加 https。
- wenyan-server 2.0.11 鉴权中间件对错误 key 挂起（不返回 401）：客户端超时不宜过长，且建议 server 升级。
- theme 清单仅能在 server 端注册(wenyan theme 命令)，server 2.0.11 未提供 HTTP 查询，清单以 `.env` WENYAN_THEME_NAMES 为准。

### 12. 公众号草稿发布模块（S5，正式规格）

> 2026-09-01 定稿,方案 A 发布侧:与预览同渲染核,**preview HTML = 发布真值**。
> 2026-09-01 实测勘误(@wenyan-md/cli 2.0.11):`/verify` 为 **GET** 探针;`/upload` multipart 字段名 `file`(限 md/css/json/图片,≤10MB);`/publish` 收 **JSON `{fileId, appId?}`**(fileId 须为上传的 .json);当前部署无效 key 即刻 401。上传文件 TTL 10 分钟。

#### 发布链路（同步,一次调用完成）

```
PublishService.publish
 → PreviewService.preview(同参同源:状态校验 + 取图床 URL + frontmatter + wenyan render)
 → 非 degraded 校验(降级 HTML 不进公众号)
 → gzhContent JSON { title(≤64,必填), content=渲染HTML, cover=封面图床URL? }   ← asset:// 不用,图片全为图床 http URL;cover 与预览 frontmatter 同源,缺失时 server 退化用正文首图当封面
 → wenyan-server POST /upload (multipart file=.json) → fileId
 → wenyan-server POST /publish (JSON {fileId}) → {media_id}
 → 原子落库 status=PUBLISHED_DRAFT + publish_media_id/publish_theme/published_at,清 last_publish_error
```

- 封面与正文 `<img src="http(s)…">` 由 server 端 fetch 后转传微信(七牛 http URL 可用);无封面时 gzhContent 不带 cover,草稿封面由 server 退化取正文首图(可能无封面图,不阻塞发布)。
- 失败语义:任何一步失败 → `last_publish_error` 落库、状态原样保留、`R.fail(400|500, 中文原因)`;可重试整链。

#### 接口契约(全部 `R<T>` 包装;HTTP 200)

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects/{id}/publish-options` | 三角色 | — | `{themes[], highlights[], defaultTheme, highlight, macStyle, footnote, publishEnabled, publishConfigOk, publishDisabledReason?, wenyanServer, publishMediaId?, publishTheme?, publishedAt?, lastPublishError?}`(探针失败不阻塞页面) |
| POST | `/api/projects/{id}/publish` | ADMIN/EDITOR | `?theme=&highlight=&macStyle=&footnote=`(query,与 preview 同形) | 成功 `{mediaId, theme, publishedAt}`;前置不满足/渲染参数非法/通道未配置 `R.fail(400)`;链路失败 `R.fail(500)`;失败均回写 last_publish_error |

- 配置:`WENYAN_MCP_SERVER_URL`(带 scheme)/`WENYAN_MCP_SERVER_API_KEY`/`WENYAN_MCP_PUBLISH_TIMEOUT_MS`(默认 30s);未配置时 publish-options 返回 publishEnabled=false + 中文原因,publish 返回 `R.fail(400)`。
- 前端:`StepPublish.vue`(第 5 步子路由 `/projects/:id/publish`):摘要(标题/封面缩略/插图数)+ 参数表单(与预览同源)+ 发布确认弹层 + 成功态(mediaId/时间/重发)+ 失败黄条;viewer 只读;`maxReachableStepOf` 放开到 index=4,`StepPreview` 状态判断修正为 PUBLISHED_DRAFT 并加「去发布」衔接。

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

- `sparkora_article_brief` 增列：`gen_mode TEXT DEFAULT 'FAST'`、`clarify_questions TEXT`、`clarify_answers TEXT`、`research_plan TEXT`、`research_notes TEXT`、`fact_sheet TEXT`、`rag_citations TEXT`（R3 知识引用明细）。
- `sparkora_article_version` 增列：`fact_risks TEXT`（数值回查结果，JSON 数组 `[{claim,riskLevel,suggestion}]`）、`rag_citations TEXT`（R3 知识引用明细）。

#### 接口契约（全部 `R<T>` 包装；方法级 `@PreAuthorize`；前缀 `/api/projects/{projectId}/deep`）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/deep/clarify` | ADMIN/EDITOR | `{topic(必填), extraInfo?}` | `{briefId, researchPlan, questions}`；新建 brief(gen_mode=DEEP) |
| POST | `/deep/clarify-answer` | ADMIN/EDITOR | `{briefId, answers:{问题:答案}}` | `{briefId, locked}`（锁定 JSON 落库） |
| POST | `/deep/run` | ADMIN/EDITOR | `{briefId}` | `{briefId, agents, done}`（同步阻塞；前端轮询 status） |
| POST | `/deep/generate` | ADMIN/EDITOR | `{briefId, stylePrompt?}` | `{versionId}`（版本 fact_risks 落库） |
| POST | `/deep/brief` | ADMIN/EDITOR | `{briefId}` | `ArticleBriefEntity`（基于事实手册生成简报，落同一条 DEEP brief 行并推状态机到 READY；研究完成后自动触发一次，此处为手动重试入口；409=状态冲突） |
| GET | `/deep/status` | 三角色 | `?briefId`(缺省取最新 DEEP brief) | `{briefId, genMode, stage, researchPlan?, questions?, answers?, agents?, factSheet?, toolHealth:{KB,SEARXNG,TAVILY}}` |

- stage 判定（brief 层展示态）：`RESEARCH_DONE`（fact_sheet 非空）> `RESEARCHING`（research_notes 非空）> `CLARIFIED`（answers 非空）> `CLARIFYING`（questions 非空）> `NONE`。
- toolHealth：KB 恒 true；SEARXNG/TAVILY 为惰性状态（`lastCallHadResults`/`lastOk`，初值乐观，调用失败自动降 false），并受 `SEARCH_WEB_ENABLED` 门禁。
- 权限冒烟：viewer 访问写接口 403（`hasAnyRole('ADMIN','EDITOR')`）。

#### 工具层（SearchTool 抽象，`com.sparkora.deep.tool`）

| 工具 | 实现 | 来源 | 降级语义 |
|---|---|---|---|
| KB | `KnowledgeSearchTool` | 委托 `CarRagService.retrieveForGeneration` 统一检索（§6c，S8） | 异常 warn，不抛出 |
| SEARXNG | `SearxngSearchTool` | GET `{SEARXNG_BASE_URL}/search?q=&format=json&language=zh-CN` | 超时/空结果静默空列表 + lastCallHadResults=false；不重试 |
| TAVILY | `TavilySearchTool` | POST `api.tavily.com/search` `{api_key,query,max_results,search_depth}` | 密钥未配置/失败 → available()=false |
- WEB 选择顺序：SEARXNG → Tavily（拿到结果即止）；每子代理 webQuota=`max(1, 8/n)`，`SEARCH_WEB_ENABLED=false` 时为 0（纯 KB）。
- **KB 锚点感知检索（R1，2026-09-06）**：`KnowledgeSearchTool.search(query, maxResults, anchors)` 委托 `retrieveForGeneration`（锚点加权 + 参数级子查询 + 核心块/权益块分层配额）；锚点由 `DeepResearchService.resolveAnchors` 解析（项目关联车型为准 → `CarModelMatcherService` 按主题识别兜底，失败不阻断）；子代理 KB 检索 query 用「主题 + 问题」复合语料（纯问题如「价格对比」缺车型上下文相似度必散）。非 OK 状态返回空列表归 gaps（行为同旧）。
- **WEB gap 驱动（R1 同批）**：KB 已命中车型域权威块（命中含 MODEL_INFO/价格区间文本）时跳过 WEB 补查——WEB 只补 KB 缺口，不与 KB 平行全问题重搜、不得覆盖 KB 结论。
- **同 claim 冲突裁决（R2，2026-09-06）**：`FactSheetService.merge` 聚合时同 claim 同时含 KB 与 WEB 来源 → **KB 胜出**（不比较相似度/置信度，量纲不同不可比；按来源身份定优先级：本系统知识库（比亚迪同步清洗）> 外部 WEB）。WEB 条目降级为该条目 `alternatives`（URL 列表）留证据，并写 warnings「以知识库为准；外部来源(N 条)有异说,未采用」。纯 KB / 纯 WEB 条目维持原置信规则（KB 0.9 / 多源交叉 0.85 / 单一 WEB 0.4 + 待核实）。
- `SearchHit.web(type=工具名→展示源)`：type 统一为 `WEB`（计数依据），工具名记 modelName 字段。
- 密钥链：`DEEP_TAVILY_API_KEY`(System property/env) → `TAVILY_API_KEY` → `sparkora.ai.deep.tavily-api-key`（dotenv 注入 System property，嵌套占位符 `${A:${B:}}` Spring 不支持，故 yml 只挂 `TAVILY_API_KEY`）。

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
- StepBrief.vue：模式切换（FAST/DEEP）→ deepStage 流转 NONE→CLARIFYING→CLARIFIED→RESEARCHING→RESEARCH_DONE → 生成；onMounted 断点恢复；`onBackFast` 退回快速模式。CLARIFYING/RESEARCHING 仅 brief 展示态，项目状态机不变（constants/project.js 注释）。RESEARCH_DONE 态下简报正常展示（自动简报完成即 READY）；失败显示「重新生成简报」+「跳过简报,直接生成正文」。
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
