# 前端：AI 生图抽屉共用组件 + 粘贴/本地参考图 + 界面重构

> 父任务：`.trellis/tasks/09-26-image-gen-ux-paste`（父任务为集成收口，本任务为交付主体）
> 依赖：`.trellis/tasks/09-26-img2img-ref-upload`（新 multipart 图生图接口）。若该接口未就绪，R1 相关路径可先按接口契约编码并标注联调阻塞。

## Goal

抽取共用 `AiImageDrawer` 组件，把「图库页」与「预览页」两套近乎重复的生图抽屉统一；新增「粘贴剪切板图片 / 本地文件」作为图生图参考图；重构参数区、候选网格与状态提示；用前端会话缓存支撑这类图的重生成。

## Background（现状勘察结论）

- 两处重复实现：
  - `frontend/src/views/ImageLibrary.vue:219-274`（AI 生图抽屉）+ 逻辑 `:737-823`（`aiTab/aiPromptText/aiPromptImg/aiSize/aiCount/refImage/refDialog/generating/candidates` + `onGenerateText/onGenerateFromImage/afterGenerated`）。
  - `frontend/src/views/project/StepPreview.vue:206-269`（AI 生图 tab）+ 逻辑 `:383-396`、`:702-767`（`doGenerate/onRegenerate/chooseRef`）。
- 参考图弹窗两处也有独立实现：`ImageLibrary.vue:276-292`+`:749-780`，`StepPreview.vue:156-165`+`watch(refDialog)`。两处参考图**都只从图库选**。
- 编辑器粘贴先例：`MarkdownEditor.vue:100` `makePasteHandler`（`event.clipboardData.files` 过滤 `image/*`）——可借鉴粘贴取图方式。
- 图片 URL 约定：网格缩略图用 `thumbUrl || url`，大图预览/插入用原图 `url`（`frontend/src/views/project/StepPreview.vue:476-477`；`.trellis/spec/frontend/index.md`「图片预览必须用原图 URL」）。
- API 层集中约定：所有后端调用走 `src/api/index.js` 具名导出（`imageApi.*`），禁止 `.vue` 直调 `http`。
- Element Plus 组件自动引入；图标需显式 import。

## Requirements

- R1 新接口接入：`imageApi.generateFromImageUpload(projectId, file, prompt, size, n, tags)` → `POST /api/images/generate-from-image-upload`（multipart），`timeout: 300000`（同既有生成接口）。见后端子任务契约。
- R2 参考图来源三路（图生图 tab）：
  - 粘贴（Ctrl/⌘+V）：在抽屉内粘贴图片 → 作为参考图（本地预览，不上传图库）。
  - 本地文件：文件选择按钮（`accept=.png,.jpg,.jpeg,.webp`）。
  - 图库选图（保留原有路径，`generateFromImage` + `refImageId`）。
  - 参考图统一以本地预览展示（`URL.createObjectURL`；替换/关闭时 `revokeObjectURL`）。
- R3 参考图来源区分提交路径：图库来源 → 旧 JSON 接口；粘贴/本地来源 → 新 multipart 接口。
- R4 前端会话缓存（新模块 `frontend/src/utils/imageRefCache.js`，模块级单例 `Map`，两个入口共享）：
  - 键：生成结果图的 `id`；值：`{ file: Blob/File, fileName, prompt, size, tags, n? }`（图库来源可存 `{ refImageId }` 以便统一处理，或按来源分支）。
  - 粘贴/本地参考图生成成功后，把**每张结果图的 id** 映射到该参考图信息。
  - 生成候选/结果列表点「重生成」：
    - 缓存命中 → 用缓存参考图 + 缓存 prompt/size 走新 multipart 接口产新候选（与后端 `/regenerate` 语义对齐：同参考图 + 同 prompt 再生成）。
    - 缺缓存（如刷新后，或图库来源图应走原后端 `/regenerate`）→ 图库来源仍调 `imageApi.regenerate(id)`；粘贴/本地来源且缓存缺失 → 按钮置灰 + tooltip「参考图未入库且会话缓存已失效，无法重生成」。
  - 缓存容量：简单上限（如 LRU/上限 20 条），淘汰时 `revokeObjectURL` 释放。
- R5 界面重构（抽屉内）：
  - 顶部来源/模式清晰分段；`文生图` / `图生图` 两个 tab 状态互不污染（各自独立 prompt ref — 既有约定）。
  - 参数区：尺寸下拉（方/横/竖）、张数（1/2/4）布局整齐，标签明确。
  - 图生图参考图区：拖拽/粘贴/选文件/图库选图统一入口，参考图预览大图 + 「更换 / 移除」。
  - 候选网格：缩略图 + 操作（插入正文 / 设为封面 / 重生成）；`n=1` 沿用自动插入正文的既有行为（预览页）——图库页 n=1 行为按现状（仅入库+定位）。
  - 空态/加载/错误：生成中骨架/进度、失败中文提示、参考图缺失禁用生成。
  - 移动端单列、触控目标 ≥44px。
- R6 两入口统一：抽取 `frontend/src/components/AiImageDrawer.vue`，`ImageLibrary.vue` 与 `StepPreview.vue` 改为引用；通过 props 区分宿主差异：
  - `projectId`（可空=全局图库）。
  - `visible`（`v-model`）。
  - 结果动作差异：预览页需要「插入正文/设为封面」，图库页需要「定位到列表」；用 `emit`（`insert` / `set-cover` / `locate`）交给宿主处理，或 `mode` prop 控制。
  - `presetTags`（图库页上传标签预选）、标签清单数据源按现状注入。
  - **保留各自独立的数据源/分页/防抖**（遵循「弹窗选数据源不要复用主列表」约定）。
- R7 生成后的宿主联动：
  - 图库页：`refreshView()` 刷新列表（保留）。
  - 预览页：`refreshImgSnapshot()` 刷新快照（保留）。
  - 由宿主通过事件/回调接收生成结果，不在组件内写宿主状态。

## Acceptance Criteria

- [ ] AC-1 图库页与预览页均由同一 `AiImageDrawer.vue` 渲染生图 UI（无重复模板）。
- [ ] AC-2 图生图 tab 可粘贴图片（Ctrl/⌘+V）作为参考图，本地即时预览；提交后生成成功，且图库中不出现该参考图。
- [ ] AC-3 图生图 tab 可通过文件选择选本地图片作为参考图，行为同 AC-2。
- [ ] AC-4 图库选参考图路径保留可用，走原 `generateFromImage`（`refImageId`），无回归。
- [ ] AC-5 用粘贴/本地参考图生成的候选，同会话内「重生成」可复用同一参考图 + prompt 再产新图。
- [ ] AC-6 刷新页面（缓存丢失）后，粘贴/本地来源图的重生成按钮置灰并给出 tooltip；图库来源图重生成仍调后端 `/regenerate` 正常。
- [ ] AC-7 文生图/图生图 prompt 状态互不污染；切换 tab 不串内容。
- [ ] AC-8 生成中/成功/失败均有明确中文反馈；参考图缺失时生成按钮禁用。
- [ ] AC-9 `npm run build` 通过；无 `import http` 直调（走 `imageApi`）。
- [ ] AC-10 防抖 timer 在 `onBeforeUnmount` 清理；`URL.revokeObjectURL` 在替换/移除/卸载/淘汰时调用。

## Dependencies

- 接口 `POST /api/images/generate-from-image-upload`（`09-26-img2img-ref-upload`）。
- 无对其他子任务的依赖；本任务是父任务 AC 的主要承担者。

## Open Questions

（无）
