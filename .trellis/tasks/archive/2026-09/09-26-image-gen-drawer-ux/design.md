# Design：AI 生图抽屉共用组件 + 粘贴/本地参考图 + 界面重构

> 父任务：`.trellis/tasks/09-26-image-gen-ux-paste`；依赖：`09-26-img2img-ref-upload`

## 1. 架构与边界

```
frontend/src/utils/imageRefCache.js          // 新增：模块级单例 Map，参考图会话缓存
frontend/src/components/AiImageDrawer.vue    // 新增：共用的 AI 生图抽屉（UI + 生成逻辑）
frontend/src/components/AiImageRefPicker.vue // 可选：参考图来源选择（若内联过大再拆）
frontend/src/views/ImageLibrary.vue          // 改：删除内联抽屉，引用 AiImageDrawer
frontend/src/views/project/StepPreview.vue   // 改：AI 生图 tab 内容替换为 AiImageDrawer
frontend/src/api/index.js                    // 改：新增 imageApi.generateFromImageUpload
```

- `AiImageDrawer` 自持全部生图状态（prompt/尺寸/张数/参考图/候选/loading），通过 props 接收上下文，通过 emit 把结果动作交还宿主。
- 宿主保留各自的图库分页数据源（不在组件内复用主列表），遵循 `.trellis/spec/frontend/index.md`「弹窗选数据源不要复用主列表」。

## 2. 组件契约（AiImageDrawer）

Props：
| prop | 类型 | 说明 |
|---|---|---|
| `modelValue` | Boolean | 抽屉显隐（`v-model`） |
| `projectId` | [String, Number, null] | 空 = 全局图库 |
| `mode` | `'library' \| 'preview'` | 宿主差异：library=生成后定位列表；preview=插入正文/设封面 |
| `presetTags` | `string[]` | 图库页上传标签预选（可空） |
| `showCoverAction` | Boolean | 是否显示「设为封面」（预览页 true） |

Emits：
| event | 载荷 | 说明 |
|---|---|---|
| `generated` | `images[]` | 生成完成（宿主刷新列表/快照） |
| `insert` | `image` | 插入正文（preview mode） |
| `set-cover` | `imageId` | 设为封面（preview mode） |
| `locate` | `image` | 定位到列表（library mode） |
| `update:modelValue` | Boolean | 抽屉开关 |

参考图选择弹窗：保留「独立数据源 + 页内搜索 + 防抖 + 分页」（现状 `ImageLibrary.vue:749-780` / `StepPreview.vue`），可内联在 `AiImageDrawer` 或拆 `AiImageRefPicker`；数据源用 `imageApi.list`。

## 3. 粘贴参考图数据流

```
drawer 内容区 @paste（或监听 document paste，仅抽屉打开时生效）
  → clipboardData.files / items 过滤 image/*
  → 生成 ObjectURL 本地预览 → refSource='local', refFile=File
  → 提交：refSource==='library' ? imageApi.generateFromImage(refImageId) : imageApi.generateFromImageUpload(file)
  → 成功后：把 results[].id 写入 imageRefCache（{file, fileName, prompt, size, tags}）
```

注意：`paste` 事件需在可聚焦容器上；抽屉内放隐藏 `tabindex` 容器或监听 `window`（open 时挂、close 时卸）。参考 `MarkdownEditor.vue:100` 的 `clipboardData.files` 取法。

## 4. 重生成分支（核心）

```
候选/结果卡片「重生成」onClick(image):
  cached = imageRefCache.get(image.id)
  if (cached)  → imageApi.generateFromImageUpload(cached.projectId, cached.file, cached.prompt, cached.size, 1, cached.tags)
  else if (image.source === 'ai-img2img' && image.refImageId) → imageApi.regenerate(image.id)   // 图库来源，后端复用 refImageId
  else if (image.source === 'ai-text2img') → imageApi.regenerate(image.id)                       // 文生图，后端可用 prompt 复现
  else → 置灰 + tooltip「参考图未入库且会话缓存已失效，无法重生成」
```

`imageRefCache`（`utils/imageRefCache.js`）：
- 单例 `Map`；`put(imageId, info)`（info 含 Blob/File、fileName、prompt、size、tags、projectId）、`get(imageId)`、`delete(imageId)`。
- 上限（如 20 条）LRU 淘汰；淘汰时如持有 ObjectURL 则 `URL.revokeObjectURL`。
- **不持久化**（不用 localStorage/IndexedDB，Blob 大且用户已接受刷新丢失）。
- 两个入口共享同一模块（同一 SPA 会话内互通）。

## 5. 界面重构要点

- tab：外层（图库 / AI 生图 / 智能建议—仅预览页）+ 内层（文生图 / 图生图）保持；prompt 独立 ref（既有约定）。
- 图生图参考图区：一个「来源切换 + 预览」区块——`粘贴 / 本地文件 / 图库`；有参考图时显示缩略预览 +「更换/移除」。
- 参数区：尺寸 + 张数 + 生成按钮一行/两行自适应（窄屏堆叠）。
- 候选网格：`el-image`（缩略 `thumbUrl||url`、预览原图 `url`）+ 动作按钮；生成中 `el-skeleton`。
- 空态：未选参考图时生成按钮 disabled + 提示；生成失败中文错误。
- 样式用 `assets/main.css` CSS 变量；移动端 `@media (max-width: 768px)` 单列、触控 ≥44px。

## 6. 兼容性 / 回归

- 图库页原候选「定位到列表」`locateInList`、预览页「插入正文/设封面」行为需保留（迁到 emit）。
- 图库页的 `afterGenerated → refreshView()`、预览页 `refreshImgSnapshot()` 由宿主在 `generated` 事件处理。
- 生成接口超时均为 `300000`，新接口同口径。
- 预览页 `n=1` 自动插入正文的既有行为保留（宿主在 `generated` 里判断）。

## 7. 权衡

| 方案 | 取舍 |
|---|---|
| **抽共用组件（选定）** | 消除重复、两入口强制一致；代价是一次性重构两个页面 |
| 只加粘贴能力，不抽组件 | 改动小，但重复逻辑继续分叉（用户要求统一，否决） |
| 参考图存 IndexedDB | 跨刷新可重生成，但复杂度高、用户已接受刷新丢失（否决） |

## 8. 风险与回滚

- 风险：抽屉 `paste` 事件绑定位置（焦点/冒泡）易错；ObjectURL 泄漏。
- 缓解：open 时挂 `window.paste`、close/卸载时卸；所有 ObjectURL 集中管理并在替换/删除/卸载时 revoke。
- 回滚：两页面各自恢复内联抽屉（保留 git 历史），或 feature 开关不引入。
