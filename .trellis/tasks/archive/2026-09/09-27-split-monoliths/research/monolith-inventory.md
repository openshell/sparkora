# Research: Monolith Inventory — ArticleProjectController / StepPreview.vue / ImageLibrary.vue

- **Query**: Split three monoliths (backend `ArticleProjectController.java` ~547/555 lines; frontend `StepPreview.vue` ~994; `ImageLibrary.vue` ~907)
- **Scope**: internal (structural inventory only)
- **Date**: 2026-09-27
- **Method**: Read + Grep/Glob, read-only. No code modified. All line anchors are 1-indexed and refer to current working tree.

> Scope note: this document **describes what exists**, it does not propose or rank changes.

---

## 1. Backend — `ArticleProjectController.java`

Path: `src/main/java/com/sparkora/web/controller/ArticleProjectController.java`
Actual size: **555 lines** (task description says ~547; file has grown).

### 1.1 Class-level

| Item | Value | Lines |
|---|---|---|
| Package | `com.sparkora.web.controller` | 1 |
| Annotations | `@RestController`, `@RequestMapping("/api/projects")` | 33-34 |
| Constructor | 10 injected dependencies | 48-65 |
| Javadoc | `创作项目 CRUD + 生成 brief（S1 起接真实 AI）。` | 30-32 |

### 1.2 Injected dependencies (constructor fields, lines 37-65)

| # | Field | Type (FQN-ish) | Decl line | Endpoints using it |
|---|---|---|---|---|
| 1 | `mapper` | `ArticleProjectMapper` | 37 | list, get, create, update, delete, generateVersions (guard), setSelectedTitle, savePreviewStyle, savePublishMeta, publishOptions |
| 2 | `briefService` | `BriefService` | 38 | currentBrief |
| 3 | `versionService` | `VersionService` | 39 | generateVersions, listVersions, setCurrentVersion, updateVersionContent, updateVersionTitle |
| 4 | `carService` | `ArticleProjectCarService` | 40 | get, create, update |
| 5 | `matcherService` | `com.sparkora.car.service.CarModelMatcherService` | 41 | create |
| 6 | `imageService` | `com.sparkora.service.ImageService` (FQN inline) | 42 | projectImages, setCover, modifyBodyImage |
| 7 | `previewService` | `com.sparkora.service.PreviewService` (FQN inline) | 43 | savePreviewStyle, preview, publishOptions |
| 8 | `publishService` | `com.sparkora.service.PublishService` (FQN inline) | 44 | publish |
| 9 | `imitationService` | `ImitationService` | 45 | analyzeImitation, imitationAnalysis |
| 10 | `suggestionService` | `com.sparkora.service.IllustrationSuggestionService` (FQN inline) | 46 | illustrationSuggestions, dismissIllustrationSuggestion |

Note: 4 of 10 fields are written with fully-qualified names inline rather than imports (lines 42-46) — imports at 6, 18-22 do not cover `ImageService`, `PreviewService`, `PublishService`, `IllustrationSuggestionService`.

### 1.3 Full endpoint inventory (25 handler methods)

