# design.md — PC 端 UI 重构（批 1）

> 范围：token v2 + AppShell 外壳 + PC-only 断点 + 3 个试点页 + 规范同步。
> 批 2/3 的页面版式见 `prd.md` §4 R4 表格，不在本文件展开。

## 1. 架构与边界

### 1.1 分层

```
frontend/src
├─ assets/main.css          ← 【重写】token v2（明暗双模）+ 极少量全局基元
├─ layouts/
│  ├─ AppShell.vue          ← 【新增】唯一外壳：rail + topbar + content slot
│  ├─ DesktopGuard.vue      ← 【新增】<1280px 遮罩（纯 CSS 驱动）
│  └─ TopBar.vue            ← 【删除】职责并入 AppShell
├─ composables/
│  └─ usePageHeader.js      ← 【新增】页面 → 上下文条的 provide/inject 通道
├─ constants/
│  ├─ nav.js                ← 【新增】8 个模块的唯一真源（与 router 对齐）
│  └─ project.js            ← 【不改】状态机映射；activeStepOf/maxReachableStepOf 退役见 §4.3
├─ views/                   ← 【改 3 个】ProjectList / StepPreview / ImageLibrary
└─ store|api|utils|composables(其余)  ← 【零改动】
```

**边界铁律**（违反即视为越界）：

| 层 | 规则 |
|---|---|
| `store/` `api/` `utils/` | 一行不改。版式重构不引入任何数据域或契约变更。 |
| `composables/` 现有 9 个 | 一行不改。已验证零 viewport 耦合。 |
| `MarkdownEditor.vue` | **不动**。CodeMirror 实例与滚动同步（`@scroll`）是全站唯一 DOM 测量代码，改版式不得触碰其内部。 |
| `preview/PreviewPane.vue` | 只改外围容器与 `<style>`，**必须保留 `class="wenyan-preview"`**（见 §4.1）。 |
| `router/index.js` | 一行不改。Q3 已决：4 条 `project-*` 路由与 8 个模块路由全部保留。 |

### 1.2 AppShell 结构

```
┌──────────┬────────────────────────────────────────────────────────┐
│          │ ▸ 折叠  面包屑:  项目 / 创作项目        [搜索?]  🌙  用户 ▾│ 48px
│  rail    ├────────────────────────────────────────────────────────┤
│  220px   │                                                        │
│  /56px   │                  content slot                          │
│          │                （页面自渲染，可全幅）                     │
│  ◆ 品牌   │                                                        │
│  ─ 导航   │                                                        │
│  ─ 用户   │                                                        │
└──────────┴────────────────────────────────────────────────────────┘
```

- **布局**：`body > #app { display:flex; height:100vh; overflow:hidden }`。滚动容器是 **content 区**，不是 body——这是「持久化应用」观感的关键。
- **rail**：可折叠 220px ↔ 56px（仅图标），折叠态持久化到 `localStorage['sparkora_rail']`。模块项来自 `constants/nav.js`；当前项由 `route.matched[0].path` 高亮；底部分隔线后固定「设置」；最底部为用户区（头像首字母 + 名称 + 角色 tag）。
- **topbar**：44-48px，左侧折叠按钮 + 面包屑，右侧全局动作。面包屑与页面主操作通过 `usePageHeader()` 注入（§2.2）。
- **登录页豁免**：`LoginView.vue` 不套 AppShell，保持现有全屏品牌+表单结构（仅去 `@media`、换 token）。
- **项目详情豁免 topbar 的部分区域**：`ProjectLayout.vue` 在 AppShell 内再叠一层**步骤 rail**（§3.3）。

### 1.3 为什么用 `provide/inject` 而不是每页自带 page-header

现状是每页自己渲染 `<div class="page-header">`（11 处）。若沿用，topbar 与页面标题会重复两行。`usePageHeader()` 让页面把 `{ title, breadcrumb, actions }` 交给外壳渲染：

- 页面模板少一层 DOM，标题与全局动作同处一条 48px 工具带 → Linear/Notion 观感。
- 面包屑天然支持「项目 / 预览」两级，替代原 `page-kicker` 英文大写标签（Q1 已决废弃）。
- 试点页之外的其他 17 页**暂不改**，它们继续渲染自己的 `page-header`；外壳的 topbar 在这些页上只显示 `模块名` 一级面包屑，视觉上不冲突（批 2/3 逐页迁移）。

## 2. 数据流与契约

### 2.1 导航真源

`constants/nav.js` 导出：

