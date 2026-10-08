# 知识中心浏览页（C3 / 10-05-source-center-ui 重构）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：统一「知识中心」入口，以 Tab 组织「车型」「知识库」「信源」「问答」四类浏览。**问答以独立入口存在（`/qa`），知识中心内为聚合面板**。

> **10-05-source-center-ui 重构（2026-10-05）**：Tab 结构由「车型/知识库/新闻/问答」调整为「车型/知识库/**信源**/问答」——
> **移除「新闻」Tab、新增「信源」Tab**（用户决策：存储层已统一为「信源域 + `source` 列」，UI 须镜像领域而非历史实现；
> 详见 [`../sources.md`](sources.md)）。原「新闻」Tab 能力（BYD 同步/切块数/官方原文/同步轮询）在「信源」Tab 内
> **对 `source=byd-news` 条目条件保留**（非一刀切通用列表）。**Tab 不是路由**：`router/index.js` 仅 `/knowledge` 单入口，
> 无 `/kb`、`/news`、`/sources` 路由；AppShell 左 rail 与 `/knowledge`、`/car`、`/car/:id` 路由不动。

---

## 1. 前端路由

| 路由 | 组件 | 说明 |
|---|---|---|
| `/knowledge` | `views/KnowledgeCenter.vue` | 知识中心，`meta.auth`；`el-tabs` 为「车型」「知识库」「信源」「问答」四个 Tab（Tab 非路由） |

- 现有路由不变：`/car`、`/car/sync`、`/car/:id`、`/kb`。
- AppShell 左 rail 导航项不变（登录后可见）。

---

## 2. 组件与数据流

| 文件 | 职责 |
|---|---|
| `views/KnowledgeCenter.vue` | 页头 + `el-tabs`；Tab 面板 `v-if` 懒挂载（首访加载、切回保留状态）；`loadedTabs` = `{car,kb,sources,qa}` |
| `views/knowledge/CarKnowledgePanel.vue` | 车型 Tab：`carApi.list()` + 关键词/网络/状态筛选 + 卡片网格；缩略图取 `introImageUrls[0]`；点卡片跳 `/car/:id`；编辑器以上「同步车型」跳 `/car/sync`。**本任务未改动** |
| `views/knowledge/KbLibraryPanel.vue` | 知识库 Tab：`kbApi` 列表/新建/编辑/重建/批量导入。**本任务未改动** |
| `views/knowledge/SourceTab.vue` | 信源 Tab 容器：统一加载 `sourceApi.list()` 供子面板做来源名映射；上部「信源管理 + 采集任务」，下部「信源内容」 |
| `views/knowledge/SourceManagePanel.vue` | 信源管理：列表（名称/类型/垂直/排期/最近采集状态/栏目数/启停）+ 编辑抽屉（el-form + rules，含**栏目列表** channels[]）+ 启停 switch + 手动触发采集（整源 / 单栏目 `channelId`） |
| `views/knowledge/SourceJobPanel.vue` | 采集任务：列表（进度 `total/success/failed/degraded`、起止时间、错误）+ 失败明细抽屉（解析 `failedItems` JSON）+ 失败重试；RUNNING 时 **2s 轮询**（`onBeforeUnmount` 清 `setInterval`） |
| `views/knowledge/SourceContentPanel.vue` | 信源内容：分页 + 关键词 + `category` + `sourceId` 筛选 + 详情抽屉（正文 pre-wrap，**表格类内容保留行列不压平**）；`source=byd-news` 条目**条件保留** BYD 专属能力（同步 FULL/INCREMENT + 轮询、切块数、官方原文链接相对 URL 补 `https://www.byd.com`）；通用信源内容提供「重建向量」（`rebuildContent`） |
| `views/knowledge/NewsKnowledgePanel.vue` | 原「新闻」Tab 面板；BYD 能力已迁入 `SourceContentPanel`，**不再被引用**（留档，不删除） |
| `views/knowledge/QaChatPanel.vue` | 问答 Tab。**本任务未改动** |
| `api/index.js` | `sourceApi`（list/get/update/collect/contentList/contentGet/rebuildContent）与 `sourceJobApi`（list/get/retry）；`newsApi` 保留（BYD 同步仍走 `/api/news/sync/*`，向后兼容） |

- **API 分层约定**：页面一律经 `src/api/index.js` 具名导出调用，禁止 `.vue` 直调 `http`。
- **`http.js` 拆包**：响应拦截已 `return resp.data`，调用方拿到的即 `R<T>`，读 `res.code`/`res.data`；分页读 `res.data.rows`/`res.data.total`。
- **URL 解析**：相对路径统一 `resolveUrl`——`http(s)://` 开头原样，否则 BYD 来源补 `https://www.byd.com`（其他来源按自身 `url` 展示）。
- **JSON 字段容错**：`SourceJobEntity.failedItems` 为 TEXT 列 JSON 字符串，前端归一兼容「字符串 / 数组」两态。
- Tab 面板用 `v-if` 懒挂载实现「首访加载 + 切回保留状态」（`loadedTabs` 集合）。

