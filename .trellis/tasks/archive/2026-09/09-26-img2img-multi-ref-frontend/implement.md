# 执行计划：前端多参考图

## Ordered Checklist

- [ ] 1. `frontend/src/api/index.js`：`generateFromImageUpload` 改为 `(projectId, files, refImageIds, prompt, size, n, tags)`，append `files`（多）+ `refImageIds`（多）；更新注释，字段名与后端 `@RequestParam` 完全一致。
- [ ] 2. `frontend/src/utils/imageRefCache.js`：info 结构扩展为 `{files:[], refImageIds:[], names:[], ...}`；ObjectURL 去重/释放按数组逐项；`put/get/has/deleteEntry/clear` 语义保持。
- [ ] 3. `frontend/src/components/AiImageDrawer.vue`：
  - 参考图状态改为 `refs` 有序数组（`kind` 区分 local/library），删除 `refFile`/`refLibraryImage`/`refSource` 单值模型。
  - 参考图区 UI：缩略图网格 + 来源角标 + 逐张移除 + 计数 n/4 + 清空；「本地文件」`multiple`；图库弹窗改多选。
  - 粘贴监听遍历 `DataTransferItemList` 追加多张；拖拽同理。
  - 提交统一 `generateFromImageUpload(projectId, files, refIds, prompt, size, n, tags)`；上限/非空校验；删除图库单图 JSON 分支。
  - 生成成功写会话缓存（整组）；重生成分支改为多图缓存命中 → 多图接口。
  - `onBeforeUnmount` 遍历 revoke 所有本地预览 URL。
- [ ] 4. `frontend/src/views/ImageLibrary.vue`：`onRegenerate` / `canRegenerate` 适配新缓存结构（`cached.files/refImageIds`）；调用多图接口。
- [ ] 5. `frontend/src/views/project/StepPreview.vue`：确认宿主 props/emits 无需改动（组件内部封装）——如有 `refImage` 相关直连逻辑则同步。
- [ ] 6. 构建。

## Validation Commands

```bash
cd frontend && npm run build
```

手工验证（`./dev.sh` 或重建容器后）：
- 图库页与预览页：粘贴 2 张 → 生成成功；本地多选 3 张 → 成功；图库多选 2 张 → 成功；混合 → 成功。
- 选第 5 张 → 提示上限；0 张提交 → 提示需选图。
- 逐张移除后生成 → 提交集合与界面一致。
- 会话内重生成多图结果 → 复用整组；刷新后置灰 + tooltip。

## Risky Files / Rollback Points

- `AiImageDrawer.vue`：参考图状态模型整体重写，是最大风险点；确保单图图库路径（`refImageIds=[id]`）不回归。
- `imageRefCache.js`：结构变更影响两个宿主的重生成分支（`ImageLibrary.vue:681/688`、`AiImageDrawer.vue:408/418`），三处同步。
- 组件卸载 ObjectURL 释放：漏 revoke 会内存泄漏，误 revoke 会坏其他预览。

## Follow-up Checks Before start

- [ ] 确认后端子任务 `09-26-img2img-multi-ref-backend` 已完成（字段 `files[]`/`refImageIds[]`、上限 4、顺序、`ref_image_id` 规则可依赖）。
- [ ] `implement.jsonl` / `check.jsonl` 已填真实 spec 条目（非 `_example`）。