| # | HTTP | Path (under `/api/projects`) | Method | Lines | Services used | Returns | Shape |
|---|---|---|---|---|---|---|---|
| 1 | GET | `` (root) | `list` | 67-89 | mapper | `R<PageResult<ArticleProjectEntity>>` | real logic (QueryWrapper, sort whitelist) |
| 2 | GET | `/{id}` | `get` | 91-98 | mapper, carService | `R<ArticleProjectEntity>` | real logic (null→404, attach carModelIds) |
| 3 | POST | `` (root) | `create` | 100-142 | mapper, matcherService, carService | `R<Long>` | real logic (genSource validation, entity assembly, AI car match) |
| 4 | PUT | `/{id}` | `update` | 144-166 | mapper, carService | `R<Void>` | real logic (column whitelist UpdateWrapper) |
| 5 | DELETE | `/{ids}` | `delete` | 168-176 | mapper | `R<Void>` | thin (split ids + deleteBatchIds) |
| 6 | POST | `/{id}/generate/brief` | `generateBrief` | 184-188 | none | `R<ArticleBriefEntity>` | dead stub → `R.fail(410,...)` (no service call) |
| 7 | GET | `/{id}/brief` | `currentBrief` | 193-197 | briefService | `R<ArticleBriefEntity>` | thin pass-through |
| 8 | POST | `/{id}/imitation/analyze` | `analyzeImitation` | 206-218 | imitationService | `R<Map<String,Object>>` | thin + error mapping try/catch |
| 9 | GET | `/{id}/imitation` | `imitationAnalysis` | 221-225 | imitationService | `R<Map<String,Object>>` | thin pass-through |
| 10 | POST | `/{id}/generate/versions` | `generateVersions` | 237-259 | mapper (guard), versionService | `R<Map<String,Object>>` | thin-ish (404/410 guard then delegate + error mapping) |
| 11 | GET | `/{id}/versions` | `listVersions` | 262-266 | versionService | `R<List<ArticleVersionEntity>>` | thin pass-through |
| 12 | PUT | `/{id}/current-version` | `setCurrentVersion` | 269-278 | versionService | `R<Void>` | thin + try/catch |
| 13 | PUT | `/{id}/versions/{versionId}/content` | `updateVersionContent` | 281-293 | versionService | `R<Void>` | thin + try/catch |
| 14 | PUT | `/{id}/versions/{versionId}/title` | `updateVersionTitle` | 296-308 | versionService | `R<Void>` | thin + try/catch |
| 15 | PUT | `/{id}/selected-title` | `setSelectedTitle` | 311-324 | mapper | `R<Void>` | real logic (len>200 check, single-column UpdateWrapper) |
| 16 | GET | `/{id}/images` | `projectImages` | 329-333 | imageService | `R<Map<String,Object>>` | thin pass-through |
| 17 | POST | `/{id}/images/{imageId}/cover` | `setCover` | 336-347 | imageService | `R<Void>` | thin + try/catch |
| 18 | POST | `/{id}/images/{imageId}/body` | `modifyBodyImage` | 350-362 | imageService | `R<Void>` | thin + try/catch |
| 19 | POST | `/{id}/illustration-suggestions` | `illustrationSuggestions` | 373-385 | suggestionService | `R<List<IllustrationSuggestionService.AnchorSuggestion>>` | thin + body parsing helpers |
| 20 | POST | `/{id}/illustration-suggestions/dismiss` | `dismissIllustrationSuggestion` | 391-405 | suggestionService | `R<Void>` | thin + SecurityUtil.current() |
| 21 | PUT | `/{id}/preview-style` | `savePreviewStyle` | 410-431 | mapper, previewService | `R<Void>` | real logic (non-null field UpdateWrapper, theme/highlight validation) |
| 22 | PUT | `/{id}/publish-meta` | `savePublishMeta` | 434-447 | mapper | `R<Void>` | real logic (blank→NULL UpdateWrapper) |
| 23 | POST | `/{id}/preview` | `preview` | 452-468 | previewService | `R<Map<String,Object>>` | thin + error mapping |
| 24 | GET | `/{id}/publish-options` | `publishOptions` | 473-506 | mapper, previewService | `R<Map<String,Object>>` | real logic (assembles ~15-key LinkedHashMap, calls serverVerify/serverHealth) |
| 25 | POST | `/{id}/publish` | `publish` | 509-526 | publishService | `R<Map<String,Object>>` | thin + error mapping + markFailure |

Private static helpers (not endpoints):
- `stringListOf(Object)` — 531-538 (JSON array → `List<String>`)
- `doubleOf(Object)` — 544-554 (number/string → `Double`)

### 1.4 Cohesive sub-domains (grouping by existing section comments + service usage)

The file already carries `// ====================` section banners that delineate sub-domains:

| Sub-domain | Section comment (line) | Endpoints | Line span | Services |
|---|---|---|---|---|
| Project CRUD | (class Javadoc; no banner) | #1-5 | 67-176 | mapper, carService, matcherService |
| Selected title (brief-stage pick) | (own Javadoc) | #15 | 310-324 | mapper |
| Brief | `生成 brief` (178-183) | #6-7 | 178-197 | briefService (stub #6) |
| Imitation | `文章仿写` (199) | #8-9 | 199-225 | imitationService |
| Versions | `文章版本（S1b）` (227) | #10-14 | 227-308 | mapper, versionService |
| Images | `配图（S3b…）` (326) | #16-18 | 326-362 | imageService |
| Illustration suggestions | `配图建议（09-15…）` (364) | #19-20 | 364-405 + helpers 528-554 | suggestionService |
| Preview/publish bridge | `预览到发布衔接…` (407) | #21-22 | 407-447 | mapper, previewService |
| Preview | `预览（S4…）` (449) | #23 | 449-468 | previewService |
| Publish | `发布（S5…）` (470) | #24-25 | 470-526 | mapper, previewService, publishService |

Cross-cutting: `mapper` appears in 5 sub-domains; the project existence check `mapper.selectById(id) == null → R.fail(404, "项目不存在")` is duplicated at lines 95, 148, 244, 315, 414, 438, 477.

### 1.5 Thin vs real-logic classification

**Thin pass-through (1-3 lines of delegation, often with try/catch):** #6 (410 stub), #7, #8, #9, #11, #12, #13, #14, #16, #17, #18, #19, #20, #23, #25 → **15 of 25**.

