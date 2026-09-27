# 预览页配图增强：剪贴板传图 + 前端暂存 + 发布时转存七牛

## Goal

在文章预览页支持用户从剪贴板粘贴图片，**不在粘贴瞬间上传七牛**；图片先在前端会话内暂存，等用户执行「去发布」动作时才统一上传七牛、把正文中的图片引用替换为图床公网 URL，再进入既有发布链路。

用户价值：粘图即时可见、不占用图床/不产生无效对象；只有真正要发布时才付出上传成本，减少「随手粘图但最终不用」造成的图床垃圾与等待。

## Background（当前实现事实，已核对 file:line）

- 预览渲染是**浏览器内** `@wenyan-md/core`：`frontend/src/utils/wenyanRender.js:74` `renderMarkdownHtml`，由 `frontend/src/composables/usePreviewRender.js` 400ms 防抖驱动。后端 `POST /api/projects/{id}/preview`（`frontend/src/api/index.js:210`）**前端从未调用**。
- 发布才走后端：`PublishService.publish`（`src/main/java/com/sparkora/service/PublishService.java:53`）→ `PreviewService.preview`（`src/main/java/com/sparkora/service/PreviewService.java:66`，本机 wenyan CLI 渲染）→ 组 `gzhContent`（`PublishService.java:86-94`）→ wenyan-server。
- 现状粘贴行为：`frontend/src/components/MarkdownEditor.vue:100-137` `makePasteHandler` —— 粘贴图片**立即** `imageApi.upload(projectId, img)` 上传七牛，成功后替换为 `![](公网URL)`。**此即本任务要改造的「粘图即上传」。**
- 正文落库：`imageApi.saveContent`（`frontend/src/api/index.js:212`）把 `contentMd` 原样存 `sparkora_article_version.content_md`；后端发布链路读库渲染。**正文 markdown 是唯一渲染真值**。
- 预览右侧是 `v-html` 渲染（`frontend/src/components/preview/PreviewPane.vue:23`），非 iframe → 页面内 `blob:` URL 可直接显示，为本地预览提供可行通道。
- 预览页工具栏「去发布」`StepPreview.goPublish`（`frontend/src/views/project/StepPreview.vue:333-342`）：正文 dirty 时先 `saveContent` 再跳发布页；发布页 `StepPublish.doPublish`（`StepPublish.vue:310`）调 `POST /publish`。
- 既有会话缓存先例：`frontend/src/utils/imageRefCache.js` —— 模块级 Map、**明确不做持久化**（Blob 体积大不适合 localStorage/IndexedDB，刷新即失效已被用户接受）。本任务的暂存遵循同一约定。
- 后端上传接口 `POST /api/images/upload`（`src/main/java/com/sparkora/web/controller/ImageController.java:108`）返回图片实体，含 `url`（入库即已转存图床）。`imageApi.upload` 已存在，**无需新增后端接口**。
- 前端既有「无扩展名剪贴板 File 需按 MIME 补名」的教训与实现（`frontend/src/components/AiImageDrawer.vue:237-257`，spec 亦有约定）。

## Requirements

### R1 粘贴暂存（不上传七牛）
- R1.1 在预览页 Markdown 编辑器粘贴剪贴板图片时，**不再立即调用上传接口**；改为在模块级会话暂存区登记该图片，并在光标处插入**占位 token** markdown。
- R1.2 占位 token 形如 `![](sparkora-img:<id>)`（`<id>` 会话内唯一）；token 是后续替换与后端防呆的识别标记。
- R1.3 粘贴时做前端前置校验（类型 png/jpg/webp、大小 ≤ `IMAGE_MAX_UPLOAD_MB` 默认 10MB），**无有效扩展名的 File 按 MIME 补扩展名**（沿用既有教训）；不合法则给出中文提示并跳过。
- R1.4 一次粘贴多张时逐张登记并各自插入占位。

### R2 本地即时预览
- R2.1 暂存时用 `URL.createObjectURL` 生成本地预览 URL；预览区渲染后，把 HTML 中 `sparkora-img:<id>` 的 `src` 替换为该 blob URL，使粘贴图在右侧预览中**即时可见**。
- R2.2 blob URL 所有权归暂存区（同一 File 全局只建一个 URL，去重与 revoke 遵循 `imageRefCache.js` 既有约定）。

### R3 发布时转存（唯一上传触发点＝预览页「去发布」）
- R3.1 点击预览页「去发布 →」时：先按既有逻辑处理正文 dirty（保存带 token 的正文），随后执行 flush —— 对每个**仍被当前正文引用**的暂存图：
  1. `imageApi.upload(projectId, file)` 上传七牛；
  2. 成功后将正文中的 token 精确替换为该图 `url`（得到 `![](公网URL)`）；
  3. 从暂存区移除该条目并 revoke 其 blob URL。
