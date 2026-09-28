# PC 端 UI 重构为专业生产力工作台

## 1. Goal 与用户价值

把 Sparkora 前端从「移动优先的居中单栏向导」重构为 **Linear / Notion 类的高密度全幅生产力工作台**，让平台在 PC 上读起来像一个成熟的商业内容生产工具，而不是一个套了 Element Plus 的后台管理系统。

用户价值：当前全站 20+ 页面共用同一个「TopBar + 1100px 居中容器 + page-header + 卡片竖排」骨架，没有空间分工，导致信息密度低、操作路径长、缺乏专业工具的秩序感。重构后每个页面应能在 2560×1440 与 1440×900 上充分利用横向空间，核心信息（资产、正文、属性、状态）同屏可见。

## 2. Background / 已确认事实（代码证据）

### 2.1 当前版式架构的病灶

| 事实 | 证据 | 影响 |
|---|---|---|
| 全站页面共用居中单栏容器 `max-width: 1100px` | `frontend/src/assets/main.css:132-136`；被 11 个视图引用 | 宽屏下内容缩成窄带浮在空白中 |
| 没有全局外壳，只有 `TopBar`（8 个平铺 router-link） | `frontend/src/layouts/TopBar.vue:4-13` | 无模块导航栏、无上下文区、无空间分工 |
| 34 处 `@media` 断点（`768px`×28、`900px`×4、`640px`×1、`prefers-reduced-motion`×2） | 全 `frontend/src` 扫描 | 大量 `min-height:44px` 与 `font-size:16px`（iOS 缩放补丁）污染桌面侧密度 |
| `.responsive-table` 双 DOM：`el-table` 与 `.card-list` 同时渲染，仅靠 CSS 切换 | `main.css:200-205`，唯一使用方 `ProjectList.vue:68-111` | 列表页 DOM 翻倍 |
| 另有移动端专用分支：`ImageCard.vue` 的 `.mobile-bar`/`.hover-panel`、`TopBar.vue` 的 `.desktop-only`、`LoginView.vue:108` 隐藏品牌侧栏 | — | 桌面路径被移动端路径稀释 |
| 全站仅 2 个页面有真实分栏 | `QaChat.vue:295`（`280px 1fr`）、`StepPreview.vue:481`（`5fr/7fr`） | 其余 18+ 页都是竖排卡片流 |
| 项目流程用顶部药丸步骤条表达，`overflow-x:auto` 横向滑动 | `ProjectLayout.vue:31-43, 150-159` | 移动端「一屏一件事」模式；专业创作者需要常驻工作台 |
| 字号散落 11/12/13/14/16/19/22/24/26px，无比例关系 | `main.css` 与各 scoped 样式 | 无字阶，视觉节奏散 |
| 圆角 14px/10px、Element Plus 默认控件高 32px 外露 | `main.css:31-32, 49` | 「后台管理系统」观感 |

### 2.2 稳定层（重构不得触碰）

`store/`（3 个）、`api/`（2 个）、`composables/`（9 个）、`utils/`（8 个）、`constants/project.js`、`router/index.js`。

- 9 个 composable 全部零 viewport 耦合（无 `matchMedia` / `ResizeObserver` / `getBoundingClientRect`）。
- `store/project-detail.js` 持有全部项目数据域与轮询，暴露 `ensureProject/ensureBrief/ensureVersions/ensureStyles/ensureImitation`、`startPolling/stopPolling`、`invalidate`、`patchProject`、`parseBrief`。
- `api/index.js` 10 个分组约 75 个方法，纯 axios 封装；发布调用 300s 超时（nginx 须 ≥300s）。

### 2.3 风险点（相邻耦合，重构易踩）

- `utils/wenyanRender.js` 通过 `PREVIEW_SELECTOR`（`.wenyan-preview`）向 `document.head` 注入 `<style>`；**重命名该类会静默破坏预览渲染**。
- `utils/pendingImageStore.js` 产出 `sparkora-img-missing` 占位类，`PreviewPane.vue` 用 `:deep()` 消费；两者必须同步。
- `MarkdownEditor.vue` / `PreviewPane.vue` 的滚动同步是全站唯一真实 DOM 测量代码。
- `constants/project.js` 的 `activeStepOf` / `maxReachableStepOf` 是向导形状（step 0-3、封顶 3），若改为「常驻工作台」需重新解释或退役；`isGenerating*` / `isPublishable` / `statusLabel` / `statusTagType` 可原样复用。状态机本体在后端 `ProjectStatusService`，**改版式不动任何后端契约**。

