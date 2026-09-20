# 系统总纲（路由 / 权限 / 状态机 / 配置 / 决策）

> 回链：[系统说明总览](../README.md)

本文件承载跨模块的系统级契约：自建 vs 复用总览、路由与权限结构、登录、项目状态机、已定决策、配置来源、交付物，以及统一约定（`R<T>`、错误矩阵、角色）。

---

## 0. 规格范围与修订历史

- **范围**：S0 项目骨架 + Spring Security 登录 + 流程化工作台（项目列表）+ 新建创作任务 + 项目详情（生成入口）；后续各阶段模块契约见总览[模块索引表](../README.md)。
- **技术栈**：Spring Boot 3 + MyBatis-Plus + Spring Security + Vue3/Element Plus（流程化创作工作台，不套重型 admin 外壳）。包结构 `com.sparkora`。
- **前端适配**：**移动端适配**（响应式，移动优先）。Element Plus 响应式栅格 + 断点（xs/sm/md/lg）；移动端单列、汉堡顶栏、表格转卡片、表单单列堆叠、触控目标 ≥44px。不引 Vant 等额外移动端框架。
- **用途**：在真机上逐条打勾验收（S0 目标——能登录、能建项目、能看到生成入口）。
- **修订历史（关键决策）**：
  - 2026-08-18 起**放弃若依**，规格按轻量栈重写。不再有 `sys_*` 复用、不再有 `@SaCheckPermission`/`v-hasPermi`，改用 Spring Security 原生。
  - 2026-08-28 决策：原六步流程中的「校验」步骤**彻底取消**（不做事实核查步骤，SEARXNG/CRAWL4AI 联网核查不启用），流程改为五步：简报→版本→配图→预览→发布。
  - 2026-08-31 S5 决策：发布成功进入 **PUBLISHED_DRAFT**（公众号草稿箱已收），**可重发覆盖**（再次发布刷新 `media_id`/`published_at`），状态为终态、不再回退。
  - 2026-09-03 S6 决策：**配图并入预览步骤**，流程改为四步：简报→版本→预览→发布；**彻底移除 IMAGES_READY 状态**，`VERSIONS_READY` 后直接可预览/发布；预览内提供图库插入 + AI 生图配图能力。车型库图片接入**预留**（暂不开发）。
  - 2026-09-09 模式收敛：快速模式（FAST）下线，深度模式为唯一生成链路（见 [brief-generation.md](brief-generation.md)）。

---

## 1. 自建 vs 复用总览（§0）

S0 是从零搭骨架，以下能力**全部自建**（不引入若依等重型后台）：

| 能力 | 实现方式 | 说明 |
|---|---|---|
| 登录 / 会话 | Spring Security + **JWT**（已定） | 自建 `AuthController` + `SecurityConfig`；密钥/过期读 `.env` |
| 用户 / 角色 | `sparkora_user` + `sparkora_role`（最简：admin/editor/viewer） | MVP 单 workspace，先不做部门树 |
| 工作台布局 | Vue3 + Element Plus，**向导式**而非侧栏 admin | 贴合「主题→brief→版本→编辑→预览→发布」流程 |
| 移动端适配 | Element Plus 响应式栅格 + 断点，**移动优先** | 移动单列/汉堡栏/表格转卡片/触控 ≥44px；不引 Vant |
| 项目列表（工作台首页） | 自建 CRUD + 分页 | `ArticleProjectController` |
| 新建创作任务 | 自建表单 + 校验 | Element Plus `el-form` |
| 项目详情 / 生成入口 | 自建详情页 + 步骤条 | S0 仅第一步可点 |
| 审计 | S0 先用日志文件（`logs/`）；`sparkora_audit_log` 表后置 | 记录生成/编辑/发布关键动作 |
| 配置来源 | 全部从 `.env` 读取（`.env.example` 为模板） | 数据库/JWT/AI/微信/wenyan 均已预留 |

> 2026-08-18 起放弃若依，规格按轻量栈重写。不再有 `sys_*` 复用、不再有 `@SaCheckPermission`/`v-hasPermi`，改用 Spring Security 原生。