**Contains real logic in the controller:**
- #1 `list` (77-88): QueryWrapper build + sort-column whitelist.
- #2 `get` (94-97): null check + `carService.listModelIds`.
- #3 `create` (102-141): genSource validation, entity field-by-field assembly, audit fields, AI `matcherService.match` conditional.
- #4 `update` (146-165): null check + 8-column explicit `UpdateWrapper` + car replace.
- #5 `delete` (170-175): comma-id parse.
- #10 `generateVersions` (243-249): project load + genSource guard (404/410).
- #15 `setSelectedTitle` (314-323): null check + length check + UpdateWrapper.
- #21 `savePreviewStyle` (413-431): null check + theme/highlight validation via previewService + conditional set columns.
- #22 `savePublishMeta` (437-446): null check + blank→NULL semantics.
- #24 `publishOptions` (476-505): project load + ~15-key map assembly + channel probes.

### 1.6 Error-mapping convention (as implemented in this controller)

Controller-local try/catch (no global mapping of business exceptions; `ApiExceptionHandler` only covers framework layer):

| Exception | HTTP code | Example line |
|---|---|---|
| `IllegalArgumentException` | 400 | 211-212, 250-251, 288-289, 303-304, 342-343, 357-358, 380-381, 400-401, 428-429 |
| `IllegalStateException` | 409 | 213-214, 252-253 |
| `NotReadyException` | 409 | 254-255 |
| generic `Exception` | 500 (with Chinese prefix) | 215-216, 256-257, 290-291, 305-306, 344-345, 359-360, 382-383, 402-403, 465-466, 522-524 |
| `IllegalArgumentException \| IllegalStateException` combined | 400 | 518-521 (publish; also calls `publishService.markFailure`) |
| explicit `R.fail(404, "项目不存在")` | 404 | 95, 148, 244, 315, 414, 438, 477 |
| explicit `R.fail(400, ...)` validation | 400 | 106, 110, 317 |
| explicit `R.fail(410, ...)` | 410 | 187, 246 |

Note inconsistency in preview #23: `IllegalStateException` maps to **400** (line 463-464), unlike versions/imitation which map it to 409.

### 1.7 Conventions observed

- **`@PreAuthorize` on every handler** (no class-level `@PreAuthorize`). Matrix: reads `hasAnyRole('ADMIN','EDITOR','VIEWER')`; writes `hasAnyRole('ADMIN','EDITOR')`; only `delete` is `hasRole('ADMIN')` (line 169).
- **Every handler returns `R<T>`** (never `ResponseEntity`).
- All paths under `/api/projects`; Deep sub-resource is already a **separate controller** (`DeepController`, `@RequestMapping("/api/projects/{projectId}/deep")`).
- Uses FQN for `java.util.Map`/`java.util.List` inline in several signatures instead of imports.
- `SecurityUtil.require()` used in `create` (line 112); `SecurityUtil.current()` used in `dismissIllustrationSuggestion` (line 396).
- DTOs: `ProjectRequest` (@Valid), `PreviewStyleRequest`, `PublishMetaRequest` (@Valid) from `com.sparkora.domain.dto`.

---

## 2. Backend conventions / package landscape

### 2.1 All controllers under `src/main/java/com/sparkora/web/controller/`

| File | Lines | Root mapping | Notes |
|---|---|---|---|
| `ArticleProjectController.java` | 555 | `/api/projects` | the monolith |
| `ImageController.java` | 331 | `/api/images` | |
| `DeepController.java` | 241 | `/api/projects/{projectId}/deep` | **existing sub-domain split precedent** |
| `CarModelController.java` | 182 | `/api/car` | combines models + sync-jobs + rag, not split |
| `NewsController.java` | 119 | `/api/news` | |
| `KbDocController.java` | 98 | `/api/kb` | |
| `QaController.java` | 87 | `/api/qa` | |
| `StyleController.java` | 78 | `/api/styles` | |
| `AuthController.java` | 62 | `/api/auth` | |
| `ApiExceptionHandler.java` | 54 | `@RestControllerAdvice(basePackages="com.sparkora.web.controller")` | |
| `SettingController.java` | 41 | `/api/settings` | |

Total 1848 lines across 10 controllers + 1 advice.

### 2.2 Existing multi-controller / sub-domain split precedents

- **`DeepController`** is the closest precedent: deep-generation endpoints live in their own controller under the nested path `/api/projects/{projectId}/deep`, while the parent project CRUD lives in `ArticleProjectController`. It injects its own service set (`ClarifyService`, `DeepResearchService`, `DeepWriterService`, `ArticleBriefMapper`, `BriefService`, tools, `DeepProperties`, `SettingService`) — see `DeepController.java:26-53`. No shared base class.
- **`CarModelController`** shows the opposite (single controller covering models + sync-jobs + rag, 15 handlers, 182 lines) — no forced split.
- **No controller sub-packages** exist; all controllers are flat in `web/controller`.