### 2.4 质量基线（重大风险）

- 前端**零测试**：无 `frontend/tests`、无 `*.spec.js`、无 vitest/playwright 配置、无前端测试依赖。
- `package.json` 的 `test` 脚本是 `vite build` 的别名，不是真测试。
- 根 `pom.xml` 无前端插件，`mvn test`（510 个 Java 用例）不覆盖前端。
- 唯一前端门禁 = `npm run build`。

结论：全站版式重做**没有自动化 UI 回归网**。已决（Q5）：本批**不引入** vitest——版式改动不动 `store`/`composables`/`utils` 的功能逻辑，单测捕获不到布局缺陷而视觉回归需截图基线（另开任务）。兜底手段 = `npm run build` + 逐提交 diff 审查 + 逐页人工验收清单（AC6）。

### 2.5 其他事实

- 依赖：`element-plus ^2.8.1`、`vue ^3.4.38`、`pinia ^2.2.2`、`@element-plus/icons-vue ^2.3.1`、`codemirror ^6`、`@wenyan-md/core ^3.0.11`、`markdown-it ^15`。
- **无动画库、无 CSS 框架、无 Tailwind**；`unplugin-auto-import` + `unplugin-vue-components`（ElementPlusResolver）自动引入。
- `vite.config.js` **未配置路径别名**（全相对路径 `../..`）；`optimizeDeps.include` 有 `el-*` 样式白名单，新增组件需同步。
- `main.css` 的 `.divider-label` 已是**死代码**（零引用）。
- 后端：Spring Boot 3.3.4 / Java 21 / MyBatis-Plus / PostgreSQL；本任务**不涉及后端**。

### 2.6 项目自身规范固化了当前骨架（必须同步改）

`.trellis/spec/frontend/index.md` 把"不好看"的那套版式写成了**硬性约定**：

- `:11` Overview：「移动端优先响应式；不引 Vant 等额外移动端框架。」
- `:92-110` 「Convention: 页面骨架统一」**逐字给出** `<TopBar /><div class="container"><div class="page-header">` + `page-kicker`（English Kicker）+ 中文标题，并要求"三态齐全（loading 骨架 / error 重试 / empty）"。
- `:114` 「移动端：`@media (max-width: 768px)`；可点元素/按钮 `min-height: 44px`（全局 `main.css` 已给 `.el-button` 兜底）」。
- `AGENTS.md` Conventions 同样写着「前端页面用 Element Plus（`el-form` + rules 校验，**移动端单列、触控目标 ≥44px**）」。

⇒ 不改这两处规范，重构产物会被 `sparkora-spec-check` 判为违规，且后续任何新页面都会按旧骨架写回去、逐步回退。**规范更新是本任务的交付物之一，不是可选项。**

注意：该 spec 其余大量条目（API 层 `R<T>` 拆包、建议链双写、正文 token、插图计数单一真源、原图 URL 预览、上传扩展名补全、ObjectURL 引用计数、防抖清理、取消语义分层等）是**功能正确性约定，与版式无关，重构必须原样存活**。只有「Page Conventions / 页面骨架」与「移动端」两条需要改写。

## 3. Requirements

### R1 全局外壳 AppShell（新增）
- 新增 `layouts/AppShell.vue`，提供：左侧模块导航栏（图标 + 文本，8 个模块，与 `TopBar` 现有一一对应）、主内容全幅插槽、用户区（用户名 / 角色 / 登出 / 明暗切换）。
- 模块导航项与 `router/index.js` 现有路由**一一对应且不新增/删除业务路由**；角色门槛沿用现状（`isEditorOrAbove` 控风格库与设置，其余 `isLoggedIn`）。
- `AppShell` 替代 `TopBar` 被各页直接 import 的现状；**`TopBar.vue` 直接删除**（外壳重写后它无独立职责，留着会诱导回退），其 8 个模块入口 / 用户信息 / 登出 / 明暗切换能力逐项迁入 AppShell。
- 登录页**不套** AppShell（保持 `LoginView.vue` 现有全屏品牌+表单结构）。
- 页面标题与主操作由页面通过 `usePageHeader()`（provide/inject）交给外壳的上下文条渲染；**未迁移页面 `inject` 到 `null` 时继续渲染自己的 `page-header`**，作为批 2/3 前的兼容路径（详见 design.md §1.3）。