---

## 3. 关键实现路径

- 前端：`views/KnowledgeCenter.vue`、`views/knowledge/{CarKnowledgePanel,KbLibraryPanel,SourceTab,SourceManagePanel,SourceJobPanel,SourceContentPanel,QaChatPanel}.vue`、`router/index.js`（`/knowledge`）、`layouts/AppShell.vue`。
- 后端：车型（[car.md](car.md)）/知识库（[retrieval.md](../retrieval.md)）复用既有接口；信源注册/采集/内容查询见 [sources.md](sources.md)（`SourceController` `/api/sources*`、`/api/source-jobs*`、`/api/source-contents*`）；BYD 新闻见 [news.md](news.md)（`/api/news*`）。

---

## 4. 信源 Tab 契约（10-05 新增）

- **Tab 顺序**：车型 / 知识库 / 信源 / 问答（`loadedTabs.sources`）。
- **信源管理**（写 ADMIN/EDITOR，读三角色）：编辑源级字段（名称/类型/垂直/cron/发布窗口/权威分档/启停/需 Crawl4AI）、启停、手动触发采集（整源或单 `channelId`）。**信源注册表当前无新建接口**（`SourceController` 仅提供 `PUT /api/sources/{id}` 部分更新，见 sources.md）；新源/新栏目由后端预置或直接写库，U 提供查看与编辑。
- **栏目（channel）**：源详情/编辑内展示栏目列表（`name/list_url/category/parse_rules/need_crawl4ai/enabled`），可对单栏目触发采集（`POST /api/sources/{id}/collect` body `{channelId}`）。
- **采集任务**：进度与失败明细（`GET /api/source-jobs`、`/{id}`）；失败项重试（`POST /{id}/retry`）；RUNNING 2s 轮询。
- **内容浏览**：`GET /api/source-contents`（分页/`keyword`/`category`/`sourceId`）+ `/{id}` 详情；**BYD 新闻作为 `source=byd-news`、`category=官方新闻` 的一个来源并入**，其同步/切块/原文/轮询能力对 `source=byd-news` 条件保留。
- **权限**：读三角色；写/触发采集/重试 ADMIN/EDITOR，按钮按角色显隐（`store/user.js` 的 `isEditorOrAbove`），后端 `@PreAuthorize` 兜底。

---

## 5. 验收清单

- [x] AC1 `/knowledge` 可访问，恰含「车型」「知识库」「信源」「问答」四个 Tab（`router/index.js` + `KnowledgeCenter.vue`）；「新闻」Tab 已移除
- [x] AC2 车型 Tab 列表/筛选/卡片跳 `/car/:id` 可用，缩略图用 `introImageUrls`（未改动）
- [x] AC3 知识库 Tab 行为未变（未改动）
- [x] AC4 信源 Tab：信源列表/编辑/启停/手动采集可用；栏目列表可见并可单栏目采集；采集任务进度/失败明细/重试可用（RUNNING 2s 轮询）
- [x] AC5 信源内容：分页/关键词/category/source 筛选/详情可用；BYD 新闻在信源内容视图中可访问，同步/切块数/官方原文/轮询对 `source=byd-news` 可用
- [x] AC6 `/car`、`/kb` 等既有路由与页面未改动；车型/知识库/问答三 Tab 代码与懒加载未改动
- [x] AC7 `npm run build` 通过（exit 0）
- [x] AC8 无 `.vue` 直调 `http`（`grep` 零命中）；轮询定时器 `onBeforeUnmount` 清理

---

## 6. 已知限制

- 知识中心原「仅聚合浏览、不做编辑」的旧定位被**有意突破**：信源 Tab 提供注册表编辑/启停/采集触发，属于长期运营动作。
- **信源新建与栏目 CRUD 无后端接口**：`SourceController` 仅 `PUT /api/sources/{id}`（源级部分更新），无 `POST /api/sources` 与 `sparkora_source_channel` 的增删改端点。故信源/栏目当前由后端预置或直接写库；U 只做查看与源级编辑。若后续运营需在线新建信源/维护栏目选择器，须先在 B 侧补接口（本任务范围外）。
- 采集内容的**视频浏览**、图片独立浏览不在信源 Tab（正文配图经 B/E 转入既有[图库](../image.md)）。
- 移动端未适配（PC-only 桌面工作台）。