### 2.3 Service package organization

- Flat `com.sparkora.service` (17 files incl. `NotReadyException`): `ArticleProjectCarService`, `BriefService`, `IllustrationSuggestionService`, `ImageEmbeddingService`, `ImageService` (50.8 KB — largest), `ImageTagService`, `ImitationService`, `PreviewService`, `ProjectStatusService`, `PublishService`, `QiniuService`, `SettingService`, `StyleService`, `VersionService`, `WenyanServerService`, `WenyanThemeCatalog`, `NotReadyException`.
- **Domain sub-packages** exist for newer domains, i.e. services ARE grouped by domain outside `com.sparkora.service`:
  - `com.sparkora.car.service` (`CarModelService` 24.8 KB, `CarRagService` 27.7 KB, `CarDocService`, `CarCleanService`, `CarSyncJobService`, `CarSyncScheduler`, `CarModelMatcherService`, `AiParamCleaner`, `ParamCleaner`)
  - `com.sparkora.deep.service`, `com.sparkora.kb.service`, `com.sparkora.news.service`, `com.sparkora.qa.service`, `com.sparkora.article.illustrate`, `com.sparkora.image.embed`
- Other top-level packages: `ai`, `article`, `car`, `common`, `config`, `deep`, `domain`(`.dto`/`.entity`), `mapper`, `news`, `qa`, `security`, `storage`, `web`(`.controller`/`.dto`), `wenyan`.

### 2.4 Backend error-handling spec

`.trellis/spec/backend/error-handling.md` exists. `ApiExceptionHandler` (54 lines) covers only framework-layer 400s: type mismatch, missing param, unreadable body, `@Valid` validation, multipart. Business exceptions are converted controller-locally (per its class Javadoc lines 15-17: "业务异常仍由各控制器自行 catch 转换").

---

## 3. Frontend — `StepPreview.vue`

Path: `frontend/src/views/project/StepPreview.vue` — **994 lines** (template 1-266, script 268-826, style 828-994).

### 3.1 Template structure

| Block | Lines | Content |
|---|---|---|
| `el-card` wrapper + header | 2-11 | title "Step 3 · 排版预览" |
| Pre-not-ready state | 13-18 | `v-if="!previewable"` |
| Toolbar `ctrl-bar` | 22-90 | see breakdown below |
| ↳ theme `el-select` | 24-48 | grouped `builtinThemes` / `communityThemes`, color dots |
| ↳ highlight `el-select` | 49-52 | |
| ↳ Mac switch | 56-61 | |
| ↳ footnote switch | 62-67 | |
| ↳ width radio group | 70-77 | phone/tablet/full |
| ↳ image button (badge) | 79-82 | opens `imgDrawer` |
| ↳ save-state tags | 84-86 | dirty/error/savedAt |
| ↳ save/copy/publish buttons | 87-89 | `saveContent` / `copyRich` / `goPublish` |
| Load-error state | 93-98 | |
| Two-pane `duo` | 100-148 | |
| ↳ left pane (editor) | 102-118 | `MarkdownEditor` at 108-115 |
| ↳ right pane (preview) | 120-147 | render error tag 123-126, progress bar 130, iPhone mock 131-146 (`previewBody` ref at 140, `v-html="html"`) |
| Bottom `next-row` publish | 150-153 | |
| Image drawer `el-drawer` | 156-264 | 3 tabs |
| ↳ tab "图库" (library) | 160-194 | filter row + `v-infinite-scroll` grid + set-cover action |
| ↳ tab "AI 生图" | 197-201 | `<AiImageDrawer mode="preview">` |
| ↳ tab "智能建议" (suggest) | 204-262 | score/tag filters + grouped suggestion list + adopt/dismiss |

### 3.2 Script setup inventory

**Imports (269-277):** `ref, computed, onMounted, onBeforeUnmount, watch, nextTick`; `useRoute, useRouter`; `projectApi, imageApi`; `useProjectDetailStore`; `renderMarkdownHtml, applyPreviewTheme, buildWechatHtml, sanitizeWenyanHtml` from `../../utils/wenyanRender`; `MarkdownEditor`; `AiImageDrawer`; `ElMessage`; icons `DocumentCopy, Loading, WarningFilled, Check, Picture, Plus`.

**Props / route / store (286-290):** `defineProps({ project: Object })`, `useRoute`, `useRouter`, `projectId = route.params.id`, `useProjectDetailStore`.

**Refs / reactive state:**

