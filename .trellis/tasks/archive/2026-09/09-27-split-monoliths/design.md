# 拆巨石 — 技术设计

> 纯结构重构，零行为变化。分三个可独立验证的阶段：M1 后端控制器拆分 → M2 StepPreview 拆分 → M3 ImageLibrary 拆分。每阶段独立编译/构建通过后再进下一阶段。

## 0. 总原则

- **零行为变化**：所有方法体逐字搬迁；不改路由、鉴权、异常映射、返回结构、前端交互与样式效果。
- **分阶段可回退**：三阶段各自成 commit，任一阶段可单独 revert。
- 不做无关格式化（避免淹没 diff）。

---

## 1. M1 后端控制器拆分

### 1.1 目标控制器划分（对齐 `DeepController` 先例）

| 新控制器 | 承载子域 | 路径前缀 | 端点（verb path） |
|---|---|---|---|
| `ArticleProjectController`（保留名） | Project CRUD | `/api/projects` | GET ``、GET `/{id}`、POST ``、PUT `/{id}`、DELETE `/{ids}` |
| `ProjectBriefController` | Brief + Imitation | `/api/projects/{projectId}` | POST `/generate/brief`(410 stub)、GET `/brief`、POST `/imitation/analyze`、GET `/imitation` |
| `ProjectVersionController` | Versions | `/api/projects/{projectId}` | POST `/generate/versions`、GET `/versions`、PUT `/current-version`、PUT `/versions/{versionId}/content`、PUT `/versions/{versionId}/title`、PUT `/selected-title` |
| `ProjectImageController` | Images + Illustration suggestions | `/api/projects/{projectId}` | GET `/images`、POST `/images/{imageId}/cover`、POST `/images/{imageId}/body`、POST `/illustration-suggestions`、POST `/illustration-suggestions/dismiss` |
| `ProjectPreviewController` | Preview + publish bridge | `/api/projects/{projectId}` | PUT `/preview-style`、PUT `/publish-meta`、POST `/preview`、GET `/publish-options` |
| `ProjectPublishController` | Publish | `/api/projects/{projectId}` | POST `/publish` |

> 备选合并：Brief+Imitation 与 Versions 可合并为「生成」控制器；Images 与 Preview 可合并。**采用上表 6 个**（每控制器 ≤ ~180 行、依赖 ≤ ~4），但若实施中发现某控制器过薄（如 Publish 仅 1 端点），允许把 `ProjectPublishController` 并入 `ProjectPreviewController`（preview/publish 同源 wenyan）。实施者择一后须在 implement.md 勾选并保持一致。

### 1.2 搬迁规则

- **路径**：`@RequestMapping` 从 `/api/projects` 改为 `/api/projects/{projectId}`；方法级 `@GetMapping` 等去掉开头的 `/{id}`（改为无路径或用 `@PathVariable Long projectId`）。**最终 HTTP 路径必须逐字等价**（用 `/api/projects/{id}/versions` 等原路径核对）。
- **方法体**：逐字搬迁，包括 try/catch 映射（含 `preview` 的 `IllegalState→400` 不一致——保持原样）、`R.fail(404,"项目不存在")` 文案、`@PreAuthorize` 注解。
- **私有助手**：`stringListOf`/`doubleOf` 随 illustration 端点迁至 `ProjectImageController`（唯一使用者）；确认无其他控制器引用后从原文件删除。
- **依赖**：每控制器只保留其端点用到的字段（构造注入）；核对无未用字段。
- **DTO**：`ProjectRequest`/`PreviewStyleRequest`/`PublishMetaRequest` 位置不变。

### 1.3 publishOptions 下沉（可选，R1）

- 把 `publishOptions`（473-506，~30 行 map 组装 + 通道探活）下沉为 `PreviewService.publishOptions(projectId)`（或新建方法于合适 service），控制器变薄透传。
- 下沉须保持返回 map 的**键集/值语义逐字不变**；异常映射保持控制器层。
- 若不实施，保持原样（AC 不强制）。