### R2 PC-only 断点策略
- 全站最小可用宽度 **1280px**（`--min-app-width` token）。低于该宽度时 `DesktopGuard` 显示「请使用桌面浏览器访问」遮罩，**不做重排降级**。
- 删除全部 `max-width: 768px` / `900px` / `640px` 断点块与 `min-width:769px` 块。
- 保留 `prefers-reduced-motion` 块（无障碍，非移动端相关）。
- 删除 `main.css` 的移动端覆写：`el-button{min-height:44px}`、输入框 `font-size:16px`、`container` 移动 padding、`page-header h2` 缩字号、`.pager{flex-wrap}`。

### R3 设计 token v2（Q1 已决：整体替换为工具风）
**决策**：放弃「纸墨」编辑美学，改为 Linear/Notion 类中性冷灰工具风。纸墨相关的暖色 token（`--paper:#faf9f7` 暖纸白、暖灰 `--line:#e7e2da`、衬线刊头 `Noto Serif SC`、`page-kicker` 杂志标签）**全部废弃**，不保留为第二主题。

- 中性色改为**冷灰中性**（近 `slate`/`zinc` 色相，明度分 8-10 档），暖色偏移归零。
- 标题**取消衬线**，改 `--font-sans` 单一字族；`page-kicker` 英文大写标签的杂志感处理掉（改为普通小号灰标签或直接去掉）。
- 收敛为三级刻度并全部走 CSS 变量，组件只引用变量：
  - 密度：`--control-h-sm/md/lg` = 28/32/40px，替代 Element Plus 默认 32px。
  - 字阶：`--fs-11/12/13/14/16/18/22/28` 固定 8 级 + 对应 `--lh-*`，替换散落的 9 档字号。
  - 圆角：`--radius-sm/md/lg` = 4/6/8px（原 10/14px），锐利工具化。
  - elevation：`--shadow-1/2/3` 三级（原 `--shadow-card/hover/pop`），降低阴影浓度，主要靠 1px 细线分隔。
  - 品牌色：现有墨蓝 `#4f46e5` 系保留但降饱和/收窄使用面（仅主按钮、选中态、focus 环），不再铺渐变（`--brand-gradient` 考虑删除）。
- 明暗双模 token 必须**同步重写**（`html.dark` 块），保证新刻度在两套下都可读。
- Element Plus 主色覆盖（`--el-color-*`）保留并跟随新 brand token；额外需覆盖 `--el-border-radius-base` 等以对齐新圆角。

### R4 页面级版式重做
按「高密度 + 全幅 + 主从分栏」原则逐页重做模板与 scoped 样式。目标形态：

| 页面 | 目标版式 |
|---|---|
| `ProjectList.vue` | 保留 `el-table`（可加行悬浮操作/密度档），删除 `.card-list` 分支与 `.responsive-table` |
| `ImageLibrary.vue` | 工具条常驻 + 网格；`ImageCard.vue` 删除 `.mobile-bar`，只留 `.hover-panel` |
| `QaChat.vue` | 已是 `280px 1fr`，升级为外壳内的主从布局（会话列表可折叠、列宽可调） |
| `StepPreview.vue` | 已是 `5fr/7fr`，升级为画布居中 + 右检查器（属性常驻） |
| `ProjectLayout.vue` + 4 个 `Step*.vue` | 步骤导航从顶部药丸改为**左侧竖排 rail**（Q3 已决，路由与 view 拆分不变）；主区全幅 |
| `CarLibrary/CarSync/CarDetail.vue` | 主从分栏：列表 + 右侧详情面板（`CarDetail` 从独立页并入） |
| `KbLibrary / StyleLibrary / knowledge/*.vue` | 列表 + 抽屉/详情双栏 |
| `SettingsView.vue` | 取消 720px 窄卡，改分组全幅表单 + 左内导航 |
| `ProjectEdit.vue` | 分区表单改全幅双列 |
| `LoginView.vue` | 基本保留，仅去 `@media` |

### R5 移动端资产清理
分两段落地：随试点页同批删除的（批 1 内），与随页面迁移删除的（批 2/3）。