| Group | Refs | Lines |
|---|---|---|
| Load/editor | `loaded, loadError, editorReady, editorRef, versionId, originalMd, contentMd, imgSnapshot` | 292-299 |
| Render | `html, rendering, renderError` | 300-302 |
| Save | `saving, savedAt, saveState` | 303-305 |
| Theme/style | `themeOptions, highlightOptions, theme, highlight, macStyle, footnote, previewWidth, previewBody, copying` | 306-314 |
| Image panel | `imgDrawer, imgTab, busy` | 317-319 |
| Suggestions | `sugGroups, sugLoading, sugLoaded, sugTagFilter, sugMinScore, sugAdopted, sugDismissedCount, allTags` | 324-331 |
| Library pagination | `libraryImages, libPage, libTotal, libLoading, libSource, libKeyword`; const `LIB_SIZE=24`; `SOURCE_LABELS` | 334-341 |
| Style defaults | `styleDefaults`; module-local `styleApplied` | 778-779 |
| Transient | `themeLoading` | 437 |

**Computed (378-411):** `previewable` 378, `canGoPublish` 380, `dirty` 381, `wordCount` 382, `draftKey` 384, `snapshotImages` 387, `coverImageId` 388, `insertedUrls` 392, `insertedCount` 397, `libHasMore` 342, `builtinThemes` 410, `communityThemes` 411.

**Plain functions / helpers (grouped):**

| Area | Functions | Lines |
|---|---|---|
| Library paging | `loadLibraryPage`, `reloadLibrary`, `loadMoreLibrary`, `onLibKeywordInput` | 345-376 |
| Helpers | `imgUrl`, `originUrl` | 399-400 |
| Theme meta | `themeMeta`, `themeColor`, `themeIsBright`, `themeLabel` | 404-408 |
| Render orchestration | `scheduleRender`, `renderMarkdown`; module-local `renderTimer, renderSeq` | 414-434 |
| Style change/persist | `onPreviewStyleChange`, `onWechatRebuild`, `scheduleSavePreviewStyle`, `flushSavePreviewStyle`, `savePreviewStyle`; module-local `previewStyleTimer, previewStyleDirty` | 438-488 |
| Edit | `onEdit` | 491 |
| Scroll sync | `onEditorScroll`, `onPreviewScroll`; module-local `syncingScroll` | 496-511 |
| Load/save | `refreshImgSnapshot`, `loadContent`, `saveContent`, `copyRich`, `goPublish` | 515-623 |
| Image ops | `insertBodyImage`, `onSetCover`, `onGenerated` | 626-651 |
| Suggestions | `generateSuggestions`, `loadSugTags`, `adoptSuggestion`, `onAdoptOne`, `onAdoptGroup`, `onDismissGroup` | 656-768 |
| Style defaults | `applyEffectiveStyle` | 780-789 |

**Watchers (5):**
- `watch(saveState, ...)` 770 (no-op-ish)
- `watch(dirty, ...)` 771-774 (draft to localStorage)
- `watch(() => props.project, ...)` 812-818 (apply project-level style once)
- `watch(previewable, ...)` 820
- `watch(imgDrawer, ...)` 822
- `watch(imgTab, ...)` 824

**Lifecycle:**
- `onMounted` 791-809 — fetch `imageApi.previewOptions()`, apply style, then `loadContent()` if previewable
- `onBeforeUnmount` 825 — clears `renderTimer`, `libKwTimer`, flushes style persist

### 3.3 Extractable cohesive units (description only)

**Already-extracted child components imported here:**
- `MarkdownEditor.vue` (205 lines) — exposes `insertMd`, `insertMdAtAnchor`, `scrollToPercent`; props `modelValue`, `projectId`; emits `update:modelValue`, `ready`, `scroll`.
- `AiImageDrawer.vue` (589 lines) — props `{modelValue, projectId, mode, presetTags, showCoverAction}`; emits `['update:modelValue','generated','insert','set-cover','locate']`.

**Pinia store used:** `useProjectDetailStore` (`store.patchProject` at 480).

**Utils used:** `frontend/src/utils/wenyanRender.js` (`renderMarkdownHtml`, `applyPreviewTheme`, `buildWechatHtml`, `sanitizeWenyanHtml`).

**Cohesive state/function clusters** (each currently entangled with `contentMd`/`editorRef`/`projectId`):

1. **Render orchestration** (413-448): `scheduleRender`/`renderMarkdown`/`onPreviewStyleChange`/`onWechatRebuild` + `html/rendering/renderError/themeLoading` — depends on `contentMd` (input) and `renderMarkdownHtml`/`sanitizeWenyanHtml`.
2. **Style persistence** (453-488 + 776-789): `scheduleSavePreviewStyle`/`flushSavePreviewStyle`/`savePreviewStyle`/`applyEffectiveStyle` + `styleDefaults`/`styleApplied` — depends on `theme/highlight/macStyle/footnote`, `projectId`, `projectApi`, store.
3. **Scroll sync** (495-511): `onEditorScroll`/`onPreviewScroll` + `syncingScroll` — depends on `previewBody` and `editorRef`.
4. **Library pagination** (333-376): independent; depends only on `imageApi` — the cleanest candidate composable.
5. **Illustration suggestions** (321-331, 653-768): `sug*` state + 6 functions; depends on `projectId`, `imageApi`, `editorRef` (`insertMdAtAnchor`), `imgSnapshot` (`bodyImageIds`), `sugAdopted`.
6. **Toolbar** (22-90): self-contained given `theme/highlight/macStyle/footnote/previewWidth/themeOptions/highlightOptions/saveState/dirty/saving/copying/renderError/insertedCount/snapshotImages.length/savedAt` + action emits.
7. **Phone preview pane** (120-147): given `html/rendering/renderError/previewWidth`, `previewBody` ref, scroll emit.
8. **Image drawer** (156-264): spans library + AI + suggest tabs.