```js
export const NAV_MODULES = [
  { name: '项目',     to: '/',          icon: 'Files',   require: 'loggedIn' },
  { name: '图库',     to: '/images',    icon: 'Picture', require: 'loggedIn' },
  { name: '风格库',   to: '/styles',    icon: 'Brush',   require: 'editor'   },
  { name: '知识中心', to: '/knowledge', icon: 'Reading', require: 'loggedIn' },
  { name: '知识问答', to: '/qa',        icon: 'ChatDot', require: 'loggedIn' },
  { name: '车型库',   to: '/car',       icon: 'Van',     require: 'loggedIn' },
  { name: '知识库',   to: '/kb',        icon: 'Notebook',require: 'loggedIn' },
]
export const NAV_SETTINGS = { name: '设置', to: '/settings', icon: 'Setting', require: 'editor' }
```

- `require` 直接映射 `store/user.js` 的 `isLoggedIn` / `isEditorOrAbove`，与 `TopBar.vue:5-12` 现状**逐项一致**。
- **AC1 要求「与 router 路由集合完全一致」** ⇒ 加一条轻量断言（模块 `<script setup>` 内 `console.assert` 或在 `router/index.js` 尾部 dev-only 校验）：`auth:true` 的非 `projects/:id` 路由集合必须等于 `NAV_MODULES + NAV_SETTINGS` 的 `to` 集合。项目详情子路由不进 rail（由步骤 rail 承担）。

### 2.2 `usePageHeader()`

```js
// composables/usePageHeader.js
const KEY = Symbol('sparkora.pageHeader')
export function providePageHeader() { provide(KEY, reactive({ crumbs: [], actions: [] })) }
export function usePageHeader() { return inject(KEY, null) }   // null = 该页不参与
```

- AppShell 顶层 `providePageHeader()`；页面 `const hdr = usePageHeader()` 后赋值。
- 契约：`hdr.crumbs: [{ label }]`（末项即页面标题）、`hdr.actions: [{ label, type, onClick, disabled, loading, icon }]`。
- `null` 时（未调 `provide` 的页面）topbar 只渲染模块名，页面自己画 `page-header`——**批 2/3 未迁移页的兼容路径**。

### 2.3 状态机映射不变

`constants/project.js` 的 `statusMeta` / `statusLabel` / `statusTagType` / `isGeneratingBrief` / `isGeneratingVersions` / `isGenerating` / `isPublishable` / `isPublished` **全部原样复用**。`activeStepOf` / `maxReachableStepOf` 退役（唯一使用方 `ProjectLayout.vue:83-84, 133`），其职责由 §3.3 的步骤 rail 内部按同一状态映射表计算。**不新增/不修改任何状态值**，后端零改动。

## 3. 试点页目标版式

### 3.1 `ProjectList.vue`（表格型）

```
┌ 上下文条: 面包屑「项目 / 创作项目」            [＋ 新建创作任务] ┐
├ 工具带: [搜索 240] [状态 ▾] [排序 ▾] [↑↓]        [批量删除]      │
├────────────────────────────────────────────────────────────────┤
│ ☐  主题(min 320,2 行为主) │ 关键词 │ 状态 │ 创建人 │ 更新时间 │ ⋯ │  40px 行高
│ ☐  …                                                            │
├                                          共 N 条  ‹ 1 2 3 ›       │
└────────────────────────────────────────────────────────────────┘
```

- **删除** `.card-list` 模板块（`ProjectList.vue:99-111`）与 `.responsive-table` 包裹（`:33`）。
- 表格：去竖线（沿用 `:224`）、行高 40px、hover 行显操作、`:focus-visible` 环、`--el-table-*` 走新 token。
- 列宽按全幅重排：主题 `min-width 320` 且允许换行两行；关键词 `min-width 160`；状态 `width 120`；创建人 `width 120`；更新时间 `width 160`；操作 `width 88 fixed=right`（hover 显形）。
- 分页改右对齐（工具风惯例），保留 `layout="prev, pager, next, total"` 与服务端 `onPage` 语义。
- 逻辑零改动：`load` / `onFilterChange` / `onBatchDelete` / `onPage` / `onSelectionChange` / `fmtTime` 全部保留。

### 3.2 `StepPreview.vue`（分栏型）