- 批 1 内：`ProjectList.vue:99-111` 的 `.card-list` 模板块、`ImageCard.vue` 的 `.mobile-bar` 模板与样式、`main.css` 的 `.responsive-table` 与 `.divider-label`（死代码）。
- 批 1 外：`TopBar.vue` 的 `.desktop-only`（随 `TopBar.vue` 整体删除）、`LoginView.vue` 的 `<900px` 断点（批 3 随该页迁移）。

### R6 键盘可达性（专业感的低成本杠杆）
- 全站可交互元素具备可见 `:focus-visible` 焦点环（token `--focus-ring`），随 R3 token 批 1 落地。
- 关键表单支持 Enter 提交（`@keyup.enter`）——按页在批 2/3 补齐。
- **已决（Q4）：不引入 ⌘K 命令面板与全局快捷键。** 属新增功能、与版式正交，后加无需返工布局；留待批 2/3 或独立任务。

### R7 质量门禁
- 前端改动必须 `npm run build` 通过（仓库既有约定）。
- 后端零改动，故不要求 `mvn test`；但需确认 `npm run build` 不因移除 media 块而报错。
- 因无自动化 UI 测试，本任务**必须产出一份逐页人工验收清单**（见 §5 AC）。

### R8 规范同步（交付物）
- 改写 `.trellis/spec/frontend/index.md`：「Page Conventions / 页面骨架统一」改为 AppShell 骨架；删除 `:11` 与 `:114` 的移动端优先表述，改为 PC-only 断点策略。
- 改写 `AGENTS.md` Conventions 中「移动端单列、触控目标 ≥44px」表述。
- 改写 `docs/` 4 处「移动端优先」规格（`docs/README.md:12`；`docs/spec/overview.md:13`、`:34` 架构决策表行、`:195`），与 `docs/spec/overview.md:13` 的 Element Plus 断点描述一并改为 PC-only + AppShell。
- 该 spec 其余功能正确性条目（API 层、token、计数真源、取消语义等）**原样保留**。

## 4. Out of Scope

- **后端任何改动**：API 契约、状态机、Flyway 迁移、服务层一律不动。
- **移动端适配**：已决定暂缓，不做响应式降级，不做独立移动端项目（`docs/` 中如需说明另行记录）。
- **业务功能新增**：不改功能、不改流程、不加新页面。
- **引入重型 UI 框架**（Tailwind / Naive UI 等）或新动画库（Q1/Q2/Q3 已决为纯版式改造，不引入）。
- **`package.json` 的 `test` 脚本修正**与前端测试基建（Q5 已决本批不做；另开任务）。
- **⌘K 命令面板 / 全局快捷键**（Q4 已决本批不做）。
- **品牌视觉资产**（logo、插画、字体授权文件）。
- 主题（wenyan）CSS 体系本身——只保证预览容器不被破坏。

## 5. Acceptance Criteria

**AC1 外壳**
- 8 个模块均可从左侧导航栏直达，当前模块有明确选中态；导航项与 `router/index.js` 路由集合完全一致。
- 1440×900 与 2560×1440 下，内容区占满可用宽度，不再出现 1100px 居中窄带。

**AC2 PC-only**
- `grep -r "@media (max-width" frontend/src` 结果为 0（`prefers-reduced-motion` 除外）。
- 1280px 以下出现"请使用桌面浏览器"提示，不再重排。
- 1280 / 1440 / 1920 / 2560 四档宽度下，R4 表格中每页无横向滚动条、无内容截断、无元素重叠。

**AC3 Token**
- 字号、圆角、控件高、间距在 `frontend/src` 内不再出现字面量（`main.css` token 定义块除外）；抽查 5 个页面确认只引用变量。
- 明暗两套主题下全站文字/边框/背景对比度可读，无浅色下白字或暗色下黑字。

**AC4 页面**
- R4 表格中每个页面均改为目标版式；`ProjectList` 无 `.card-list`；`ImageCard` 无 `.mobile-bar`。
- 现有功能零回归：项目创建/生成/编辑/预览/发布全流程可走通；图库上传、筛选、批量打标、语义搜索可用；问答、车型、知识库 CRUD 可用。