### 1.4 风险

- 路径等价是最大风险点 → AC4 用映射清单比对（拆分前后各生成一份 `verb + full path` 列表 diff）。
- Spring 映射冲突：两个 `@RequestMapping` 前缀重叠不会冲突（不同类），但需确认无两控制器声明同一 `verb+path`。

---

## 2. M2 StepPreview.vue 拆分

### 2.1 目标结构

```
frontend/src/views/project/StepPreview.vue        (≤ ~450 行；保留编排+跨切状态)
frontend/src/components/preview/PreviewToolbar.vue      (主题/高亮/Mac/脚注/宽度/保存状态/按钮)
frontend/src/components/preview/PreviewPane.vue         (手机预览 + 渲染态 + 滚动 emit)
frontend/src/components/preview/PreviewImageDrawer.vue  (el-drawer 三 tab：图库/AI/建议)
frontend/src/composables/usePreviewLibrary.js           (文库分页)
frontend/src/composables/usePreviewSuggestions.js       (智能建议)
frontend/src/composables/usePreviewStylePersist.js      (主题持久化 debounce+flush)
```

> 组件目录：现有组件在 `frontend/src/components/` 扁平存放。本任务新增按域子目录 `preview/`（`AiImageDrawer`/`MarkdownEditor` 保持原位，避免大范围改 import）。若审核者偏好扁平，可直接放 `components/`，但三阶段需一致。

### 2.2 契约设计

**父 → 子（PreviewToolbar）props：** `theme/highlight/macStyle/footnote/previewWidth`（v-model 式或 `:x` + `@update:x`）、`themeOptions/highlightOptions`、`saveState/dirty/saving/copying/renderError/insertedCount`、`snapshotCount`、`savedAt`；**emits：** `update:theme`…、`save`、`copy`、`publish`、`openImages`。

**PreviewPane props：** `html`、`rendering`、`renderError`、`previewWidth`；**emits：** `scroll`；**expose：** `previewBodyEl`（父做滚动同步）或父通过 `@ref` 拿 DOM。

**PreviewImageDrawer props：** `modelValue`、`projectId`、`presetTags`；**emits：** `update:modelValue`、`insert`、`set-cover`、`generated`。内部委托 `usePreviewLibrary`。

**composable 边界：**
- `usePreviewLibrary(projectId, opts)` → `{ images, page, total, loading, source, keyword, hasMore, loadPage, reload, loadMore, onKeywordInput }`（搬 333-376）。
- `usePreviewSuggestions(projectId, { getSnapshot, insertAtAnchor })` → `{ groups, loading, loaded, tagFilter, minScore, allTags, adopted, dismissedCount, generate, loadTags, adopt, dismiss, onAdoptOne, onAdoptGroup, onDismissGroup }`（搬 321-331 + 653-768）。
- `usePreviewStylePersist({ projectId, theme, highlight, macStyle, footnote, onSaved })` → `{ schedule, flush, applyEffectiveStyle, styleDefaults }`（搬 453-488 + 776-789）。

### 2.3 跨切状态归属（保持父）

`contentMd/originalMd/versionId`、`editorRef`、`imgSnapshot`、`loaded/loadError`、`saveState/savedAt`、`theme/highlight/macStyle/footnote/previewWidth`、`html/rendering/renderError`。父负责 `onMounted` 拉 `previewOptions` 并初始化、`onBeforeUnmount` 清理。

### 2.4 风险

- 滚动同步跨父/子 DOM → 用 `ref` + `defineExpose`（`MarkdownEditor` 已有 `scrollToPercent` 先例）。
- `defineExpose`/`defineEmits` 遵循现约定（数组式 emits）。
- 样式：`scoped` 抽到子组件需确认子组件根元素承接原类名，避免布局回归；建议子组件各自 `scoped`，父保留布局容器类。

---

## 3. M3 ImageLibrary.vue 拆分

### 3.1 目标结构