---

## 2. 登录（Spring Security）（§2）

- 前端 `/login` → 后端 `POST /api/auth/login`（用户名/密码）。
- 登录成功签发 **JWT**（密钥 `JWT_SECRET`、过期 `JWT_EXPIRE_MINUTES` 从 `.env` 读），返回 token + 当前用户信息。
- 前端后续请求带 `Authorization: Bearer <token>`；后端 `JwtAuthenticationFilter` 校验。
- 验收：未登录访问 `/api/**` → 401（已注册 `AuthenticationEntryPoint`，实测通过；已认证但角色不足仍 403）；前端未登录访问受保护路由 → 跳 `/login`；登出（前端丢弃 token）后再次访问需重新登录。

---

## 3. 路由 / 权限结构（§1）

后端 REST 路径前缀 **`/api`**，Spring Security 保护 `/api/**`，登录走 `/api/auth/login`。

```
前端路由（Vue3）
/login                         登录页
/                              工作台 = 创作项目列表（首页）
/projects/new                  新建创作任务（表单）
/projects/:id                  项目详情（向导步骤条，子路由 brief/versions/preview/publish）
/styles                        风格库
/images                        图库
/car  /car/sync  /car/:id      车型库 / 同步 / 详情
/knowledge                     知识中心（车型 / 新闻 两 Tab）
/qa                            多轮对话式知识问答
/kb                            通用知识库
/settings                      系统检索设置

后端 API（/api 前缀，Spring Security；接口清单截至 S3b，后续模块章节各自登记新增接口）
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
- 前端用 `v-if`/路由守卫判断角色（从 `/api/auth/me` 取），不做若依那种菜单权限点。
- 各模块新增接口的完整契约见对应模块文档（见总览[模块索引表](../README.md)）。

---

## 4. 项目状态机（§4）

S3b 起共 6 态（S2a 前 5 态照旧；「校验」步骤已取消，不设 FACT_CHECK 态，实现见 `BriefService` / `VersionService` / `ImageService`）：

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
- **VERSIONS_READY**：至少一版成功（`current_version_id` 默认指向本次第一版；全部失败才回退 READY）。**S6 起：版本就绪后直接可预览/发布**（配图已并入预览步骤，不再有 IMAGES_READY）。深度单版生成（`/deep/generate`）同样推进 READY→VERSIONS_READY（2026-09-10 修复：落版本后由 `DeepController` 成功分支推状态 + 首版设 current）。**存量数据自愈（09-10-versions-page-fix）**：`schema.sql` 启动时把历史「有版本但仍 READY/DRAFT」的项目推到 VERSIONS_READY，`current_version_id` 为空时设首版（幂等，与 R3 字段回填同段）。
- **PUBLISHED_DRAFT**（S5 新增，终态）：发布成功（渲染 HTML 经 wenyan-server 写入公众号草稿箱，拿到 media_id）。可重发：再次 `POST /publish` 重新渲染并覆盖草稿，刷新 `publish_media_id`/`published_at`/`publish_theme`；发布失败状态原样保留并写 `last_publish_error`（成功后清空）；`publish` 仅在 VERSIONS_READY/PUBLISHED_DRAFT 可调用，否则 `R.fail(400)`（错误经状态校验文案提示，如「尚未生成正文版本，无法预览」）。
- 前端状态映射唯一事实源：`frontend/src/constants/project.js`（文案/标签色/步骤推进/生成中判定/发布判定 `isPublishable`/`isPublished`）。**S6 起 `statusMeta` 对历史残留 `IMAGES_READY` 归一为 `VERSIONS_READY`**（兼容旧数据，避免历史项目无法预览/发布）。
- **状态守护（2026-09-01 定稿）：下游步骤已触发后，上游生成动作前后端双重拦截，禁止状态机回退。**
  - 后端：`generate/brief` 仅在 DRAFT/READY、`generate/versions` 仅在 READY/VERSIONS_READY 放行（条件更新 WHERE 白名单；「生成中且陈旧超 10 分钟」分支自愈但同样限定生成中状态，防 `updated_at` 较旧的下游状态被误放行）；违反返回 `R.fail(409, 中文原因「…下游步骤已触发，不支持回退重做」)`。
  - 前端：StepBrief「重新生成」仅 READY 可见；StepVersions「再生成其他风格」仅 VERSIONS_READY 可见——下一步已触发后不再显示上一步的生成按钮。

> 生成接口并发防护（S2a 补）：项目处于 GENERATING_BRIEF / GENERATING_VERSIONS 时再次触发，返回 `R.fail(409, "该项目正在生成中…")`，不重复调 AI；若生成中状态已陈旧（`updated_at` 超过 10 分钟，如 JVM 中途死亡/重启遗留），原子条件更新放行重新生成以自愈。brief 未就绪时触发版本生成返回 `R.fail(400)`。

---

## 5. 已定决策（§7）

- **会话方式**：Spring Security + **JWT**（`JWT_SECRET` / `JWT_EXPIRE_MINUTES` 从 `.env` 读）。
- **数据库**：**PostgreSQL**，连接参数从 `.env` 的 `SPARKORA_DB_*` 读取（host/port/name/user/password）；schema 初始化脚本幂等可重复。
- **前端工程位置**：`frontend/`，单独 Vue3 + Element Plus 工程。
- **模型入口**：**axonhub 统一入口 `https://axo.caiqz.cn`**（OpenAI 兼容），`AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL` 从 `.env` 读；S0 不调 AI，但骨架预留 `AiClient` 配置读取位。
- **wenyan-server**：S4/S5 双通道；发布通道可用性只看 `WENYAN_MCP_SERVER_URL`+`WENYAN_MCP_SERVER_API_KEY`（旧 `WENYAN_MCP_ENABLED`/`WENYAN_MCP_BIN` stdio 模式已于 S5 废弃移除）。
- **审计**：S0 先用日志文件（`logs/`），`sparkora_audit_log` 表后置。

