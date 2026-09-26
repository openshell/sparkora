# AI 生图体验改进与剪切板图生图支持（父任务）

## Goal

提升 AI 生图（配图）体验：支持把**剪切板图片 / 本地文件**直接作为图生图（img2img）参考图（此前只能从图库选），并重构生图抽屉界面、统一「图库页」与「预览页」两个生图入口。

## Background（现状勘察结论）

- 两个 AI 生图入口，代码近乎重复：
  - 图库页 `frontend/src/views/ImageLibrary.vue:219`（`AI 生图` 抽屉）。
  - 预览页配图抽屉 `frontend/src/views/project/StepPreview.vue:207`（`AI 生图` tab）。
- 图生图参考图**只能从图库选**：`refImageId` → 后端 `ImageService.generateImage2Image:169` 从图床下载参考图字节；无法粘贴/上传本地图片。
- 编辑器已有「粘贴上传」先例 `frontend/src/components/MarkdownEditor.vue:100`（面向正文，非图生图参考图）。
- 后端 `ImageGenDTO` 无图片字段，`POST /api/images/generate-from-image`（`ImageController.java:172`）强依赖 `refImageId`。
- 图库无「上传但不可见」概念，若复用 `POST /api/images/upload` 会污染图库。

## Confirmed Decisions（用户拍板）

1. **参考图不落图库**：粘贴/本地选的参考图仅用于本次生成，不进入图库；生成结果照旧入库。→ 需新增 multipart 图生图接口。
2. **重生成由前端会话缓存支持**：粘贴/本地来源的图，其结果「重生成」用前端缓存（`Map<imageId, {file字节,fileName,prompt,size}>`）复用同一参考图 + prompt 再产新图；缓存仅当前 SPA 会话存活，**刷新后丢失 → 重生成置灰 + tooltip**。后端 `/regenerate` 契约不动；图库来源参考图重生成照旧走后端。
3. **范围**（用户多选）：粘贴/本地参考图 + 生图抽屉界面重构 + 图库页与预览页体验统一。
4. 后端改动收敛为一个新增接口，不改现有 `ImageGenDTO` / `generate-from-image` / `regenerate` 契约。

## Scope

### In Scope

- 后端：新增 multipart 图生图接口（参考图字节直传 AI，不落图库）。
- 前端：抽取共用 `AiImageDrawer` 组件；支持「粘贴 / 本地文件 / 图库」三路参考图来源；界面重构（参数区、候选网格、状态/错误提示）；统一图库页与预览页两个入口。
- 前端：参考图会话缓存模块，支撑重生成；缓存缺失时重生成置灰。
- 文档：更新 `docs/spec/image.md`（新接口字段级契约 + UI 职责）。

### Out of Scope

- 参考图持久化/跨会话（后端临时存储方案已否决）。
- 文生图链路改动（prompt/尺寸/张数能力不变）。
- 生成历史 / prompt 复用（用户未选）。
- 图库整体 UI 重设计（仅生图抽屉与两入口统一）。

## Requirement Map（父 → 子）

| 需求 | 归属子任务 |
|---|---|
| R1 参考图字节直传 AI、不落图库（新 multipart 接口） | `09-26-img2img-ref-upload` |
| R2 粘贴剪切板图片作为图生图参考图 | `09-26-image-gen-drawer-ux` |
| R3 本地文件选择作为图生图参考图 | `09-26-image-gen-drawer-ux` |
| R4 保留图库选参考图（原有路径，不回归） | `09-26-image-gen-drawer-ux` |
| R5 参考图前端会话缓存 → 重生成；缓存缺失置灰 | `09-26-image-gen-drawer-ux`（依赖 R1 接口） |
| R6 生图抽屉界面重构 | `09-26-image-gen-drawer-ux` |
| R7 图库页与预览页两入口统一（共用组件） | `09-26-image-gen-drawer-ux` |
| R8 `docs/spec/image.md` 同步 | 父任务（集成收口） |

## Cross-Child Acceptance Criteria

- [ ] AC-1 两个入口（图库页、预览页）均能：粘贴图片 → 作为图生图参考图 → 生成成功，且图库中**不出现**该参考图。
- [ ] AC-2 两个入口均能：本地选图 → 作为参考图 → 生成成功，参考图不进图库。
- [ ] AC-3 图库选参考图路径不回归（与既有行为一致）。
- [ ] AC-4 用粘贴/本地参考图生成的图，在**同一会话内**点「重生成」能复用同一参考图 + prompt 再产新图；刷新页面后该按钮置灰并给出原因 tooltip。
- [ ] AC-5 两个入口 UI 一致（同一组件渲染），参数区/候选网格/空态/错误提示齐全。
- [ ] AC-6 `mvn -q -DskipTests compile` 与 `frontend npm run build` 均通过。
- [ ] AC-7 `docs/spec/image.md` 已按新接口与 UI 更新（字段级表格三处同步）。

## Open Questions

（无 — 阻塞项已全部澄清）