```
┌ 上下文条: 面包屑「项目 / 预览」        保存状态 ·  [去发布 →]      ┐
├ 工具带(粘性): 主题▾ | 高亮▾ | ▢Mac ▢脚注 | 宽度◉ | ⟳复制 | 🖼配图  │
├──────────────────────────┬──────────────────────────────────────┤
│ pane-left                 │ PreviewPane                          │
│ ┌ pane-head: Markdown  N字┐│ ┌ pane-head: 主题名 渲染中…          ┐│
│ │                          ││ ┌──────────┐                       ││
│ │  MarkdownEditor (flex:1) ││ │ .phone   │  微信样式预览          ││
│ │  自滚,与右侧 @scroll 同步 ││ │  设备框   │                       ││
│ │                          ││ └──────────┘                       ││
│ └──────────────────────────┘└──────────────────────────────────────┘
└── 可拖拽分隔条 (resizable, 持久化到 localStorage) ────────────────┘
```

- **删除最外层 `el-card.step-card`**（`StepPreview.vue:2`）——工具风不做「一切包卡片」。
- 「Step 3 · 排版预览」标题与副标题移入 `ProjectLayout` 的步骤 rail / 面包屑（`usePageHeader`）。
- 「去发布」从底部 `.next-row`（`:79-82`）**上移到上下文条右侧**作为主 CTA；`.next-row` 删除。`canGoPublish` / `goPublish` 逻辑不变。
- `.duo` 改为**填满剩余高度**（`flex:1; min-height:0`）的双 pane，两侧各自 `overflow:auto`，中间可拖拽分隔条，默认 50/50，范围 30%–75%，宽度存 `localStorage['sparkora.previewSplit']`。
- 失效粘贴图 `el-alert`（`:36-42`）改为顶部条带，位置在工具带之下，**文案与判定逻辑零改动**。
- `PreviewToolbar` 组件本身**不改 DOM 结构**，只换皮（粘性定位、控件高度 28px、主题 select 宽度）。它的 `@900px` 断点删除。
- `PreviewImageDrawer` **不改**，仅去 `@900px` 断点。

### 3.3 `ProjectLayout.vue` 步骤 rail（随外壳一起改，非试点页内容）

```
┌────────────┬──────────────────────────────────────────────┐
│ ← 返回工作台 │  面包屑: 项目 / #12 · 比亚迪新车主           │
│            ├──────────────────────────────────────────────┤
│  ● 简报     │                                              │
│  ○ 版本     │              步骤内容（router-view）          │
│  🔒 预览     │                                              │
│  🔒 发布     │                                              │
│            │                                              │
│  [状态 tag] │                                              │
└────────────┴──────────────────────────────────────────────┘
```

- 200px 竖排 rail，替换顶部 `nav.steps-nav`（`ProjectLayout.vue:31-43` 与样式 `:150-193`）。
- 完成态打勾（沿用 `Check`）、锁定态上锁（`Lock`，`:disabled` + `cursor:not-allowed`）、当前态左侧 2px brand 竖条 + `--brand-weak` 底。
- 可达性仍由 `maxReachableStepOf` 语义驱动；`onStepClick`（`:120-124`）与 `STEPS` 数组**不变**。
- `loadProject` 的「中断重进自动定位」逻辑（`:105-110`）、`onErrorCaptured` 兜底（`:127-133`）、轮询 `watch`（`:114-117`）**全部零改动**。
- 页面标题区（`.page-header.project-head`，`:5-16`）并入 topbar 面包屑；状态 tag 下移至 rail 底部。

### 3.4 `ImageLibrary.vue`（网格型）

- 上下文条：面包屑「图库」+ 右侧 `共 N 张` 说明（`muted-small` 迁到 `usePageHeader` 的副标题位）。
- `ImageLibraryToolbar` 两段式改为**单行粘性工具带**（左：上传/新建/AI 生图；右：语义开关、关键词、来源/标签/项目/排序 select、密度切换）。控件高度 28px。
- `ImageCard.vue` **删除 `.mobile-bar`**（模板 + 样式），只保留 `.hover-panel`（`components/image/ImageCard.vue:121` 的 `prefers-reduced-motion` 块**保留**）。
- 网格：`--card-w` 变量驱动，`compact` 档 140px / 标准档 220px，`gap: 12px`，`align-items:start`（沿用现有 spec 的横竖图混排约定）。
- 逻辑零改动：`useImageFilters` / `useBulkSelect` / `useSemanticSearch` / `useImageLibraryOps` 的调用与 `activeChips`、`locateInList`、`syncRouteTag` 语义全部保留。

## 4. 兼容与迁移

### 4.1 不可破坏的类名契约

