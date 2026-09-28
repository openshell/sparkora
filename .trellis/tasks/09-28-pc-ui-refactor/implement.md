# implement.md — 批 1 执行计划

> 顺序即依赖。每步一个 commit，步与步之间可独立 `git revert`（见 design.md §6）。
> 前置：`task.py start` 前需用户批准最终规划摘要。

## 0. 启动前检查

- [ ] 确认工作区干净：`git status --porcelain`（当前有 1 处未提交改动，先确认是否属于本任务）
- [ ] 读 `.trellis/spec/frontend/index.md`（功能正确性约定必须存活，只改「页面骨架」与「移动端」两处）
- [ ] 起联调环境确认基线可跑：`./dev.sh start` → `curl -s localhost:5661/api/auth/me`（401 属正常）
- [ ] 基线截图：3 个试点页各存一张（改前对照物）

## 1. token v2 独立提交

- [ ] 重写 `frontend/src/assets/main.css` 的 `:root` 与 `html.dark` 两块：
  - 中性色阶 `--n-25…--n-900`（冷灰，暖偏移归零）
  - `--font-sans` 单一字族 + 新增 `--font-mono`；**删除衬线在 `h1/h2/h3/.serif` 上的生效**
  - `--fs-11/12/13/14/16/18/22/28` + `--lh-*`；`--control-h-sm/md/lg` = 28/32/40
  - `--radius-xs/sm/md/lg` = 3/4/6/8；`--shadow-1/2/3`
  - `--sp-1…--sp-8` = 2/4/6/8/12/16/24/32
  - `--focus-ring` 令牌 + 全局 `:focus-visible`
  - Element Plus 覆盖块跟随新值（`--el-border-radius-base: 6px`、`--el-font-size-base: 14px`、`--el-border-color*`、`--el-fill-color*`、`--el-bg-color*`、`--el-table-*` 密度）
- [ ] 保留既有语义别名（`--brand` `--ok` `--warn` `--err` `--ink` `--muted` `--faint` `--line` `--line-strong` `--card` `--paper`）以免 17 张未迁移页面破版；把它们重定义为新色阶的引用
- [ ] 删除：`@media (max-width:768px)` 全块、`.responsive-table` 规则、`.divider-label`（死代码）
- [ ] **暂留**（批 2/3 迁移用，design.md §4.1）：`.container` `.page-header` `.page-kicker` `.state-*` `.empty` `.loading` `.pager` `.step-card` `.topbar`
- [ ] 验证：`npm run build` ✅；明暗两套下 3 个试点页 + 5 张未迁移页肉眼检查

⚠️ 此步是全站可见变更，**单独 commit，不与任何其他步混合**。

## 2. PC-only 断点与外壳骨架

- [ ] 新建 `frontend/src/layouts/DesktopGuard.vue`：纯 CSS 驱动，`max-width:1279px` 时 `position:fixed; inset:0; z-index:9999` 不透明遮罩 + 「请使用桌面浏览器访问（建议窗口宽度 ≥1280px）」；同时该断点内 `body { overflow:hidden }` 消除横向滚动条
- [ ] 新建 `frontend/src/constants/nav.js`：`NAV_MODULES`（7 项）+ `NAV_SETTINGS`，含 `icon` / `require` 字段（design.md §2.1 已给完整定义）
- [ ] 新建 `frontend/src/composables/usePageHeader.js`：`providePageHeader` / `usePageHeader`（§2.2）
- [ ] 新建 `frontend/src/layouts/AppShell.vue`：
  - `body/#app` 改 `display:flex; height:100vh; overflow:hidden`（改 `App.vue` 或 `assets/main.css` 基元，二选一）
  - rail 220/56 可折叠 + `localStorage['sparkora_rail']`
  - topbar 44-48px：折叠钮 + 面包屑 + 右侧（主题切换 / 用户）
  - 顶层 `providePageHeader()`
  - `<slot />` 为 content
- [ ] `App.vue` 引入 `AppShell` + `DesktopGuard`；登录路由不套壳（`router.beforeEach` 加 meta 或按 `route.name==='login'` 条件渲染）
- [ ] `TopBar.vue` 的能力逐项迁入 AppShell：8 个模块入口 → rail；`desktop-only` 用户信息 → rail 底部；登出 → rail 用户区；`theme.toggle()` → topbar 右侧
- [ ] `nav.js` ↔ `router` 一致性 dev-only 断言（§2.1），`npm run dev` 下控制台无断言失败
- [ ] 验证：`npm run build` ✅；`/login` 免壳正常登录；8 个模块入口可达；明暗切换持久化

## 3. 清理 TopBar 引用

- [ ] 18 个 `import TopBar from ...` 引用点删除 import + 模板标签（`grep -rn "TopBar" frontend/src` 归零）
- [ ] 删除 `frontend/src/layouts/TopBar.vue`
- [ ] `main.css` 的 `.topbar` 规则删除
- [ ] 验证：`npm run build` ✅；逐路由目视无「双导航」（AppShell rail + 旧 page-header 并存是预期，见 design.md §1.3）

