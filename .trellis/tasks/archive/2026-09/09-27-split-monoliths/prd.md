# 拆巨石（后端 Controller + 前端大组件）

## Goal

降低三个巨石文件的复杂度，纯结构重构、**零行为变化**：

1. `ArticleProjectController.java`（555 行，25 个端点，构造注入 10 个依赖）→ 按子域拆成多个薄控制器，每个只注入自身所需依赖。
2. `frontend/src/views/project/StepPreview.vue`（994 行）→ 抽子组件 + composable，父组件显著瘦身。
3. `frontend/src/views/ImageLibrary.vue`（908 行）→ 抽子组件 + composable。

## Background（研究证据，详见 `.trellis/tasks/09-27-split-monoliths/research/monolith-inventory.md`）

### 后端 ArticleProjectController（555 行）

- `@RequestMapping("/api/projects")`；25 个 handler，10 个构造依赖（`mapper/briefService/versionService/carService/matcherService/imageService/previewService/publishService/imitationService/suggestionService`）。
- 文件内已有 `// ====================` 子域分段注释：Project CRUD / Brief / Imitation / Versions / Images / Illustration suggestions / Preview-publish bridge / Preview / Publish。
- **已存在拆分先例**：`DeepController` 独立挂 `/api/projects/{projectId}/deep`，自带依赖集，无共享基类。
- 15/25 端点是薄透传（1-3 行委托 + try/catch）；10 个含真实逻辑。
- 错误映射为控制器本地 try/catch（`ApiExceptionHandler` 只兜框架层）：`IllegalArgument→400`、`IllegalState→409`、`NotReady→409`、其他→500；项目不存在→显式 `R.fail(404)`（重复 7 处）。
- 每方法带 `@PreAuthorize`：读 `ADMIN/EDITOR/VIEWER`、写 `ADMIN/EDITOR`、`delete` 仅 `ADMIN`；全部返回 `R<T>`。
- 私有静态助手 `stringListOf`/`doubleOf` 供 illustration 端点使用。
- **已知不一致（保持现状，不在本任务修）**：`preview` 端点把 `IllegalStateException` 映射为 400（其余子域映射 409）——纯搬迁须**逐字保留**。

### 后端包/控制器现状

- `web/controller` 扁平存放 11 个类（含 `ApiExceptionHandler`）；无子包。
- 服务层：`com.sparkora.service`（17 个，扁平）+ 各新域子包（`car.service`/`deep.service`/`kb.service`/`news.service`/`qa.service`/`image.embed` 等）。
- `docs/spec/project-lifecycle.md`、`docs/spec/preview.md`、`docs/spec/publish.md`、`docs/spec/image.md`、`docs/spec/imitation.md`、`docs/spec/version-generation.md` 以 `ArticleProjectController` 为权威代码路径——拆分后需同步。

### 前端 StepPreview.vue（994 行；template 1-266 / script 268-826 / style 828-994）

- 已导入子组件：`MarkdownEditor.vue`(205)、`AiImageDrawer.vue`(589)；store：`useProjectDetailStore`；utils：`wenyanRender.js`。
- 模板块：工具栏 22-90、双栏 100-148（左 `MarkdownEditor`、右手机预览 120-147）、图片抽屉 `el-drawer` 156-264（图库/AI 生图/智能建议三 tab）。
- script：大量 ref（加载/渲染/保存/主题/图库分页/建议）、6 个 watch、`onMounted`/`onBeforeUnmount`。
- 内聚可抽簇：文库分页（333-376，最独立）、智能建议（321-331 + 653-768）、渲染编排（413-448）、样式持久化（453-488 + 776-789）、滚动同步（495-511）、工具栏、预览窗、图片抽屉。
- 跨切状态须留父或上移：`contentMd/originalMd`、`editorRef`、`imgSnapshot`、`projectId`、`saveState`、`theme/highlight/macStyle/footnote`。

### 前端 ImageLibrary.vue（908 行；template 1-266 / script 268-774 / style 776-907）

- 已导入：`TopBar.vue`(76)、`AiImageDrawer.vue`；store：`useUserStore`；utils：`imageRefCache.js`。
- 模板块：工具栏 13-76、语义条 78-87、批量选择条 89-96、过滤 chips 98-104、卡片网格 133-222（卡片体 136-215 最重）、`AiImageDrawer` 224-226、标签编辑对话框 228-241、批量标签对话框 243-260。
- 内聚可抽簇：语义搜索（325-392）、批量选择（401-439）、过滤+路由同步（441-539）、来源追踪（478-499）、上传（602-630）、卡片重生成（653-699）、标签对话框（724-770）、卡片渲染、工具栏。

### 前端现状与约定

