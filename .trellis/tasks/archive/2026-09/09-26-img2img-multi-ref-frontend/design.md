# 设计：前端多参考图

## Boundary & Data Model

参考图集合状态（替换现有 `refFile` / `refLibraryImage` / `refSource` 单值模型）：

```js
// 每项：{ kind: 'local'|'library', file?: File, previewUrl?: string,
//         libraryImage?: object, name: string, id?: number }
const refs = ref([])              // 有序，界面顺序 == 提交顺序
const REF_MAX = 4
const localRefs   = computed(() => refs.value.filter(r => r.kind === 'local'))
const libraryRefs = computed(() => refs.value.filter(r => r.kind === 'library'))
```

- 提交顺序：后端契约为「先 files 后 refImageIds」。界面允许混合排列，但**提交时须与界面顺序一致**——由于后端只保证 `files` 在 `refImageIds` 之前，采用简化口径：**参考图区展示按来源分组（本地组在前、图库组在后）**，与提交顺序天然一致，避免顺序歧义。若用户混合添加，仍按「本地在前、图库在后」渲染。
- ObjectURL 生命周期：逐项在 `previewUrl` 持有；移除/清空/`onBeforeUnmount` 时 revoke（沿用现有 `clearRef` 思路，改为遍历）。

## API Contract（依赖后端子任务）

```js
// frontend/src/api/index.js
generateFromImageUpload: (projectId, files, refImageIds, prompt, size, n, tags) => {
  const fd = new FormData()
  ;(files || []).forEach(f => fd.append('files', f))
  ;(refImageIds || []).forEach(id => fd.append('refImageIds', Number(id)))
  fd.append('prompt', prompt)
  if (projectId != null && projectId !== '') fd.append('projectId', Number(projectId))
  if (size) fd.append('size', size)
  fd.append('n', n == null ? 1 : n)
  ;(tags || []).forEach(t => { const v = String(t||'').trim(); if (v) fd.append('tags', v) })
  return http.post('/images/generate-from-image-upload', fd, { timeout: 300000 })
}
```

- 前端预校验：`files.length + refImageIds.length` ∈ 1~4，否则本地提示（不请求）。
- 移除对 `generateFromImage`（JSON 单图）的调用（图生图统一走多图接口）；该导出保留（API 面完整），若无其他调用方则标记为兼容保留。

## Cache Contract（会话缓存扩展）

`frontend/src/utils/imageRefCache.js` 由单 file 扩展为整组：

```js
// info: { files: File[], refImageIds: number[], names: string[],
//         prompt, size, tags, projectId, previewUrls?: (string|null)[] }
```

- ObjectURL 去重/释放逻辑按 `files` 数组逐项处理（同一 File 多条目共享一个自有 URL）。
- 重生成分支（`AiImageDrawer` 与 `ImageLibrary` 两处口径一致）：
  - 缓存命中且 `files.length || refImageIds.length` → `generateFromImageUpload(projectId, files, refImageIds, prompt, size, 1, tags)`。
  - 否则 `img.source==='ai-img2img' && img.refImageId != null` / `ai-text2img` → 后端 `regenerate`。
  - 否则置灰 + tooltip。
- 语义检索命中（`img.score != null`）沿用现有「交后端判定」口径（不误置灰）。

## Interaction

- 参考图区：缩略图网格（含来源角标、移除按钮）+「本地文件」按钮 +「从图库选择」按钮 + 粘贴/拖拽提示 + 计数 `n/4`。
- 粘贴：一次可能多图（遍历 `DataTransferItemList`）；超出上限的部分丢弃并提示。
- 图库选图弹窗：由单选改多选（checkbox 覆盖层），确认后追加。
- 本地选择：`input[type=file]` 加 `multiple`。
- 超上限：新增请求被拒，`ElMessage.warning('最多支持 4 张参考图')`。

## Compatibility

- 单图图库来源：添加 1 张图库图 → `refImageIds=[id]` → 后端落 `ref_image_id=id` → 后端 `/regenerate` 路径不变（AC-5）。
- 旧 JSON `generate-from-image` 保留导出，不再被图生图 UI 调用。

## Rollback

前端纯代码回滚（revert + `npm run build`），无数据迁移。