## 4. 试点页 A — `ProjectList.vue`（表格型）

- [ ] 删除 `.responsive-table` 包裹与 `.card-list` 模板块（`:33`、`:99-111`）
- [ ] 迁移到 `usePageHeader`：crumbs = `['项目', '创作项目']`，actions = `[{ label:'新建创作任务', type:'primary', icon:Plus, onClick }]`（`user.isEditorOrAbove` 门槛保持）
- [ ] 工具带：左搜索（240px）+ 状态/排序/方向；右批量删除；高度 28px 控件
- [ ] 表格：`--el-table-*` 走新 token，行高 40px，去竖线，hover 显操作，`:focus-visible` 环；列宽按全幅重排（§3.1）
- [ ] 分页右对齐（`.pager` 暂不改类名，只改样式）
- [ ] scoped 样式内所有字号/圆角/颜色改为 var()；删除本页 `@media`
- [ ] 逻辑零改动自查：`load` `onFilterChange` `toggleDir` `onBatchDelete` `onSelectionChange` `onPage` `fmtTime` 逐个确认未动
- [ ] 验证：`npm run build` ✅；1280/1440/1920/2560 四档无横滚无截断；筛选/排序/批量删除/分页实测

## 5. 试点页 B — `StepPreview.vue` + `ProjectLayout.vue`（分栏型 + 步骤 rail）

- [ ] `ProjectLayout.vue`：
  - 顶部 `nav.steps-nav` → 左侧 200px 竖排 rail；删除 `:150-193` 样式
  - 页面标题（`.page-header.project-head`）并入 `usePageHeader` crumbs = `['项目', '#' + id, topic]`，状态 tag 下移 rail 底部
  - `STEPS` 数组、`onStepClick`、`maxReachableStepOf`、`loadProject` 自动定位、`onErrorCaptured`、`watch` 轮询 —— **逐字不动**
  - 退役 `activeStepOf` / `routeStepIndex` 相关的顶部药丸逻辑，rail 内部按同映射表算当前步
- [ ] `StepPreview.vue`：
  - 删最外层 `el-card.step-card`；删 `@media (max-width:900px)`
  - 「去发布」从 `.next-row` 移到 `usePageHeader` actions（`canGoPublish` 逻辑不变）
  - `.duo` → `flex:1; min-height:0` 双 pane 填满剩余高度 + 可拖拽分隔条（30%–75%，`localStorage['sparkora.previewSplit']`）
  - 失效图 `el-alert` 改顶部条带，**文案与判定零改动**
  - `PreviewPane` 保留 `class="wenyan-preview"`（`PreviewPane.vue:23`）——**重构前先 grep 确认**
- [ ] `components/preview/PreviewToolbar.vue`：粘性定位 + 控件 28px + 删 `@media`；**DOM 结构不改**（`StepPreview` 依赖其 10 个 `@update:*` 事件）
- [ ] `PreviewImageDrawer` / `AiImageDrawer`：只删 `@media`，不改结构
- [ ] 验证：`npm run build` ✅；wenyan 主题抽查 2 套（1 内置 + 1 社区 `custom:*`）渲染正常；编辑↔预览滚动同步正常；`sparkora-img:` token 粘贴图预览可见；拖拽分隔条刷新后保持

## 6. 试点页 C — `ImageLibrary.vue`（网格型）

- [ ] 迁移到 `usePageHeader`（crumbs = `['图库']`，副标题 `共 N 张…`）
- [ ] `ImageLibraryToolbar` 改单行粘性工具带，控件 28px；`ImageSemanticBar` / `bulk-bar` / `chip-row` 改为工具带下方的条带层
- [ ] `components/image/ImageCard.vue` **删除 `.mobile-bar`**（模板 + 样式），保留 `.hover-panel`；**保留 `@media (prefers-reduced-motion)`**
- [ ] 网格：`--card-w` 变量驱动（compact 140 / 标准 220），`gap:12px`，`align-items:start`
- [ ] `ImageLibraryToolbar` / `ImageSemanticBar` / `ImageTagDialog` / `ImageBulkTagDialog`：删 `@media`，只换皮
- [ ] 逻辑零改动自查：`useImageFilters` / `useBulkSelect` / `useSemanticSearch` / `useImageLibraryOps` 的调用点、`activeChips` / `locateInList` / `syncRouteTag` 语义
- [ ] 验证：`npm run build` ✅；上传/新建/AI 生图入口可达；筛选 chip 增删回写 URL；语义搜索进出；批量全选/打标/删除

## 7. 规范同步