- **`frontend/src/components/` 仅 2 个文件**（`AiImageDrawer.vue`/`MarkdownEditor.vue`）；**`frontend/src/composables/` 不存在**（零 composable）；store 3 个（`project-detail.js`/`theme.js`/`user.js`）。
- 全量 `<script setup>`；`defineProps` 对象式、`defineEmits` 数组式、`defineExpose` 暴露命令式方法；Element Plus `el-*` 自动引入但**图标必须显式 import**；API 一律走 `src/api/index.js` 具名导出，**禁止 `.vue` 直调 `http`**；动态 `<component :is>` 需显式 import（先例 `AiImageDrawer`）。
- 唯一「从巨石抽出共享组件」先例：`AiImageDrawer.vue`（StepPreview + ImageLibrary 共用）。
- `StepBrief.vue`(647)/`StepVersions.vue`(485)/`StepPublish.vue`(436) 均未拆分。

## Requirements

### R1 后端控制器按子域拆分（纯搬迁）

- 把 `ArticleProjectController` 的 25 个端点按子域拆到多个控制器（保留 `ArticleProjectController` 名承载 Project CRUD），新增控制器挂 `@RequestMapping("/api/projects/{projectId}")`（对齐 `DeepController` 先例）或独立前缀。
- **路径/方法/`@PreAuthorize` 角色矩阵/返回类型/异常映射逐字不变**；私有助手随使用者迁移。
- 每个新控制器只注入其子域所需依赖。
- 可选（同任务内）：把 `publishOptions` 的 ~30 行 map 组装下沉到 service，使发布控制器更薄。

### R2 StepPreview.vue 拆分

- 抽 composable：`usePreviewLibrary`（分页，最独立）、`usePreviewSuggestions`（建议）、`usePreviewStylePersist`（主题持久化，含 debounce+flush）、可选 `usePreviewRender`。
- 抽子组件：`PreviewToolbar`、`PreviewPane`、`PreviewImageDrawer`。
- 父组件保留跨切状态（`contentMd`/`editorRef`/`imgSnapshot`/`saveState`）与编排；子组件用 props/emits 交互。

### R3 ImageLibrary.vue 拆分

- 抽 composable：`useSemanticSearch`、`useBulkSelect`、`useImageFilters`（过滤+路由同步）。
- 抽子组件：`ImageCard`、`ImageLibraryToolbar`、`ImageTagDialog`、`ImageBulkTagDialog`（+ 可选 `ImageSemanticBar`）。

### R4 约定与文档同步

- 新前端文件遵循现有约定（`<script setup>`、对象式 props、数组式 emits、API 走 `api/index.js`、图标显式 import、scoped style + CSS 变量）。
- 更新受影响的 `docs/spec/**` 与 `.trellis/spec/**` 中对巨石类/文件路径的权威引用。

## Acceptance Criteria

- [ ] AC1 `mvn -q -DskipTests compile` 通过。
- [ ] AC2 `mvn test` 全绿（基线 424）。
- [ ] AC3 `npm run build` 通过。
- [ ] AC4 **路由面完全等价**：拆分后所有 `/api/projects/**` 端点（verb+path）与拆分前一一对应（用映射清单比对，无新增/丢失/改路径）。
- [ ] AC5 **鉴权矩阵等价**：逐端点 `@PreAuthorize` 角色与拆分前一致。
- [ ] AC6 控制器瘦身：`ArticleProjectController` 及各新控制器均显著小于 555 行（目标各自 ≤ ~180 行），无控制器再注入无关依赖。
- [ ] AC7 前端瘦身：`StepPreview.vue`/`ImageLibrary.vue` 行数显著下降（各 ≤ ~450 行），抽出文件各自 ≤ ~400 行。
- [ ] AC8 **行为零变化**：既有前端交互（预览渲染/保存/配图/建议/发布、图库筛选/搜索/上传/批量/标签）与后端既有测试断言不变；前端构建产物无新告警。
- [ ] AC9 文档/spec 引用同步（无死链/无指向已删方法）。
- [ ] AC10 无新增重型依赖。

## Out of Scope

- 任何行为/接口/视觉变更（含前述 `preview` 的 400/409 不一致——**不修**）。
- 后端逻辑向 service 大规模下沉（仅允许 `publishOptions` 这类小抽取；CRUD 逻辑保持原位搬迁）。
- 其他文件（`StepBrief/StepVersions/StepPublish/ImageController/CarModelController` 等）的拆分。
- 状态管理重构（不新引 Pinia store，除非确有必要且不改变行为）。
- 引入 TypeScript / 换 UI 框架 / 改构建链。
- P1 父任务的 Cross-child 收口（单独执行）。

## Key Decisions

- **后端按子域拆 5-6 个控制器**（Project CRUD / Brief+Imitation / Versions / Images+Illustration / Preview / Publish），对齐 `DeepController` 先例；不改服务层包结构。
- **纯搬迁优先**：端点方法体逐字搬移，保证零行为变化；仅 `publishOptions` 允许下沉。
- **前端新文件落位**：组件放 `frontend/src/components/`（沿用现有扁平位置；如按域增多再考虑子目录），composable 新建 `frontend/src/composables/`。
- **父组件保留跨切状态**，子组件 props/emits；composable 持有可自洽逻辑（分页/建议/语义/批量/过滤）。
- **不追求一次性最小行数**，以可读与零行为风险为先；每个文件给出行数目标区间。

## Open Questions

（无——范围已定「完整拆分」，三项各自独立可验证。）
