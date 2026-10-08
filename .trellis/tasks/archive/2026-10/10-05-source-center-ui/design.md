# design.md — U: 知识中心信源管理重构

> 父任务设计：`../10-05-self-hosted-sources/design.md`。
> 依赖：`10-05-source-crawl-base`（信源/任务 API）+ `10-05-source-domain-retrieval`（内容查询）。
> 现状依据：`docs/spec/knowledge/center.md`、`frontend/src/views/KnowledgeCenter.vue`（已是 `el-tabs` 多面板）。

## 1. 边界

**纯前端**（Vue3 + Element Plus + Pinia），后端 API 全部来自 B/E，U 不新增后端接口。
改动集中在：

| 层 | 文件 | 改动 |
|---|---|---|
| 视图 | `views/KnowledgeCenter.vue` | **移除「新闻」Tab、新增「信源」Tab**（`el-tabs` + lazy 加载） |
| 面板 | `views/knowledge/SourceManagePanel.vue`（新增） | 信源列表/新建/编辑/启停/手动采集 |
| 面板 | `views/knowledge/SourceJobPanel.vue`（新增） | 采集任务进度/失败明细/重试 |
| 面板 | `views/knowledge/SourceContentPanel.vue`（新增，**承接 `NewsKnowledgePanel` 能力**） | 采集内容浏览（分页/筛选/详情）+ BYD 专属能力（同步/切块数/原文/轮询） |
| 面板 | `views/knowledge/NewsKnowledgePanel.vue` | **能力迁移到 `SourceContentPanel` 后删除**（或保留为内部子组件由 `SourceContentPanel` 在 `source=byd` 时复用） |
| API | `src/api/index.js` | 新增 `sourceApi` / `sourceJobApi` 具名导出（`newsApi` 保留供后续/兼容） |
| 文档 | `docs/spec/knowledge/center.md` | 更新 Tab 契约（新闻并入信源） |

**不做**：后端采集/入库（B/E）；内容人工编辑；图片/视频浏览；移动端。

## 2. Tab 结构决策（用户 2026-10-05：替换，非并列）

现状：`KnowledgeCenter.vue:7` 的 `el-tabs` 有 车型/知识库/新闻/问答 四面板（`loadedTabs` 懒加载）。
**注意：Tab 不是路由**——`router/index.js` 只有 `/knowledge` 单入口，无 `/kb`、`/news` 路由（原 PRD 表述有误）。

**决策：`SourceTab` 替换 `news` Tab**（新增「信源」，删除「新闻」）：
- **依据**：B/E 后存储层统一为「信源域 + `source` 列」，BYD 新闻 = `source=byd`、`category=官方新闻` 的一行。
  UI 须镜像统一后的领域模型；两 Tab 读同一张表会重复实现且认知不一致。
- **Tab 内布局**：`SourceTab` 内分「信源管理 + 采集任务」（上部）+「信源内容」（下部）分区，减少 Tab 数量。
- **Tab 顺序**：车型 / 知识库 / **信源** / 问答（`'news'` 键从 `loadedTabs` 移除，加 `'sources'`）。
- **lazy**：`loadedTabs.sources` 首次点击才请求（照既有范式）。
- **迁移策略**：`NewsKnowledgePanel` 的能力迁入 `SourceContentPanel`（§2.1），迁移完成前可保留该组件作为
  `source=byd` 分支的内部子组件，避免一次性重写风险。

## 3. 组件与数据流

| 组件 | 数据源 | 交互 |
|---|---|---|
| `SourceManagePanel` | `sourceApi.list()` / `create` / `update` / `toggle` / `collect` | 表格 + 编辑抽屉（`el-form` + rules）；手动采集后跳任务区 |
| `SourceJobPanel` | `sourceJobApi.list()` / `get` / `retry` | 任务表 + 失败明细抽屉；RUNNING 时 **2s 轮询**（照 `NewsKnowledgePanel` 同步轮询范式，组件卸载清定时器） |
| `SourceContentPanel` | `sourceApi.contentList({page,size,keyword,category,sourceId})` | 分页 + 筛选 + 详情抽屉（正文 pre-wrap；表格类内容保留行/列展示）；`source=byd` 时条件渲染同步按钮/切块数/官方原文/轮询 |

### 2.1 BYD 新闻能力保留（替换的代价）

`NewsKnowledgePanel` 现有专属能力**必须不丢**，按来源条件保留：

| 能力 | 处理 |
|---|---|
| 同步（FULL/INCREMENT）+ RUNNING 轮询 | `SourceContentPanel` 对 `source=byd` 显示同步按钮 + 轮询（仅 BYD 有同步语义；其他源走「手动采集」） |
| 切块数展示 | 详情抽屉对 `source=byd` 保留 |
| 官方原文链接（`https://www.byd.com` + 相对 `url`） | 对 `source=byd` 保留；其他源按其 `url` 展示 |
| 正文空显示图片型提示 / `tagNames` 容错 | 迁移进内容面板通用逻辑 |

> 迁移完成前，允许 `SourceContentPanel` 内部 `v-if="source==='byd'"` 复用 `NewsKnowledgePanel` 的子结构，
> 避免一次性重写引入回归。

## 4. API 分层（center.md §2）

- 一律经 `src/api/index.js` 具名导出（`sourceApi`/`sourceJobApi`），**禁止 `.vue` 直调 `http`**。
- 方法对应 B 的接口（design B §6）：`list/get/update/collect`、`jobs.list/get/retry`。
- 错误由 `http.js` 拦截器统一提示（401 跳登录）。

## 5. 权限（照现有知识中心范式）

- 读（列表/详情/内容）：登录即可。
- 写（新建/编辑/启停/手动采集/重试）：按钮按角色显隐（ADMIN/EDITOR 可见，VIEWER 隐藏）；后端已 `@PreAuthorize` 兜底。
- 角色来源：`store/user.js`。

## 6. 导航

- 不新增顶层路由：信源作为 `/knowledge` 的 Tab（Tab 非路由，见 §2；AppShell 左 rail 与 `/knowledge`、`/car`、`/car/:id` 不变）。
- 移除「新闻」Tab 不涉及路由删除（`/news` 本就不存在）。
- 若后续需要深链 `/knowledge?tab=sources`，预留（不在本任务范围）。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 替换「新闻」Tab 丢失其能力 | §2.1 BYD 能力条件保留；AC-U2 验证同步/切块/原文/轮询仍可用 |
| 新增 Tab 破坏既有面板懒加载 | 照 `loadedTabs.*` 既有范式；车型/知识库/问答 Tab 代码不动 |
| 轮询泄漏 | 组件 `onUnmounted` 清 `setInterval` |
| 直调 `http` 破坏分层 | code review + `npm run build`；沿用 `kbApi` 规范化先例 |
| viewer 误见写按钮 | 角色显隐 + 后端 403 双保险 |
| 表格内容展示丢结构 | 详情抽屉保留行/列，不做纯 pre-wrap 压平 |

## 8. 回滚

纯前端新增，删除新面板 + 移除 Tab 即回滚；既有 Tab 与路由零改动。