---

## 6. 配置来源（§7b）

`.env` → Spring Boot（`spring-dotenv` 或启动时读 `.env`），映射到 `@ConfigurationProperties`：

| `.env` 变量 | 代码用途 | 启用状态 |
|---|---|---|
| `SPARKORA_DB_HOST/PORT/NAME/USER/PASSWORD` | 数据源（PostgreSQL） | ✅ 启用 |
| `JWT_SECRET` / `JWT_EXPIRE_MINUTES` | JWT 签发与校验 | ✅ 启用 |
| `SERVER_PORT` | 后端端口（默认 8080） | ✅ 启用 |
| `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` | axonhub 统一入口 | ✅ 启用 |
| `AI_IMAGE_MODEL` / `AI_IMAGE_MODELS` | 文生图 / **图生图**（axonhub，多模型逗号分隔轮询） | ✅ S3b 启用 |
| `AI_RAG_MIN_SCORE` / `AI_RAG_REJECT_SCORE` | 知识库 RAG 检索门槛（逐块/整体；契约见 [retrieval.md](retrieval.md)） | ✅ S6.1 启用 |
| `AI_RAG_KB_TOPK` / `AI_RAG_KB_ENABLED` | 通用知识库检索配额/总开关（契约见 [knowledge/kb.md](knowledge/kb.md)） | ✅ S7 启用 |
| `AI_RAG_ANCHOR_BOOST` | 统一检索锚点车型加权系数（契约见 [knowledge/kb.md](knowledge/kb.md)） | ✅ S8 启用 |
| `AI_IMAGE_MIN_SCORE` | 图片语义检索相似度门槛（契约见 [image.md](image.md)「图片语义检索」；默认 0.3） | ✅ 09-15 img-semantic-search 启用 |
| `IMAGE_STORAGE_DIR` | 数据盘目录（S6 起图片不再落本地；仅 wenyan 渲染临时文件落位） | ✅ S3b 启用 |
| `WECHAT_*` | 公众号草稿发布 | ⏸ **S5 经 wenyan-server 发布（微信凭据配在 server 端，Sparkora 不直连微信）** |
| `WENYAN_MCP_*` | wenyan 预览/发布 | ✅ S5 启用（SERVER_URL/SERVER_API_KEY/PUBLISH_TIMEOUT_MS；发布通道 = 远程 wenyan-server） |
| `SEARXNG_*` / `CRAWL4AI_*` | 搜索/抓取素材 | ✖ 随「校验」步骤取消（2026-08-28 决策），不启用 |