**Cross-cutting state that would need to stay in the parent or move to a composable/store if child extraction happens:** `contentMd`/`originalMd` (dirty tracking), `editorRef` (insertion target for both library-insert and suggestion-adopt), `imgSnapshot`, `projectId`, `project`, `saveState` (shared by toolbar and dirty watch), `theme`/`highlight`/`macStyle`/`footnote` (shared by toolbar, render, and persist).

---

## 4. Frontend — `ImageLibrary.vue`

Path: `frontend/src/views/ImageLibrary.vue` — **908 lines** (template 1-266, script 268-774, style 776-907).

### 4.1 Template structure

| Block | Lines | Content |
|---|---|---|
| Wrapper + `TopBar` + container | 2-4 | |
| Page header | 5-11 | title + total count |
| Toolbar `lib-toolbar` | 13-76 | left `tb-primary`: upload `el-upload` 16-19, AI-gen button 20, preset tags 22-25; right `tb-browse`: semantic toggle 29-32, semantic input/keyword 33-36, source/tag/project filters 37-52, semantic tags/minScore/search 53-69, density 70-72, refresh 73, batch-manage 74 |
| Semantic bar | 78-87 | |
| Bulk select bar | 89-96 | count / select-all-page / bulk-tag / bulk-delete / cancel |
| Filter chips row | 98-104 | |
| Load-error state | 106-112 | |
| Empty state | 114-131 | |
| Grid + pagination | 133-222 | |
| ↳ card `v-for` | 136-215 | thumb `el-image`+preview 140-142, source tag 144-146, checkbox 148-150, desktop hover panel 152-188 (meta 153-173, actions 175-187: edit tags/regen/delete), mobile bar 190-203, mobile tags 205-207, mobile source 209-212, mobile score 214 |
| ↳ pagination | 218-221 | hidden in semantic mode |
| `AiImageDrawer` (library mode) | 224-226 | |
| Tag edit dialog | 228-241 | |
| Bulk tag dialog | 243-260 | |
| Hidden upload input | 262-263 | |

### 4.2 Script setup inventory

**Imports (269-277):** `ref, computed, onMounted, onBeforeUnmount, watch`; `useRoute, useRouter`; `TopBar` from `../layouts/TopBar.vue`; `AiImageDrawer`; `imageApi, projectApi`; `* as imageRefCache` from `../utils/imageRefCache`; `useUserStore`; `ElMessage, ElMessageBox`; icons `Refresh, WarningFilled, Search, MagicStick, Menu, Grid, Check, MoreFilled, Upload, Aim`.

**State & computed:**

| Group | Items | Lines |
|---|---|---|
| Store/consts | `user`, `SOURCE_LABELS` (6 entries incl. `byd-news`) | 280-281 |
| List/paging | `images, total, page, size=24, projects, projectFilter, sourceFilter, keyword` | 282-289 |
| Tags | `allTags, tagFilter, presetTags`, `tagOptionNames` (computed 294-299), `tagGroups` (computed 301-317) | 291-317 |
| Status | `loadError, loading, uploading, deletingId, regenId, highlightId` | 318-323 |
| Semantic | `semanticMode, semanticQuery, semanticTags, semanticLoading, semanticActive, semanticMinScore` | 328-333 |
| Density | `density` (localStorage-backed) | 395 |
| Bulk select | `selectMode, selectedIds (Set), bulkDeleting` | 402-404 |
| Chips | `hasFilter` (442), `activeChips` (443-456) | 442-456 |
| Source trace | `sourceMap` | 479 |
| Route | `route, router` | 502-503 |
| Page preview | `pageOriginUrls` (542) | 542 |
| Upload | `uploadInput` | 603 |
| AI drawer | `aiDrawer` | 709 |
| Tag dialogs | `tagDialog, tagDialogImage, tagDialogTags, tagSaving` | 725-728 |
| Bulk tag dialog | `bulkTagDialog, bulkTagTags, bulkTagAction, bulkTagSaving` | 751-754 |

**Functions:**

