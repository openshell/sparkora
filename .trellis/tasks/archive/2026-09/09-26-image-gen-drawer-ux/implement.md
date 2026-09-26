# Implement：AI 生图抽屉共用组件 + 粘贴/本地参考图 + 界面重构

> 父任务：`.trellis/tasks/09-26-image-gen-ux-paste`；依赖：`09-26-img2img-ref-upload`（接口就绪后联调）

## 验证命令

- 构建：`cd frontend && npm run build`
- 联调：`./dev.sh restart backend && ./dev.sh restart frontend`，或 `npm run dev`；浏览器验证粘贴/本地/图库三路。
- 后端接口依赖：`mvn -q -DskipTests compile`（接口任务）。

## 实施清单（有序）

1. **API**：`frontend/src/api/index.js` 新增
   ```js
   generateFromImageUpload: (projectId, file, prompt, size, n, tags) => {
     const fd = new FormData()
     fd.append('file', file)
     fd.append('prompt', prompt)
     if (projectId != null && projectId !== '') fd.append('projectId', Number(projectId))
     if (size) fd.append('size', size)
     fd.append('n', n == null ? 1 : n)
     ;(tags || []).forEach(t => { const v = String(t || '').trim(); if (v) fd.append('tags', v) })
     return http.post('/images/generate-from-image-upload', fd, { timeout: 300000 })
   }
   ```
2. **缓存模块**：新增 `frontend/src/utils/imageRefCache.js`（单例 Map + LRU 上限 + revokeObjectURL）。
3. **共用组件**：新增 `frontend/src/components/AiImageDrawer.vue`：
   - props/emits 按 `design.md` §2；内部实现文生图/图生图、参考图三来源、候选网格、粘贴监听、重生成分支。
   - 参考图选择弹窗（独立数据源 + 防抖 + 分页）。
4. **图库页改造**：`ImageLibrary.vue` 删除内联 AI 抽屉模板（:219-274）与相关逻辑，改为 `<AiImageDrawer v-model="aiDrawer" :project-id="null" mode="library" :preset-tags="presetTags" @generated="refreshView" @locate="locateInList" />`；保留 `locateInList`。
5. **预览页改造**：`StepPreview.vue` 的 AI 生图 tab（:206-269）替换为 `<AiImageDrawer>`（`mode="preview"` `:project-id="projectId"` `show-cover-action`），事件接 `insertBodyImage` / `onSetCover` / `refreshImgSnapshot`；保留 `n=1` 自动插入行为。
6. **文档（父任务收口）**：更新 `docs/spec/image.md` §8 页面职责（两入口统一到共用组件、粘贴/本地参考图、重生成缓存策略）。
7. **自检**：`npm run build`；手动回归三来源 + 重生成 + 刷新后置灰。

## 验证要点（对照 AC）

- AC-2/3：粘贴与本地选图各生成一次，查图库确认参考图未入库。
- AC-4：图库选参考图生成，确认无回归。
- AC-5/6：粘贴来源图重生成可用；刷新后置灰 + tooltip；图库来源重生成仍走 `/regenerate`。
- AC-7：文生图输入后切图生图，prompt 不串。
- AC-9：全局搜索 `.vue` 无 `import http`。
- AC-10：所有防抖 timer 有 `onBeforeUnmount` 清理；ObjectURL 均 revoke。

## 风险文件 / 回滚点

- `ImageLibrary.vue`、`StepPreview.vue`（大段删除，注意保留宿主回调与样式类）。
- 新组件 `AiImageDrawer.vue` 的粘贴事件绑定（焦点问题）。
- 回滚：恢复两页面内联抽屉（git 历史）。

## Follow-ups

- 与后端接口 `09-26-img2img-ref-upload` 联调；若接口未就绪，先按契约编码，联调阶段验证。
- 完成后回父任务做集成核对与 `docs/spec/image.md` 同步。
