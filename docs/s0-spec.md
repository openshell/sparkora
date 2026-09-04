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
| GET | `/api/projects` | 三角色 | `page,size,topic,status` | `{rows[],total,page,size}` |
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

**检索门槛**（粗调值，**待按真实 query 分数分布校准**；`REJECT` 须 ≥ `MIN`）：

| `.env` 变量 | 默认 | 代码用途 |
|---|---|---|
| `AI_RAG_MIN_SCORE` | `0.3` | 逐块相似度门槛，低于不注入（沿用 S6 原硬编码值） |
| `AI_RAG_REJECT_SCORE` | `0.5` | 整体置信度门槛：全部命中块的最高相似度低于该值 → `LOW_CONFIDENCE` 全部抛弃 |

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
| 生效前提 | 切块口径变更**仅对新重建的车型生效**;存量 56 车型需逐个重建向量(体检发现 408/1293 块历史向量缺失,重建一并补齐) |

体检报告（量化）见 `.trellis/tasks/09-04-kb-clean-audit/research/clean-audit-report.md`：规则引擎覆盖 98.8%+（口径可信度受旧误标影响，重清洗后复测）；AI 兜底 9 行中 4 行「无值清成空串」属错误输出（P2 建议：AI 返回空值视为失败不落库）；**31.6% 文档块无向量（历史静默丢失）**；单车型 39 清洗行 6432（占 60%）疑似多版本摊平，待核查。

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

### 数据模型（`sparkora_image_asset`，S3b 新表）

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

### 版本-图片关联（挂版本，不挂项目）

`sparkora_article_version` 增列（幂等 ALTER）：

| 字段 | 类型 | 说明 |
|---|---|---|
| cover_image_id | BIGINT | 该版本封面（sparkora_image_asset.id，可空；每版本一张） |
| body_image_ids | String(1000) | 正文插图 id 列表（逗号分隔，有序） |

> 理由：多版本各有排版，预览/发布按「当前版本」取图；项目级关联无法表达版本间差异。

### 配图 API（全部 `R<T>` 包装；HTTP 200）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images` | 三角色 | `?projectId=` 过滤 | `{images[]}`（含 id,fileName,source,promptText,width,height,storageKey,url,createdAt） |
| POST | `/api/images/upload` | ADMIN/EDITOR | multipart `file` + `projectId?`（可空=全局图库） | `{image}`；类型限 png/jpg/webp，≤10MB（Spring multipart 限制同步 `IMAGE_MAX_UPLOAD_MB`），超限 `R.fail(400)` |
| DELETE | `/api/images/{id}` | ADMIN/EDITOR | — | `{ok:true}`；被封面/插图引用时 `R.fail(400, 提示引用方)`；删记录+图床对象 |
| POST | `/api/images/generate-text` | ADMIN/EDITOR | `{projectId?, prompt, size?}` | `{image}`；AI 失败 `R.fail(500)` 含候选模型错误明细 |
| POST | `/api/images/generate-from-image` | ADMIN/EDITOR | `{projectId?, refImageId, prompt, size?}` | `{image}`；provider 不支持 edits 时 `R.fail(500, 明确提示)` |
| GET | `/api/projects/{id}/images` | 三角色 | — | `{images[], coverImageId, bodyImageIds[]}`（当前版本配图快照；images 为**全量图库**——含全局图，与配图选用口径一致） |
| POST | `/api/projects/{id}/images/{imageId}/cover` | ADMIN/EDITOR | — | `{ok:true}`（version.cover_image_id）；重复选同一张幂等 |
| POST | `/api/projects/{id}/images/{imageId}/body` | ADMIN/EDITOR | `?action=add/remove` | `{ok:true}`（增删 version.body_image_ids）；重复添加幂等 |

- 图片访问：**图床公网 URL**（`url` 字段，由 `storage_key` 实时拼）。`/images/**` 静态映射已删除（S6 本地不留）。
- 文生图/图生图返回的 axonhub URL **必须转存图床**（临时 URL 会过期），转存失败则该次生成报错（不留死链）。
- 请求体数字字段（projectId/refImageId）统一健壮解析：兼容数字与字符串形式（前端路由参数为字符串）。
- **S6 起 `complete-images` 接口已删除**（配图并入预览，不再有「完成配图」状态推进）。

### 页面职责（2026-08-30 调整；2026-09-03 S6 配图并入预览）

- **图库独立页 `/images`**（`ImageLibrary.vue`，TopBar 入口）：上传、浏览、按项目过滤、删除（ADMIN/EDITOR）。素材管理归图库，不在文章流程内。
- **预览步配图面板（项目向导 Step3 并入 Step4）**：工具栏「配图」面板提供**图库插入**（全量图库选图插入正文光标处/设封面）与 **AI 生图**（文生图/图生图，产物进图库后插入正文）两种来源。图不够时引导去图库页。车型库图片接入**预留**（暂不开发）。

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