| Area | Functions | Lines |
|---|---|---|
| Semantic search | `toggleSemanticMode, exitSemantic, hitToCard, runSemanticSearch, refreshSemantic` | 334-392 |
| Density | `toggleDensity` | 396-399 |
| Bulk select | `enterSelectMode, exitSelectMode, toggleSelect, selectAllPage, onCardClick, onBulkDelete` | 405-439 |
| Chips/filter | `clearAllFilters`, `filterByTag` | 457-476 |
| Source trace | `sourceInfo, shortDay, openNews, loadSources` | 480-499 |
| Route sync | `tagsFromRoute, applyFilterFromRoute, syncRouteTag`; `watch(() => route.query.tag, ...)` 526-539 | 505-539 |
| Display helpers | `imgUrl, originUrl, sourceLabel, shortTime, isAiImage, projectLabel, hpSubText` | 545-560 |
| Filter/load | `onKeywordInput` (300ms debounce), `onFilterChange`, `load` | 562-600 |
| Upload | `triggerUpload, onUploadInput, beforeUpload, doUpload` | 604-630 |
| Refresh/delete/regen | `refreshView, onDelete, canRegenerate, cacheHasRefs, onRegenerate, onMobileCmd` | 634-705 |
| AI drawer | `locateInList` | 712-722 |
| Tag dialogs | `openTagDialog, onSaveTags, openBulkTag, onBulkTag` | 729-770 |

**Lifecycle:** `onMounted(() => { applyFilterFromRoute(); load() })` 772; `onBeforeUnmount(() => clearTimeout(kwTimer))` 773. **Watchers:** only `watch(() => route.query.tag, ...)` 526-539.

### 4.3 Extractable cohesive units (description only)

**Existing child components imported:** `TopBar.vue` (layout, 76 lines), `AiImageDrawer.vue` (shared).

**Store used:** `useUserStore` only (`user.isEditorOrAbove`). No project-detail store.

**Utils used:** `frontend/src/utils/imageRefCache.js` (`has/get/put`).

**Cohesive clusters:**
1. **Semantic search** (325-392): fully isolated state + 5 functions; independent of grid except shared `images/total/loadError`.
2. **Bulk selection** (401-439): `selectMode/selectedIds/bulkDeleting` + 6 functions; touches `load()` and imageApi.
3. **Filter + route sync** (441-539): `keyword/sourceFilter/tagFilter/projectFilter` + chips + route bidirectional sync (`tagsFromRoute/applyFilterFromRoute/syncRouteTag` + watcher).
4. **Source trace** (478-499): `sourceMap` + `sourceInfo/shortDay/openNews/loadSources`.
5. **Upload** (602-630): `uploadInput/beforeUpload/doUpload` + preset tags.
6. **Card regeneration** (653-699): `canRegenerate/cacheHasRefs/onRegenerate`, depends on `imageRefCache` + `imageApi`.
7. **Tag dialogs** (724-770): single + bulk.
8. **Card rendering** (136-215): heaviest template block; takes `img` + many library-level refs (selectMode/selectedIds/highlightId/sourceMap/projects/user) and emits click/tag-filter/delete/regen/tag-edit/news-open.
9. **Toolbar** (13-76): owns no state itself, binds ~12 refs + many handlers.

---

## 5. Frontend conventions to capture

### 5.1 Complete file lists

`frontend/src/components/` (ONLY 2 files):
| File | Lines |
|---|---|
| `AiImageDrawer.vue` | 589 |
| `MarkdownEditor.vue` | 205 |

`frontend/src/composables/` — **directory does not exist.** There are currently **zero composables** in the project. (`ls` returns "没有那个文件或目录".)

`frontend/src/store/`:
| File | Lines |
|---|---|
| `project-detail.js` | 212 |
| `theme.js` | 18 |
| `user.js` | 40 |

Other: `frontend/src/layouts/TopBar.vue` (76). `frontend/src/api/` = `http.js` (41) + `index.js` (~230 lines, named API objects). `frontend/src/utils/` = `imageRefCache.js`, `wenyanRender.js`, `wenyanThemes.js`.

### 5.2 Naming / style conventions (observed)

