# 设计：配图链路修复（失效可见化 + 封面不误入正文 + 计数统一）

## 1. 问题到方案的映射

| 缺陷 | 根因（已确认） | 方案要点 |
|---|---|---|
| Bug1 静默消失 | ① `flushPendingImages` 先上传、后 `saveContent`；两步之间任何重载 → 图床有对象、正文仍是 token，内存条目已 `remove` → 永久失效且无提示。② 解析失败时 `mapTokenSrc` 原样保留破图 `src`，视觉等同消失。 | 上传后**先持久化成功再清理条目**；解析失败的 token 渲染为**失效占位块** + 顶部警示 + 三处阻断；诊断自动刷新源 |
| Bug2 封面入正文 | `onGenerated` 对首次 n=1 **无条件** `insertBodyImage` | 删除自动插入；「设为封面」只改封面 |
| Bug3 计数错 | 工具栏分母=快照(含封面)、分子=快照∩正文；发布页数登记表；手动/粘贴插图不登记 | 统一「解析正文图片引用」为唯一口径；补登记关联表 |

## 2. 契约设计

### 2.1 正文图片引用解析（新增纯函数，前端）

新增 `frontend/src/utils/bodyImageRefs.js`：

```js
// 解析 markdown 图片引用 ![alt](target)；target 去重（保首次出现顺序）
export function parseBodyImageRefs(md) {
  // 返回 { urls: string[], tokenIds: string[] }
  // urls    —— 非 token 的图片目标（图床公网 URL / 已有 URL），按字符串去重
  // tokenIds—— sparkora-img:<id> 的 id，按 id 去重
}

// 正文「已就绪」插图数 = urls.length（不含封面、不含未上传 token）
export function countBodyImages(md) { return parseBodyImageRefs(md).urls.length }
```

- 正则：`/!\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g`，仅取目标串；忽略 `sparkora-img:` 前缀者归入 tokenIds。
- **唯一口径**：预览工具栏与发布页都调本函数，封面（`coverImageId`）不参与。
- 与旧 `insertedUrls` 的关系：`insertedUrls` 改为基于 `parseBodyImageRefs(md).urls` 构造 Set（供抽屉「已插入」角标），语义不变、口径收紧。

### 2.2 失效 token 的可见化（前端渲染投影）

改造 `pendingImageStore.mapTokenSrc` 为 **DOM 级**替换（现为字符串 `src=` 替换，无法生成占位元素）：

```js
// 渲染后投影：可解析 → 换 blob URL；不可解析 → 替换为失效占位块
export function projectBodyTokens(html, resolver) {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  doc.querySelectorAll('img[src^="sparkora-img:"]').forEach(img => {
    const id = img.getAttribute('src').slice('sparkora-img:'.length)
    const url = resolver?.(id)
    if (url) img.setAttribute('src', url)
    else {
      const ph = doc.createElement('span')
      ph.className = 'sparkora-img-missing'
      ph.setAttribute('role', 'img')
      ph.textContent = '粘贴图片已失效，请重新粘贴'
      img.replaceWith(ph)
    }
  })
  return doc.body.innerHTML
}
```

- 调用点：`usePreviewRender.renderMarkdown` → `sanitizeWenyanHtml(projectBodyTokens(raw, previewUrl))`。
- 占位样式：`PreviewPane.vue` 内 `:deep(.sparkora-img-missing)`（虚线框 + 居中中文提示，移动端 ≥44px 高）。
- 保留 `mapTokenSrc` 作为薄封装（或直接改名，实现时统一，勿留双份）。

### 2.3 未解析 token 的检测与阻断（前端）

- `StepPreview` 计算 `unresolvedTokens = extractTokens(contentMd).filter(id => !get(id))`；
- 顶部 `el-alert`（type=warning）：`正文含 N 张已失效的粘贴图，请重新粘贴（刷新会导致未上传的粘贴图丢失）`；
- 阻断点：
  - `usePendingImageFlush.goPublish`：`unresolved>0` → 阻止（保留现有逻辑，确保在**上传后**判定）；
  - `copyRich`：现有 `hasPendingToken` 拦截文案补充「失效」语义；
  - `StepPublish.doPublish`：现有 `hasToken` 拦截保留（后端 `PublishService` 防呆为兜底，不改）。

### 2.4 flush 顺序修正（R1.4 核心）

`usePendingImageFlush.flushPendingImages` 改为「先持久化、后清理」：

```
上传每张被引用暂存图 → md 替换 token→url → setContent(md)（累计 changed）
记录 uploadedIds（不立即 remove）
若 changed：
    saveContent()  // 持久化 URL 版正文
    失败 → return { ok:false, reason:'正文保存失败', entriesKept:true }（保留条目，可重试）
    成功 → 遍历 uploadedIds remove()（此时才 revoke/清理）
兜底清理「正文未引用」的本项目条目
return { ok:true, changed, unresolved }
```