| 契约 | 位置 | 后果 |
|---|---|---|
| `PREVIEW_SELECTOR = '.wenyan-preview'` | `utils/wenyanRender.js:66`；主题 CSS 由 `#wenyan` 改写为该类（`:90`）注入 `document.head` | 重命名 → 15 套主题**全部静默失效**（预览变成无样式 HTML），构建与运行均不报错 |
| `sparkora-img-missing` | `utils/pendingImageStore.js` 产出，`PreviewPane.vue` 用 `:deep()` 消费 | 失效粘贴图占位失去样式，退化成裸文本 |
| `.card-list` / `.responsive-table` | `main.css:200-205` | 本次**主动删除**，唯一使用方 `ProjectList` 同步删除 |
| `.state-error` / `.state-title` / `.state-msg` / `.empty` / `.loading` / `.pager` | `main.css:177-187`，被 15+ 文件引用 | 改名会打断未迁移页面（批 2/3）。**本批保留类名，只换视觉** |
| `.container` / `.page-header` / `.page-kicker` | `main.css:132-159`，被 11 视图引用 | 同上，**本批保留类名待批 2/3 迁移**；`page-kicker` 仅试点页不再使用 |

### 4.2 未迁移页面的中间态（Q4 决策的直接后果）

token 全站生效后，批 2/3 未改造的 17 页会呈现「新色系 + 新密度 + 旧 1100px 居中窄栏」。这是**已知且接受**的中间态：观感优于现状（配色与密度已换），版式待后续批次补齐。不得为此回退 token 决策。

### 4.3 迁移顺序（关键路径）

1. `main.css` token v2（明暗双模）— 单独一次提交，立即可见全站变色。
2. `DesktopGuard` + `AppShell` + `nav.js` + `usePageHeader` — 引入外壳但页面内容不动。
3. `TopBar.vue` 删除，18 个引用点改为「不引（由 AppShell 提供）」。
4. `ProjectList` 迁移 + `.responsive-table`/`.card-list` 删除。
5. `StepPreview` 迁移 + `ProjectLayout` 步骤 rail。
6. `ImageLibrary` + `ImageCard` 迁移。
7. 规范同步（`.trellis/spec/frontend/index.md` + `AGENTS.md`）。

第 1 步与第 2 步之间必须能独立构建通过（便于二分定位回归）。

## 5. 关键取舍

| 取舍 | 选择 | 理由 | 代价 |
|---|---|---|---|
| 页面标题放哪 | topbar（`usePageHeader`）而非页面内 `page-header` | 消除双行标题，产出 Linear/Notier 式单工具带 | 需 provide/inject；未迁移页走兼容分支（§1.3） |
| 是否保留 `TopBar.vue` | 删除 | 外壳重写后它无独立职责，留着会诱导回退 | 18 处 import 需改（机械改动） |
| 预览分栏是否可拖拽 | 做，并持久化 | 分栏比例是创作工具的核心手感，固定比例会让宽屏浪费空间 | 多一个 localStorage 键与 pointer 事件处理 |
| 暗色模式 | 保留双模 | `store/theme.js` 零 viewport 依赖、已稳定；砍掉是功能回退 | token 验证面 ×2 |
| 是否引入 vitest | **不引入**（Q5 判定） | 本批是纯版式改动，功能逻辑在 `store/composables/utils` 完全不动；单测无法捕获布局类缺陷，视觉回归需截图基线（另开任务） | 无自动化网，靠 `npm run build` + 人工清单 + 逐提交 diff 审查兜底 |
| ⌘K 命令面板 | **不做**（Q4 判定） | 属新增功能，与版式正交；后加不需返工布局 | 「高级感」少一个杠杆，批 2/3 可补 |

## 6. 运维与回滚

- **无后端改动** ⇒ 无 DB 迁移、无 API 兼容问题、无回滚脚本需求。
- **回滚点**（按 §4.3 顺序，每步一个 commit）：
  | commit | 回滚后状态 |
  |---|---|
  | 1 token | 回到纸墨，全站可读 |
  | 2-3 外壳 | 回到 `TopBar` 顶栏，token 保留 |
  | 4-6 试点页 | 外壳与 token 保留，试点页回旧版式 |
  | 7 规范 | 无运行时影响 |
- 每步之间 `npm run build` 必须通过；任一步骤失败可单独 `git revert` 该 commit 而不必整体回滚。
- `.trellis/spec/frontend/index.md` 与 `AGENTS.md` 属**文档回滚不影响运行**，但必须与对应代码 commit 同步回滚，否则规范与实现脱节。
- 部署形态不变（`docker compose up -d --build` 或 `./dev.sh`），无新依赖、无 `vite.config.js` 变更（仅可能新增 `optimizeDeps` 白名单项，视实际用到的 Element Plus 组件而定）。