**AC5 关键契约不破**
- 预览页 wenyan 主题渲染正常（含 8 内置 + 7 社区主题抽查 2 个），`sanitizeWenyanHtml` 生效。
- 正文 `sparkora-img:` token 插图在预览与发布两条链路均正常；缺图占位样式生效。
- `ProjectLayout` 的生成中轮询、10min 陈旧自愈相关 UI 提示正常；中断重进自动定位到最新流程节点的行为不变。

**AC6 门禁**
- `npm run build` 成功。
- 交付一份逐页人工验收清单（每页：路由、控件清单、预期版式、勾选框），供人工过一遍。
- `.trellis/spec/frontend/index.md`、`AGENTS.md`、`docs/README.md`、`docs/spec/overview.md` 已更新为新骨架约定；跑一次 `sparkora-spec-check` 不再把新骨架判为违规，且 `docs/` 内无残留「移动端优先」表述。

## 6. 已决决策（用户裁定，均 09-28）

| # | 决策点 | 结论 | 影响 |
|---|---|---|---|
| Q1 | 基础视觉语言 | **整体替换为 Linear/Notion 类中性冷灰工具风**；放弃「纸墨」编辑美学，**不保留第二主题** | R3 全部取值；衬线标题、暖纸色、英文 kicker 标签全部废弃 |
| Q2 | 改造节奏 | **分批**。批 1 = token v2 + AppShell + 3 个试点页 → 人工肉眼验收 → 再铺开 | 决定本文件 §7 的批 1 范围；试点未过不铺开 |
| Q3 | 项目流结构 | **保留 4 步路由分页**与后端状态机不变；仅把顶部药丸步骤条改为**左侧竖排 rail**；不做多面板合并工作台 | `router/index.js` 零改动、4 个 `Step*.vue` 不合并 |
| Q4 | 键盘优先 | **不引入** ⌘K 命令面板与全局快捷键；只做 `:focus-visible` 焦点环（批 1） | R6 缩到最小；后加无需返工布局 |
| Q5 | 质量兜底 | **不引入** vitest / 视觉回归基建（另开任务）；靠 `npm run build` + 逐提交 diff + 逐页人工清单 | R7 只保留 build 门禁 + AC6 清单 |
| Q6 | 主题数量 | **保留明暗双模**（`store/theme.js` 零 viewport 依赖且已稳定，砍掉是功能回退） | R3 token 验证面 ×2 |
| Q7 | 新 token 生效范围 | **全站一次性生效**——直接重写 `:root` / `html.dark`，不做作用域隔离 | 批 2/3 未迁移的 17 页在批 1 期间处于「新色系 + 旧 1100px 窄栏」的已知中间态（design.md §4.2） |

## 7. 分批计划（Q2 决定）

### 批 1（本次实施范围）
1. R3 token v2（明暗双模全量重写）
2. R1 AppShell 外壳 + `usePageHeader` + `nav.js` + R2 PC-only 断点与 `DesktopGuard`
3. `TopBar.vue` 删除 + 全站 18 处引用清理
4. R4 三个试点页改造：`ProjectList`（表格型）/ `StepPreview`（分栏型）/ `ImageLibrary`（网格型），含 `ProjectLayout` 步骤 rail
5. R5 批 1 内清理项 + R6 焦点环
6. R8 规范同步（`.trellis/spec/frontend/index.md` + `AGENTS.md`）
7. 交付 AC6 的人工验收清单

试点页选择理由：三者分别是「密集表格」「画布 + 检查器」「卡片网格」三种版式原型，能一次性验证 token 密度、AppShell 分栏比例、悬停/焦点态在各形态下是否成立。

### 批 2 / 批 3（后续任务，范围待试点结论确定）
- 批 2：项目流其余页（`StepBrief` / `StepVersions` / `StepPublish`、`ProjectEdit`）、`QaChat`、R6 的 Enter 提交补齐。
- 批 3：车型域（`CarLibrary` / `CarDetail` / `CarSync`）、知识域（`KnowledgeCenter` / `knowledge/*` / `KbLibrary` / `StyleLibrary`）、`SettingsView`、`LoginView`。
- 批 2/3 期间逐步退役 `main.css` 中为兼容未迁移页保留的旧类（`.container` / `.page-header` / `.page-kicker` / `.state-*` / `.empty` / `.loading` / `.pager` / `.step-card`），最终只剩 AppShell 体系。
- 独立任务（与批次无关）：前端测试基建（vitest 或视觉回归）、⌘K 命令面板。