- 保证「图床已收到对象的图，正文不停留在 token 版本」。
- `goPublish` 中现有「dirty 先存 → flush → changed 再存」的顺序保留；因条目延迟清理，重试安全。

### 2.5 补登记关联表（R3.3）

- **图库 / AI 插图**：`StepPreview.insertBodyImage(img)` 在 `insertMd` 前后调 `imageApi.addBodyImage(projectId, img.id)`（幂等）。策略：先 `insertMd`（渲染真值，必成）→ 再 best-effort `addBodyImage`，失败仅 warn（避免登记成功但未插入正文的虚高）。
- **粘贴图上传**：`flushPendingImages` 上传响应为 `R<ImageAssetEntity>`，取 `res.data.id` → 上传成功后 `imageApi.addBodyImage(pid, id)`（best-effort，失败 warn，不阻断发布）。
- 关联表 `add` 已幂等（`ImageService.modifyBodyImage`），重复调用安全。

### 2.6 移除 AI 首次自动插入（R2）

- `StepPreview.onGenerated`：删除 `if (meta?.reason==='generate' && list.length===1) insertBodyImage(...)`；仅保留 `refreshImgSnapshot()`。
- 候选卡片「插入正文」（`AiImageDrawer` emit `insert`）与「设为封面」（emit `set-cover`）保持独立；`onSetCover` 已只改封面，无需改。

## 3. 数据流（修复后）

```
粘贴 ─► pendingImageStore.add(pid, file) ─► 正文 ![](sparkora-img:id)
预览渲染 ─► projectBodyTokens(raw, previewUrl)
            ├─ 命中条目 → src=blob: 即时可见
            └─ 未命中   → 失效占位块（可见）
去发布 ─► goPublish:
           isDirty? → saveContent(当前内容)
           flushPendingImages:
             逐张 upload → token→url（内存）→ [全部完成后] saveContent 持久化
             持久化失败 → 保留条目、中止、可重试
             成功 → 清理条目 + best-effort addBodyImage
           unresolved>0 → 阻断
           flushStyle → 跳发布页
发布页 ─► 摘要「插图 N 张」= countBodyImages(版本 contentMd)；含 token 则阻断
```

## 4. 兼容性与迁移

- **零 DB 迁移**：仅复用既有 `POST /projects/{id}/images/{imageId}/body`。
- 存量正文无 token：`projectBodyTokens` 无匹配、`unresolvedTokens` 为空、计数按既有 URL 解析（与旧 `insertedUrls` 结果一致）→ 行为不变。
- 后端 `PublishService` token 防呆**保留不动**（兜底）。
- 旧 `mapTokenSrc` 若被其他处引用需一并更新（grep 确认仅 `usePreviewRender`）。

## 5. 权衡

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 失效呈现 | 渲染层占位块 + 顶部警示 | 仅破图 | 用户主诉是「静默」；必须可见可处置 |
| 计数真值 | 解析正文 markdown | 快照 images / 关联表 | 正文才是发布渲染真源；快照含封面、关联表会漂移 |
| 登记时机 | 先插入正文、best-effort 登记 | 先登记、失败回滚 | markdown 是渲染真值；避免「登记了但没插」虚高（建议路径已用严格回滚，手动路径取轻量） |
| 持久化 | 维持内存不持久化 | IndexedDB | 用户明确保持「刷新丢临时图」策略 |
| 清理时机 | saveContent 成功后再 remove | 上传后立即 remove | 修复「上传成功但正文未存」的丢失窗口 |

## 6. 风险与回滚

- **R-a 自动刷新源未定**（R1.5）：若为浏览器标签丢弃，应用无法阻止；靠 2.2–2.4 使结果可见/不丢/可阻断。实现阶段需实测并记录结论。
- **R-b 占位块样式在 v-html + scoped 下失效**：用 `:deep()` 且验证移动端。
- **R-c `projectBodyTokens` 用 DOMParser 重建 HTML**：须置于 `sanitizeWenyanHtml` 之前，且不得改变其余节点（用 DOMParser 原样序列化，风险低）。
- **回滚**：纯前端；删 `bodyImageRefs.js`、还原 `pendingImageStore.mapTokenSrc`、`usePendingImageFlush`、`StepPreview.onGenerated/insertBodyImage`、`usePreviewRender`、`PreviewToolbar`/`StepPublish` 计数即可；无数据迁移。

## 7. 已知限制（写入 spec）

- 暂存图仍不持久化：刷新/标签丢弃后未上传的粘贴图必然丢失，但**现在可见、会阻断发布**。
- 计数以正文解析为准，与关联表在「用户手工编辑删除图片后未同步登记」时可能短暂不一致（登记尽力而为）。