- **All views use `<script setup>`** (no Options API anywhere in `views/` or `components/`).
- `defineProps` uses **object form**: `defineProps({ project: Object })` (StepPreview 286, StepPublish 146, StepBrief 252, StepVersions 174) or typed object form in `AiImageDrawer.vue:176-182` (`{ modelValue: {type,...}, ... }`).
- `defineEmits` uses **array form**: `defineEmits(['update:modelValue','generated',...])` (`AiImageDrawer.vue:183`); `MarkdownEditor.vue:19` uses `['update:modelValue','ready','scroll']`.
- `defineExpose` used in `MarkdownEditor.vue:187-193` to expose imperative methods (`insertMd`, `insertMdAtAnchor`, `scrollToPercent`).
- **Element Plus components auto-imported** via `unplugin-vue-components` (template `<el-*>` works without import); **icons explicitly imported** from `@element-plus/icons-vue` (StepPreview 277, ImageLibrary 277). Vue reactivity APIs are explicitly imported (no auto-import for these).
- **Dynamic `<component :is>` requires explicit import** (spec `frontend/index.md` §Common Mistakes, and `AiImageDrawer.vue:1-3` uses `isInline ? 'div' : ElDrawer` with `ElDrawer` imported).
- **API calls go through named exports** in `frontend/src/api/index.js` (`projectApi`, `imageApi`, etc.); `.vue` files never `import http` directly (spec `frontend/index.md` §API Layer).
- Page skeleton: `<div><TopBar/><div class="container"><div class="page-header">…` with three states (loading/error/empty) — spec `frontend/index.md` §Page Conventions.
- Scoped `<style scoped>` per SFC, using CSS variables `--brand/--ink/--muted/--line/--card/--paper/--radius/--shadow-hover`; mobile `@media (max-width: 768px)` with 44px touch targets.

### 5.3 Step*.vue sibling sizes & decomposition pattern

| File | Lines | Decomposition |
|---|---|---|
| `project/StepPreview.vue` | 994 | none (imports `MarkdownEditor`, `AiImageDrawer` only) |
| `project/StepBrief.vue` | 647 | none (inline template blocks, no child components) |
| `project/StepVersions.vue` | 485 | none |
| `project/StepPublish.vue` | 436 | none |
| `project/ProjectLayout.vue` | 197 | wizard shell + step routing |

**No Step*.vue has been decomposed into step-local child components.** The only extraction precedent is `AiImageDrawer.vue` (589 lines, created 2026-09-26 per commit `fc2f6d7 feat(ui): 图生图抽屉支持多张参考图...`) now shared by `StepPreview.vue` and `ImageLibrary.vue` — the sole "shared component extracted out of a monolith" case in the frontend.

### 5.4 Shared step patterns (relevant to any extraction)

- Steps receive `project` via `defineProps({ project: Object })` and read `route.params.id`; `ProjectLayout.vue` owns data-loading via `useProjectDetailStore` (project-detail.js exposes `ensureProject/ensureBrief/ensureVersions/ensureStyles/ensureImitation/startPolling/stopPolling/invalidate/patchProject/resetAll`).
- `StepPublish.vue` reads project-level theme via `projectApi.publishOptions` and persists meta via a debounce+seq pattern (`metaSeq` line 265) — structurally analogous to StepPreview's `previewStyleDirty` pattern.
- `StepVersions.vue` uses `reactive({})` maps for expand state (`citesOpen`, `simOpen`) — a pattern not used in StepPreview.

---

## 6. Related specs / references (read-only pointers)

- `frontend/src/api/index.js` — API contract for every endpoint used by both frontend monoliths.
- `frontend/src/utils/wenyanRender.js` — render helpers used by StepPreview.
- `.trellis/spec/frontend/index.md` — frontend conventions (API layer, page skeleton, lazy tabs, debounce cleanup, route-query sync, original-vs-thumb URL, dynamic `:is`).
- `docs/spec/preview.md`, `docs/spec/publish.md`, `docs/spec/image.md`, `docs/spec/imitation.md`, `docs/spec/version-generation.md` — module contracts for the sub-domains inside ArticleProjectController.
- `docs/spec/project-lifecycle.md` — project status machine.
- `.trellis/spec/backend/error-handling.md`, `.trellis/spec/backend/directory-structure.md` (latter is a placeholder: "To be filled by the team").
- `.trellis/big-question/` — **empty** (no known-issues entries).

---

## 7. Caveats / Not Found

- **Line-count mismatch**: task says ArticleProjectController ~547; actual is **555**; StepPreview is **994** exactly; ImageLibrary is **908** (task says ~907).
- **`frontend/src/composables/` does not exist** — no composable precedent to mirror; only Pinia stores exist.
- **`ArticleProjectController.generateBrief` (184-188)** is a **dead endpoint** returning 410 unconditionally — no `briefService` call. `briefService` is otherwise used only by `currentBrief`.
- **Error-code inconsistency** at `preview` (#23): `IllegalStateException` → 400, whereas imitation/versions map it to 409.
- No `CarSyncController` / separate car controllers exist; `CarModelController` is itself a combined monolith (models + sync jobs + rag).
- No `.trellis/big-question/` entries and `.trellis/spec/backend/directory-structure.md` is unfilled, so there is no written package-layout rule to cite; observed convention is "newer domains get `com.sparkora.<domain>.service` sub-packages; `web/controller` stays flat".
- The `// ====================` section banners inside ArticleProjectController are the only in-code sub-domain demarcation.
