# Sparkora · S0 可审规格（屏幕清单 + 字段级 + 接口契约 + 状态机）

**范围**：S0 项目骨架 + Spring Security 登录 + 流程化工作台（项目列表）+ 新建创作任务 + 项目详情（生成入口占位）。
**当前进度**：S0~S2a 已实现（登录、项目 CRUD、简报生成、多版本正文、风格库）；本文档 §1/§3/§4/§6 已同步更新至 S2a 现状（2026-08-27 核对）。
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

后端 API（/api 前缀，Spring Security；接口清单截至 S2a）
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
GET   /api/styles              风格库列表            权限 ADMIN/EDITOR/VIEWER
GET   /api/styles/{id}         风格详情              权限 ADMIN/EDITOR/VIEWER
POST  /api/styles              新建风格              权限 ADMIN/EDITOR
PUT   /api/styles/{id}         编辑风格              权限 ADMIN/EDITOR
DELETE /api/styles/{id}        删除风格              权限 ADMIN
POST  /api/styles/extract      样文提炼风格入库      权限 ADMIN/EDITOR
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

> 所有响应统一 `R<T>` = `{code, msg, data}`，`code=0` 成功。**注意：业务失败（含登录失败、生成失败）均为 HTTP 200 + `R.fail`，前端不能只依赖 axios 错误拦截器，必须检查 `code`**（S2a 已在 store.login / ProjectEdit / StepBrief / StepVersions 逐处落实）。

---

## 4. 状态机（ArticleProject.status）

S1/S1b 起共 5 态（S0 原为两态，已扩展；实现见 BriefService / VersionService）：

```
DRAFT ──(生成简报)──▶ GENERATING_BRIEF ──(落库 brief)──▶ READY
              ▲                    │
              └────(失败回退+写 last_brief_error)
READY ──(多版本生成)──▶ GENERATING_VERSIONS ──(落库版本+默认选第一版)──▶ VERSIONS_READY
              ▲                       │
              └────(失败回退+写 last_version_error；部分成功也进 VERSIONS_READY 并记录明细)
（S3 起展开：VERSIONS_READY→FACT_CHECK→IMAGES→PREVIEW→PUBLISHED）
```

- **DRAFT**：刚创建（`add` 即 DRAFT）；简报生成失败也回退到此态。
- **GENERATING_BRIEF**：简报生成进行中（先落库再调 AI，前端可观察；再次触发返回 409）。
- **READY**：简报就绪（`current_brief_id` 指向最新简报；S0 语义「记录创建成功」已由 S1 取代）。
- **GENERATING_VERSIONS**：多版本生成进行中（每风格一版；再次触发返回 409）。
- **VERSIONS_READY**：至少一版成功（`current_version_id` 默认指向本次第一版；全部失败才回退 READY）。
- 前端状态映射唯一事实源：`frontend/src/constants/project.js`（文案/标签色/步骤推进/生成中判定）。

---

## 5. 新建创作任务 `/projects/new`

- 表单 = §3.2 中「表单」列字段，字段级校验：`topic` 必填、长度限制。
- 操作：保存（DRAFT）或「创建并生成 Brief →」（DRAFT→GENERATING_BRIEF→READY，S0 只落库）。
- 校验错误逐字段 `el-form` 提示，后端 `@Validated` 兜底。

---

## 6. 项目详情 / 生成入口占位 `/projects/:id`

- 顶部六步步骤条（brief→版本→校验→配图→预览→发布）；S2a 已实现 brief / versions 两步为子路由（`ProjectLayout.vue` 外层步骤导航 + `Step*.vue` 子路由），「校验」起为 S3 占位置灰（未解锁上锁不可点）。
- 步骤推进与可达范围由 `frontend/src/constants/project.js` 依据 `project.status` 计算；生成中停留当前步骤。
- 「生成简报」→ `POST /api/projects/{id}/generate/brief`（S1 起真实 AI，同步调用，前端 loading + 以 project.status 为事实源轮询恢复）。

---

## 7. 已定决策（S0 落地依据）

- **会话方式**：Spring Security + **JWT**（`JWT_SECRET` / `JWT_EXPIRE_MINUTES` 从 `.env` 读）。
- **数据库**：**PostgreSQL**，连接参数从 `.env` 的 `SPARKORA_DB_*` 读取（host/port/name/user/password）；schema 初始化脚本幂等可重复。
- **前端工程位置**：`/dockerData/code/sparkora/frontend/`，单独 Vue3 + Element Plus 工程。
- **模型入口**：**axonhub 统一入口 `https://axo.caiqz.cn`**（OpenAI 兼容），`AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL` 从 `.env` 读；S0 不调 AI，但骨架预留 `AiClient` 配置读取位。
- **wenyan-mcp**：默认 `WENYAN_MCP_ENABLED=false`（S4 再启用）。
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
| `AI_IMAGE_MODEL` | 文生图 / **图生图**（axonhub） | ⏸ S3b 预留 |
| `WECHAT_*` | 公众号草稿发布 | ⏸ S5 启用 |
| `WENYAN_MCP_*` | wenyan 预览/发布 | ⏸ S4 启用，默认 disabled |
| `SEARXNG_*` / `CRAWL4AI_*` | 搜索/抓取素材 | ⏸ 链接抓取阶段启用 |

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

## 10. 配图模块（S3b，本节为 S0 之后的预留设计）

文章配图支持**三种来源**：

| 来源 | 说明 | 接口形态（axonhub / OpenAI 兼容） |
|---|---|---|
| 图库选图 | 用户上传/选图，统一风格管理 | 不调 AI |
| 文生图 | prompt → 生成封面/插图 | `images/generations` |
| **图生图** | 上传参考图 + prompt → 基于参考图生成 | `images/edits`（传参考图） |

**待确认**：axonhub 是否支持 `images/edits`（图生图）。若不支持，图生图改走备用 provider 或后置。

### 数据模型扩展（`sparkora_image_asset`，S3b 建表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | Long | 主键 |
| workspace_id | Long | 工作空间 |
| file_name | String | 文件名 |
| storage_url | String | 存储地址 |
| source | String(20) | `upload` / `ai-text2img` / `ai-img2img` |
| prompt_text | String | 生成 prompt（AI 来源时） |
| ref_image_id | Long | **图生图**的参考图 id（自引用，可空） |
| style_json | String | 风格/尺寸配置 |
| width / height | Integer | 尺寸 |
| created_at | Datetime | 创建时间 |

### 配图 API（S3b，S0 仅预留接口位不实现）

- `POST /api/images/upload` 上传图库图
- `POST /api/images/generate-text` 文生图（axonhub `images/generations`）
- `POST /api/images/generate-from-image` **图生图**（axonhub `images/edits`，传参考图）
- `GET /api/images` 图库列表
- `POST /api/projects/{id}/articles/{verId}/cover` 选封面
- `POST /api/projects/{id}/articles/{verId}/body-images` 选正文插图