- [ ] `.trellis/spec/frontend/index.md`：
  - `:11` Overview 的「移动端优先响应式」→ 改为 PC-only（最小宽度 1280，低于则遮罩不降级）
  - `:92-110` 「页面骨架统一」→ 改为 AppShell + `usePageHeader` 骨架
  - `:114` 「移动端 768px / 44px 触控目标」→ 删除，替换为 `--control-h-*` 密度刻度 + `:focus-visible` 要求
  - **新增**「不可破坏类名契约」条目（`.wenyan-preview` / `sparkora-img-missing`），说明来源与后果
  - 其余条目（API 层 `R<T>` 拆包、建议链双写、正文 token、插图计数真源、原图预览、上传扩展名、ObjectURL 计数、防抖清理、取消语义分层）**一字不动**
- [ ] `AGENTS.md` Conventions 中「移动端单列、触控目标 ≥44px」→ 改为 PC-only + AppShell 表述
- [ ] `docs/` 4 处「移动端优先」表述（grep 定位，改前复核行号）：
  - [ ] `docs/README.md:12` 前端技术栈行
  - [ ] `docs/spec/overview.md:13` 「前端适配」小节（Element Plus 断点描述一并改）
  - [ ] `docs/spec/overview.md:34` 架构决策表「移动端适配」整行
  - [ ] `docs/spec/overview.md:195` 「前端响应式适配」表述
  - 收尾验证：`grep -rn "移动端优先\|移动端单列\|触控目标" docs/ AGENTS.md .trellis/spec/frontend/` 期望 0（功能正确性语境除外）
- [ ] 跑 `sparkora-spec-check` 核对新骨架不被判违规

## 8. 收尾

- [ ] `npm run build` ✅（最终）
- [ ] `git status` 确认无遗留调试代码 / 无 console.log 残留
- [ ] 交付**逐页人工验收清单**（AC6）：每页列 路由 / 控件清单 / 预期版式 / 勾选框；覆盖 3 试点页 + 3 个未迁移页做对照 + `/login`
- [ ] 四档宽度（1280/1440/1920/2560）× 明暗双模 各过一遍 AC2/AC3
- [ ] 后端零改动的证据：`git diff --stat` 不含 `src/`；无需 `mvn test`
- [ ] 走 Phase 3：spec 已更新（§7）→ commit → wrap-up

## 风险文件（改动即高危）

| 文件 | 风险 | 缓解 |
|---|---|---|
| `utils/wenyanRender.js` | 不改，但**下游类名改动会静默破坏 15 套主题** | 任何涉及 `.wenyan-preview` 的改动前后 grep 确认；渲染必测 1 内置 + 1 社区主题 |
| `components/MarkdownEditor.vue` | CodeMirror 实例 + 滚动同步 | **不改**；分栏改造只动外层容器尺寸 |
| `utils/pendingImageStore.js` + `PreviewPane` | `sparkora-img-missing` 类名跨文件耦合 | 保留类名；贴图预览/发布两链路各测一次 |
| `views/project/ProjectLayout.vue` | 状态机 UI 唯一持有点 | `loadProject`/`onStepClick`/轮询 `watch`/`onErrorCaptured` 逐字保留，改后 diff 人工确认 |
| `assets/main.css` | 全站生效，一次改错影响 21 页 | 单独 commit；明暗双套逐档目视 |
| `components/preview/PreviewToolbar.vue` | 10 个 `@update:*` 事件被 `StepPreview` 绑定 | **不改 DOM 结构**，只换皮肤 |
| `store/project-detail.js` | — | 零改动，改即越界 |

## 验证命令

```bash
# 每步之后
cd frontend && npm run build

# 全站移动端断点清零（AC2）
grep -rn "@media (max-width" frontend/src | grep -v prefers-reduced-motion   # 期望 0
grep -rn "@media (min-width" frontend/src                                      # 期望 0

# TopBar 清零（步骤 3）
grep -rn "TopBar" frontend/src                                                 # 期望 0

# 移动端死分支清零
grep -rn "card-list\|responsive-table\|mobile-bar\|desktop-only\|divider-label" frontend/src  # 期望 0

# 契约类名存活（AC5）
grep -n "wenyan-preview" frontend/src/utils/wenyanRender.js frontend/src/components/preview/PreviewPane.vue
grep -rn "sparkora-img-missing" frontend/src

# 字体/圆角字面量抽查（AC3，改完后应只剩 main.css 的 token 定义块）
grep -rn "font-size: [0-9]" frontend/src --include=*.vue | grep -v "var(--fs" | head

# 后端零改动
git diff --stat -- src/                                                        # 期望空

# 联调
./dev.sh restart backend   # 仅当误改后端（预期不需要）
./dev.sh logs frontend -f
```

## 明确的非目标（本批不做）

- 其余 17 页的版式重做（批 2/3）
- ⌘K 命令面板 / 全局快捷键（批 2/3 可加，不需返工布局）
- vitest / 视觉回归基建（另开任务）
- `vite.config.js` 路径别名（无需求；全相对路径沿用）
- 品牌 logo / 插画 / 字体授权
- 后端任何改动