> 各模块专属变量（`CAR_*` / `NEWS_*` / `DEEP_*` / `AI_ILLUSTRATION_*` / `QINIU_*` / `WENYAN_CLI_PATH` 等）见总览[配置总览](../README.md)与对应模块文档。

---

## 7. 交付物（§9）

- Spring Boot 3 + MyBatis-Plus + Spring Security 后端骨架（`com.sparkora`）。
- Vue3 + Element Plus 流程化工作台前端（`frontend/`），**移动端响应式适配**。
- 业务表 `sparkora_article_project`、`sparkora_user`、`sparkora_role`（最简）。
- 登录 + 工作台列表 + 新建任务表单 + 项目详情（步骤条占位）。

---

## 8. 统一约定

### 8.1 `R<T>` 响应包装

- `com.sparkora.common.R<T>` = `{code, msg, data}`；`code=0` 成功，失败用 `R.fail(code, msg)`（msg 中文提示）。
- 分页接口返回 `PageResult`：`{rows[], total, page, size}`。
- 业务失败（含登录失败、生成失败）均为 **HTTP 200 + `R.fail`**，前端不能只依赖 axios 错误拦截器，必须检查 `code`（`store.login` / `ProjectEdit` / `StepBrief` / `StepVersions` 已逐处落实）。

### 8.2 框架层错误矩阵（`ApiExceptionHandler`）

`@RestControllerAdvice(basePackages = "com.sparkora.web.controller")` 把 Spring 框架层异常统一收敛为 `R.fail(400, 中文提示)`；业务异常仍由各控制器自行 `catch` 转换（本类只兜框架层）：

| 异常 | 行为 |
|---|---|
| `MethodArgumentTypeMismatchException`（`@RequestParam Long` 解析失败，如 NaN/字母） | `R.fail(400, "参数 <name> 格式不正确")` |
| `MissingServletRequestParameterException`（缺必填参数） | `R.fail(400, "缺少必填参数: <name>")` |
| `HttpMessageNotReadableException`（请求体 JSON 不可读/字段类型不符） | `R.fail(400, "请求体格式不正确")` |
| `MethodArgumentNotValidException` / `BindException`（`@Valid` DTO 校验失败） | 取首条字段中文提示，`R.fail(400, <提示>)`；无提示回退「参数校验失败」 |
| `MultipartException`（multipart 超限/损坏） | `R.fail(400, "上传失败: <root message>")` |

- 背景：图库页上传曾因 `projectId=NaN` 落到默认 400，前端只看到裸 `{"status":400}` 无 msg，故统一兜底。

### 8.3 权限角色

- 角色仅 **ADMIN / EDITOR / VIEWER** 三种；viewer 只读。
- 接口级授权用方法级 `@PreAuthorize("hasAnyRole('ADMIN','EDITOR','VIEWER')")`；写接口限 ADMIN/EDITOR，DELETE 仅 ADMIN。
- 未登录 401（`AuthenticationEntryPoint`），已认证但角色不足 403。

### 8.4 其他全局惯例

- 实体审计字段（`created_by` / `created_at` / `updated_at` / `deleted`）由控制器手工赋值。
- 表结构变更三处同步：`schema.sql`（幂等）+ 对应 entity/mapper + 对应模块文档字段级表格。
- 排序字段白名单：用户可控的 `orderBy`/`orderDir` 只允许映射到固定列名常量，原始参数绝不透传 `QueryWrapper`（如 `GET /api/projects` 的 `orderBy` 白名单 `updatedAt`(默认)/`createdAt`，`orderDir` `desc`(默认)/`asc`，非法值静默回退默认）。