- R3.2 flush 结束后**保存正文**（使库里存的是最终公网 URL 正文），再 `flushSavePreviewStyle()` 并跳转发布页。
- R3.3 若某张上传失败：中止跳转，保留该条目于暂存区，给出中文错误提示；用户可重试或删除占位后重试。
- R3.4 不再被正文引用的暂存条目（用户删除了占位）：flush 时直接移除并 revoke，**不发起上传**。
- R3.5 正文已无暂存引用的条目不在 flush 范围内（避免无用上传）。

### R4 复制排版的保护
- R4.1 正文存在未上传暂存图时，点「复制排版」**拦截并提示**（如「正文含未上传的粘贴图，请先点『去发布』上传后再复制」），不组装带失效引用的复制内容。

### R5 跨页面/跨项目隔离与防呆
- R5.1 暂存区按**项目**隔离：进入预览页时以当前 `projectId` 开启会话，项目变更时清空并 revoke 旧项目条目。
- R5.2 发布页在存在未上传暂存条目时**阻止发布**并提示回预览页上传（前端防呆）。
- R5.3 后端**发布防呆**：`PublishService` 组装前若当前版本正文含 `sparkora-img:` 占位，以中文原因中止发布（防御任何绕过前端的路径，如刷新后直接发布）。

### R6 范围
- R6.1 **仅正文插图**；封面不在本次范围（封面仍需从图库/AI 生图选，已有 `imageId`）。
- R6.2 **不做持久化**：刷新/关闭页面/换设备后未上传的暂存图丢失，正文中残留 token 由 R5.3 / 发布页防呆兜底。

## Acceptance Criteria

- [ ] AC1（R1）预览页粘贴图片后，**不产生** `/api/images/upload` 网络请求；正文出现 `sparkora-img:<id>` 占位，光标位置正确。
- [ ] AC2（R1.3）粘贴非图片/超 10MB 文件给出中文提示且不插入；无扩展名的剪贴板图片能通过校验（补名后）。
- [ ] AC3（R2）粘贴后右侧预览区即时显示该图片（blob URL 生效），主题/样式渲染不受影响。
- [ ] AC4（R3）点「去发布」后：每张被引用暂存图各发起一次上传；正文占位被替换为对应图床 `url`；正文已落库为最终 URL 版本；成功跳转发布页。
- [ ] AC5（R3.3）上传失败时留在预览页、有中文错误、正文与暂存区保持可重试状态。
- [ ] AC6（R3.4）删除正文中占位后点「去发布」，该图**不上传**且暂存条目被清理。
- [ ] AC7（R4）含未上传暂存图时点「复制排版」被拦截并提示，不写入剪贴板。
- [ ] AC8（R5.2/R5.3）绕过预览页直接发布含 `sparkora-img:` 的正文被阻止（前端提示 / 后端 400 中文原因）。
- [ ] AC9（R5.1）切换到另一项目后，旧项目的暂存图不再出现在新项目预览/正文解析中。
- [ ] AC10 回归：不粘贴图片的既有流程（编辑、保存、主题、发布）行为不变；`npm run build` 与 `mvn -q -DskipTests compile` 通过。

## Key Decisions

- D1 暂存不做持久化，内存 Map，刷新即丢（对齐 `imageRefCache.js` 既有约定，用户已确认）。
- D2 上传唯一触发点＝预览页「去发布」（复用既有 dirty 自动保存桥接；用户已确认）。
- D3 范围仅正文插图，不含「粘贴图设为封面」（用户已确认）。
- D4 「复制排版」遇未上传暂存图拦截并提示，不自动上传（用户已确认）。
- D5 正文用自定义 token `sparkora-img:<id>` 表示待上传图；发布时替换为公网 URL。选 token 而非复用 blob URL，因正文会落库、blob URL 刷新即失效且不可移植。
- D6 后端增加发布占位防呆（防御绕过前端路径），代价极小、杜绝带失效图发布。

## Out of Scope

- 剪贴板暂存图的持久化（IndexedDB / localStorage）。
- 粘贴图设为封面。
- 拖拽上传、截图工具专属入口等其它传图方式。
- 新增后端上传接口或修改上传契约。
- 图库/关联表的既有债务修复（`sparkora_article_version_image` 不参与渲染）。

## Open Questions（阻塞项）

无（全部产品决策已确认）。
