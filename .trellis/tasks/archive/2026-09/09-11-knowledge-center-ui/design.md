# Design — C3 知识中心浏览页（车型/新闻）

> 父任务 `09-11-knowledge-base-data-foundation`。依赖：C1（车型数据/`introImageUrls`）、C2（新闻 REST + 数据）。与父任务 `design.md` §5/§6 契约衔接。

## 1. 范围与边界

**目标**：新增 `/knowledge` 统一知识中心，以 Tab 组织「车型」「新闻」两类知识浏览；现有 `/car`、`/car/:id`、`/car/sync`、`/kb` 路由与页面**完全保留不改**。问答不是 Tab（C4 独立入口）。

**不做**：问答 UI（C4）；车型/新闻的编辑与同步管理页重构（同步能力沿用 C1/C2 接口，C3 仅提供入口与最小触发）；新闻正文重新抓取。

## 2. 现有可复用资产（证据）

- 路由：`frontend/src/router/index.js`（41 行）已有 `/car`、`/car/sync`、`/car/:id`、`/kb`；无 `/knowledge`。
- 车型 API：`frontend/src/api/index.js` `carApi`（list/detail/catalog/createJob/getJob/listJobs/retryJob/syncOne/remove/rag）。
- 车型列表返回 `CarModelEntity`，C1 后含非持久化 `introImageUrls: string[]`（`CarLibrary.vue:161-167` 已按此展示缩略图）。
- 新闻 REST（C2）：`GET /api/news?page&size&keyword` → `R<PageResult<NewsEntity>>`（`rows/total/page/size`）；`GET /api/news/{id}` → `R<NewsEntity>`（含 `content`）；同步 `POST /api/news/sync/jobs {jobType:"FULL"|"INCREMENT"}` → `{jobId}`；`GET /api/news/sync/jobs/{id}`。
- `NewsEntity`：`id, newsId, title, url(相对,如 /cn/detail634), imageUrl(相对或绝对), publishDate, tags, tagNames(JSON 数组), content, chunkCount(非持久化)`。
- KB API：`KbDocController` 前缀 `/api/kb`（`GET /docs`、`GET /docs/{id}`、`POST`、`PUT /{id}`、`DELETE /{id}`、`POST /{id}/rebuild`）；`KbLibrary.vue` 现直调 `http`（R5 需补 `kbApi`）。
- 样式变量：`frontend/src/assets/main.css`（`--brand/--ink/--muted/--faint/--line/--card/--radius` 等）；移动端断点 `768px`，触控 ≥44px 约定。

## 3. 组件与文件设计

| 文件 | 动作 | 说明 |
|---|---|---|
| `frontend/src/views/KnowledgeCenter.vue` | 新增 | `/knowledge` 页面：`TopBar` + `container` + `el-tabs`（车型/新闻），各自内联面板 |
| `frontend/src/views/knowledge/CarKnowledgePanel.vue` | 新增 | 车型 Tab 面板：搜索 + 网络/状态筛选 + 卡片网格；点卡片跳 `/car/:id`（复用现有详情页）；编辑器以上显示「同步车型」跳 `/car/sync` |
| `frontend/src/views/knowledge/NewsKnowledgePanel.vue` | 新增 | 新闻 Tab 面板：搜索 + 分页列表 + 详情抽屉（正文/封面/标签/原文链接）；编辑器以上可触发 FULL/INCREMENT 同步并轮询进度 |
| `frontend/src/api/index.js` | 修改 | 新增 `newsApi`（list/get/createJob/getJob/listJobs/retryJob）与 `kbApi`（list/get/create/update/remove/rebuild） |
| `frontend/src/views/KbLibrary.vue` | 修改 | 直调 `http` 改为 `kbApi`（纯机械替换，行为不变） |
| `frontend/src/router/index.js` | 修改 | 新增 `/knowledge` 路由（auth） |
| `frontend/src/layouts/TopBar.vue` | 修改 | 新增「知识中心」导航项（保留车型库/知识库链接，遵循父设计「不替换」） |

**不抽取 CarLibrary.vue**：为避免影响 `/car` 既有页面（AC4），C3 车型面板独立实现「浏览」子集（列表+筛选+跳转详情），不复用/重构 CarLibrary.vue。车型详情页复用现有 `/car/:id`，参数渲染逻辑不重写。此取舍已在本文记录（轻微列表逻辑重复换取 `/car` 零回归风险）。

## 4. 交互契约

### 4.1 车型 Tab
- 加载 `carApi.list()`；筛选项：关键词（name/salesNetwork）、销售网络（去重）、同步状态（all/SUCCESS/PENDING/FAILED）。
- 缩略图：`row.introImageUrls?.[0]`（无则占位）。
- 点击卡片 → `router.push('/car/' + row.id)`。
- 顶部按钮：`user.isEditorOrAbove` 显示「同步车型」→ `/car/sync`。

### 4.2 新闻 Tab
- 列表：`newsApi.list({page, size:12, keyword})`；渲染封面（`resolveImageUrl`：`http` 开头原样，否则 `https://www.byd.com` + path）、标题、日期、`tagNames`（JSON 解析）前若干。
- 详情：点击卡片 → `newsApi.get(id)` → 抽屉展示标题/日期/封面/正文 `content`（`white-space: pre-wrap`）+「查看官方原文」（`https://www.byd.com` + `url`）+ 切块数。
- 分页：`el-pagination`（total/page/size）。
- 同步（编辑器以上）：下拉 INCREMENT（默认）/FULL → `newsApi.createJob(jobType)` → 轮询 `newsApi.getJob(jobId)` 显示进度，终态刷新列表。

### 4.3 兼容
- `/car`、`/car/:id`、`/car/sync`、`/kb` 路由与组件不改（除 TopBar 新增链接、KbLibrary 换 api 封装）。
- `kbApi` 替换严格保持原请求方法/路径/参数不变。

## 5. 边界与校验
- `tagNames` 为 JSON 字符串，前端解析失败静默降级为空数组。
- 新闻 `content` 可能为空（图片型）：抽屉显示「该新闻为图片型内容，请查看官方原文」。
- 分页参数：page<1 归 1；size 固定 12（列表）。
- 空态：车型/新闻各自 `el-empty` 文案引导。

## 6. 验证
- `npm run build` 通过。
- 手测：`/knowledge` 两 Tab 可切换；车型卡片跳详情正常；新闻列表分页/详情正文/原文链接正常；`/car`、`/kb` 仍可用。

## 7. 回滚
- 新增文件可直接删除；`router/index.js`、`TopBar.vue`、`api/index.js`、`KbLibrary.vue` 改动均为可逆小改（新增项/机械替换）。
