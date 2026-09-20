# 知识中心浏览页（C3）

> 回链：[系统说明总览](../../README.md) ｜ 上级：[知识库检索](../retrieval.md)

职责：统一「知识中心」入口，以 Tab 组织「车型」「新闻」两类知识浏览。**问答不是 Tab**（[qa.md](qa.md) 为独立入口）。现有 `/car`、`/car/:id`、`/car/sync`、`/kb` 路由与页面**保留不改**（Tab 为聚合浏览入口，非替换）。

> C3 正式规格（2026-09-12）。

---

## 1. 前端路由

| 路由 | 组件 | 说明 |
|---|---|---|
| `/knowledge` | `views/KnowledgeCenter.vue` | 知识中心，`meta.auth`；`el-tabs` 仅「车型」「新闻」两个 Tab |

- 现有路由不变：`/car`、`/car/sync`、`/car/:id`、`/kb`。
- TopBar 新增「知识中心」导航项（登录后可见）；「车型库」「知识库」链接保留。

---

## 2. 组件与数据流

| 文件 | 职责 |
|---|---|
| `views/KnowledgeCenter.vue` | 页头 + `el-tabs`；Tab 面板 `v-if` 懒挂载（首访加载、切回保留状态） |
| `views/knowledge/CarKnowledgePanel.vue` | 车型 Tab：`carApi.list()` + 关键词/网络/状态筛选 + 卡片网格；缩略图取 `introImageUrls[0]`（**非 `introImages` 原始 id**）；点卡片跳 `/car/:id`；编辑器以上「同步车型」跳 `/car/sync` |
| `views/knowledge/NewsKnowledgePanel.vue` | 新闻 Tab：`newsApi.list({page,size,keyword})` 分页 + 详情抽屉（`newsApi.get`，正文 pre-wrap / 官方原文 `https://www.byd.com`+`url` / 切块数）；`tagNames` JSON 容错；`content` 空显示图片型提示；编辑器以上 FULL/INCREMENT 同步 + 轮询 |
| `api/index.js` | `newsApi`（list/get/createJob/getJob/listJobs/retryJob）与 `kbApi`（list/get/create/update/remove/rebuild） |

- **API 分层约定**：页面一律经 `src/api/index.js` 具名导出调用，禁止 `.vue` 直调 `http`；`KbLibrary.vue` 已由直调 `http` 规范为 `kbApi`（方法/路径/参数等价，行为不变）。
- **`http.js` 拆包**：响应拦截已 `return resp.data`，调用方拿到的即 `R<T>`，读 `res.code`/`res.data`；分页读 `res.data.rows`/`res.data.total`。
- **URL 解析**：相对路径（新闻 `imageUrl`/`url`）统一 `resolveUrl`——`http(s)://` 开头原样，否则补 `https://www.byd.com`。
- Tab 面板用 `v-if` 懒挂载实现「首访加载 + 切回保留状态」（`loadedTabs` 集合）。

---

## 3. 关键实现路径

- 前端：`views/KnowledgeCenter.vue`、`views/knowledge/CarKnowledgePanel.vue`、`views/knowledge/NewsKnowledgePanel.vue`、`router/index.js`（`/knowledge`）、`layouts/TopBar.vue`。
- 后端：复用车型（[car.md](car.md)）/新闻（[news.md](news.md)）既有接口，无新增后端。

---

## 4. 验收清单（2026-09-12 check 实测）

- [x] AC1 `/knowledge` 可访问，恰含「车型」「新闻」两个 Tab（`router/index.js` + `KnowledgeCenter.vue`）
- [x] AC2 车型 Tab 列表/筛选/卡片跳 `/car/:id` 可用，缩略图用 `introImageUrls`
- [x] AC3 新闻 Tab 列表分页（`PageResult`）/详情正文/原文链接可用
- [x] AC4 `/car`、`/kb` 等既有路由与页面未改动（`git diff` 核实；KbLibrary 仅机械换 `kbApi`，6 处调用等价）
- [x] AC5 `npm run build` 通过（exit 0）
- 既有缺陷（非 C3 引入，未修）：`CarLibrary.vue` 批量重建直调 `http` 但未 import（ReferenceError 隐患），待后续任务修复。**注：09-13 已修为 `carApi.rebuildAll()`。**

---

## 5. 已知限制

- 知识中心仅聚合浏览，不做编辑（编辑走各自页面 `/car/*`、`/kb`）。
- Tab 仅车型/新闻两类；问答/图库不在其中。
