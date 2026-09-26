# 前端：多参考图选择与统一提交（AiImageDrawer）

## Goal

`AiImageDrawer` 图生图参考图区从「单张（三来源二选一）」升级为「多张（≤4，可混合来源、可逐张移除）」，并统一走一个多图 multipart 提交；会话缓存扩展为整组参考图以支撑多图重生成。

## Background（勘察结论）

- `frontend/src/components/AiImageDrawer.vue` 当前单参考图模型：`refFile`（本地/粘贴，multipart）与 `refLibraryImage`（图库，JSON `refImageId`）二选一；`refSource` 决定提交分支（:371-381）。
- `frontend/src/api/index.js:176` `generateFromImageUpload(projectId, file, prompt, size, n, tags)` 单 file；:167 `generateFromImage` 单 `refImageId`。
- `frontend/src/utils/imageRefCache.js` 会话缓存当前按 `imageId → {file, fileName, prompt, size, tags, projectId}` 单 file。
- 重生成（图库页/预览页）分支：缓存命中 → `generateFromImageUpload`；`ai-img2img` 有 refImageId / `ai-text2img` → 后端 `regenerate`；否则置灰 + tooltip。
- 后端新契约（子任务 `09-26-img2img-multi-ref-backend` 定义）：`generate-from-image-upload` 接 `files[]` + `refImageIds[]`，顺序「先 files 后 refImageIds」，上限 4。

## Requirements

- R1 参考图区支持多张：粘贴（可多文件）/ 拖拽 / 本地多选 / 图库多选，均可**追加**与**混合**；总数上限 4。
- R2 每张参考图有缩略图 + 来源标识 + 逐张移除；「清空」。
- R3 达到上限后再加 → 拒绝并提示「最多支持 4 张参考图」；0 张提交 → 提示「请至少选择 1 张参考图」。
- R4 提交统一走多图 `generateFromImageUpload`（`files[]` + `refImageIds[]`），顺序与界面一致；**移除旧单图 JSON 提交分支**（图生图场景）。
- R5 会话缓存扩展为整组参考图（本地 files 列表 + 图库 id 列表 + prompt/size/tags/projectId），支撑多图重生成。
- R6 单张图库来源结果仍可走后端 `/regenerate`（若结果 `refImageId` 非空）；多图/本地来源结果靠会话缓存。
- R7 两个宿主（图库页 `ImageLibrary.vue`、预览页 `StepPreview.vue`）继续共用组件，行为一致。

## Acceptance Criteria

- [ ] AC-1 两入口均可：粘贴 2~4 张 / 本地多选 / 图库多选 / 混合来源 → 生成成功，参考图不入图库。
- [ ] AC-2 选择第 5 张被拒并提示「最多支持 4 张参考图」；0 张提交被拒并提示需选择参考图。
- [ ] AC-3 逐张移除后提交集合与界面一致，顺序为先本地 files 后图库 id。
- [ ] AC-4 会话内多图「重生成」复用整组参考图 + prompt；刷新后本地来源置灰 + tooltip。
- [ ] AC-5 单图图库来源能力不回归（`refImageId` 落库、后端 `/regenerate` 可用）。
- [ ] AC-6 `frontend npm run build` 通过。

## Dependencies

- **依赖后端子任务 `09-26-img2img-multi-ref-backend`** 的字段契约与实现（`files[]`/`refImageIds[]`/顺序/上限/`ref_image_id` 规则）。在前端实现前须确认后端契约已落地。

## Technical Notes

- `refImageCache` 结构由 `{file}` 改为 `{files: File[], refImageIds: number[], ...}`；对象 URL 生命周期按 File 引用去重/逐张 revoke。
- 删除图库单图 JSON 分支后，`imageApi.generateFromImage` 是否仍被其他调用方使用需确认（若仅此处使用，可保留导出但不再调用，避免破坏 API 面）。
- 粘贴事件可能一次含多张图片（`DataTransferItemList`），需遍历追加。