```
frontend/src/views/ImageLibrary.vue                    (≤ ~450 行)
frontend/src/components/image/ImageLibraryToolbar.vue  (工具栏 13-76)
frontend/src/components/image/ImageCard.vue            (卡片 136-215)
frontend/src/components/image/ImageSemanticBar.vue     (语义条 78-87)
frontend/src/components/image/ImageTagDialog.vue       (单图标签 228-241)
frontend/src/components/image/ImageBulkTagDialog.vue   (批量标签 243-260)
frontend/src/composables/useSemanticSearch.js          (325-392)
frontend/src/composables/useBulkSelect.js              (401-439)
frontend/src/composables/useImageFilters.js            (过滤 + 路由同步 441-539)
```

### 3.2 契约设计

- **ImageCard props：** `img`、`selectMode`、`selected`、`density`、`highlightId`、`sourceInfo`、`projectLabel`、`canRegenerate`；**emits：** `click`、`toggle-select`、`filter-tag`、`edit-tags`、`regen`、`delete`、`open-news`。父用 `v-for` 传 props。
- **ImageLibraryToolbar props：** 全部过滤/语义/密度/选择状态 + `presetTags`；**emits：** 各更新与动作（`update:keyword`、`search`、`upload`、`ai-gen`、`refresh`、`toggle-select-mode`、`update:density`…）。
- **composable：**
  - `useSemanticSearch(imageApi)` → `{ mode, query, tags, loading, active, minScore, results, toggle, exit, run, refresh, hitToCard }`（搬 325-392）。注意与主列表 `images/total/loadError` 的耦合：语义结果复用同一展示变量——由父在两个 composable 间桥接，或让 `useSemanticSearch` 接受 `setList` 回调。
  - `useBulkSelect()` → `{ selectMode, selectedIds, bulkDeleting, enter, exit, toggle, selectAllPage, onCardClick, onBulkDelete }`（搬 401-439，`onBulkDelete` 依赖 `load` → 注入回调）。
  - `useImageFilters(route, router)` → `{ keyword, sourceFilter, tagFilter, projectFilter, activeChips, hasFilter, clearAll, filterByTag, tagsFromRoute, applyFilterFromRoute, syncRouteTag }`（搬 441-539 + watcher 526-539）。

### 3.3 风险

- 路由同步 watcher 移入 composable 后须在组件卸载时正确清理（`useImageFilters` 内注册 `watch`，随 setup 生命周期自动停止）。
- 批量选择与主 `load()` 的耦合：`onBulkDelete` 完成后调 `load`，用回调注入。
- 卡片模板最大，抽出后 props 较多——接受（纯声明式）。

---

## 4. 兼容性与验证

| 维度 | 校验方式 |
|---|---|
| 后端路由 | AC4：拆分前后 `verb+path` 清单 diff 为空 |
| 后端鉴权 | AC5：逐端点 `@PreAuthorize` 与基线比对 |
| 后端行为 | `mvn test`（424）全绿；`mvn compile` |
| 前端行为 | `npm run build`；人工核对交互路径（预览/保存/配图/建议/发布、图库筛选/语义/上传/批量/标签） |
| 文档 | AC9：grep 巨石类/文件引用，更新 spec |

## 5. 回滚

- M1/M2/M3 各自单 commit；任一阶段 revert 不影响其他（M2/M3 仅前端、M1 仅后端；前端 M2/M3 互不依赖）。
- 无 DB/配置变更，无迁移。

## 6. 验证命令

```bash
# M1
mvn -q -DskipTests compile && mvn test
# 路由清单(拆分前后各跑一次 diff)
rg -n "@(Get|Post|Put|Delete)Mapping|@RequestMapping" src/main/java/com/sparkora/web/controller/*.java
# M2/M3
cd frontend && npm run build
# 瘦身核对
wc -l src/main/java/com/sparkora/web/controller/ArticleProjectController.java frontend/src/views/project/StepPreview.vue frontend/src/views/ImageLibrary.vue
```
