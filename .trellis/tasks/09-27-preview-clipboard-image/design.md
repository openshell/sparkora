# 设计：预览页剪贴板传图 + 前端暂存 + 发布时转存七牛

## 1. 架构与边界

本任务**几乎纯前端**，仅在后端加一处发布防呆。核心事实：预览是浏览器内渲染、发布才走后端，因此「暂存 + 发布时上传」天然落在前端。

```
[预览页]
  粘贴图片 ──► 暂存区(blob + File + token)           ← 唯一新增的数据结构
               └─ 正文插入 ![](sparkora-img:<id>)
               └─ 预览渲染后把 token → blob URL      ← 即时可见
  [去发布] ──► flush:
               每张被引用暂存图 → imageApi.upload → 得到 url
               正文 token → url(markdown 精确替换)
               暂存条目 revoke/移除
               保存正文(最终 URL) ──► 跳发布页
[发布页]     body 含 sparkora-img: → 前端阻止发布
[后端]       PublishService 组装前含 sparkora-img: → 中文 400 防呆
```

### 新增前端单元
- `frontend/src/utils/pendingImageStore.js`（名称待定）——模块级单例会话暂存区：
  - `add(projectId, file)` → `{ id, previewUrl }`；`get(id)` / `entries(projectId)` / `remove(id)` / `clearProject(projectId)` / `previewUrl(id)` / `hasAny(projectId)`；
  - 同一 File 全局只建一个 ObjectURL；`clearProject`/`remove` 时按引用去重后 revoke（对齐 `imageRefCache.js` 所有权约定）。
- 正文 token 工具（可与暂存区同文件或 `utils` 独立）：`TOKEN_RE = /sparkora-img:([A-Za-z0-9_-]+)/g`、`replaceToken(content, id, url)`、`replaceAllTokens(content, resolver)`、`hasToken(content)` / `extractTokens(content)`。

### 改动点
- `frontend/src/components/MarkdownEditor.vue`：`makePasteHandler`（:100-137）由「立即 upload」改为「登记暂存 + 插入 token」。
- `frontend/src/composables/usePreviewRender.js`：渲染出的 HTML 在 `sanitizeWenyanHtml` 前/后，把 `src="sparkora-img:<id>"` 映射为 `previewUrl(id)`（渲染后替换，保证即时可见）。
- `frontend/src/views/project/StepPreview.vue`：`goPublish`（:333）插入 flush 步骤；`copyRich`（:312）加拦截；`loadContent`/项目切换时 `clearProject` 旧项目暂存。
- `frontend/src/views/project/StepPublish.vue`：`doPublish`（:310）发布前检查当前版本 `contentMd` 是否含 token，含则提示并阻止。
- `src/main/java/com/sparkora/service/PublishService.java`：`publish`（:53）组装 `gzhContent` 前检查 `v.getContentMd()` 是否含 `sparkora-img:`，含则 `throw new IllegalStateException("正文含未上传的粘贴图，请回到预览页上传后再发布")`（现有控制器已把异常转 `R.fail` + 写 `last_publish_error`）。

## 2. 数据流与契约

### 2.1 暂存条目结构（内存，不落库）
```
{ id: string,            // 会话内唯一，如 'p1' / crypto.randomUUID 短 id
  projectId: string,
  file: File,            // 已按 MIME 补扩展名
  previewUrl: string,    // ObjectURL
  createdAt: number }
```

### 2.2 正文 token 契约
- markdown：`![](sparkora-img:<id>)`
- 正则：`/sparkora-img:([A-Za-z0-9_-]+)/g`
- 后端防呆只做**存在性**判断（`contentMd.contains("sparkora-img:")`），不解析 id（避免前后端 token 格式耦合面扩大）。
- token 与 URL 的替换在**前端**完成；后端永远不应收到 token（防呆仅兜底）。

### 2.3 flush 语义（`goPublish` 内）
```
flushPendingImages(projectId, contentMd):
  tokens = extractTokens(contentMd)
  for id in tokens:
     entry = store.get(id)
     if !entry: 跳过（可能是历史/失效 token）
     try: r = upload(projectId, entry.file)
          contentMd = replaceToken(contentMd, id, r.data.url)
          store.remove(id)                    // revoke previewUrl
     catch: throw 中文错误（保留 entry，供重试）
  // 未被引用的条目：不在 tokens 中 → 不处理，但可在 flush 末尾对 store.entries(projectId) 中
  // 已不在正文的条目做清理（remove + revoke），避免泄漏
  return contentMd
```
- 上传逐个串行（粘贴量小、保证错误定位）；失败即中止整次 flush，已成功的替换保留（幂等：已换成 URL 的 token 不再被 `extractTokens` 命中）。

## 3. 兼容性与迁移

- **零 DB 迁移**：不新增列/表，不改上传接口。
- **存量正文**：不含 token，`hasToken` 为 false，flush/copy/发布防呆均短路 → 行为不变。
- **发布页直接发布不触发上传**：flush 只在预览页「去发布」发生；从工作台/直达发布页且正文含 token 时被 R5.2/R5.3 阻止（不会误传也不会误发）。
- **刷新后**：暂存丢失，正文残留 token；预览显示破图（可接受，见 Open 已知限制）；发布被阻止并提示回预览重贴。
- **`markdown` 渲染器行为**：`@wenyan-md/core` 对 `![](sparkora-img:x)` 会生成 `<img src="sparkora-img:x">`（不被识别为协议，可能原样保留）——渲染后替换基于 `src` 属性匹配即可，不依赖协议解析。实现时需以真实渲染输出校验选择器（可能被 sanitizer 清掉非法协议，需在渲染管线的合适时机替换）。

## 4. 权衡

| 决策 | 选择 | 备选 | 理由 |
|---|---|---|---|
| 正文占位形态 | 自定义 token `sparkora-img:<id>` | 复用 blob URL | 正文会落库；blob URL 刷新即失效、跨设备不可移植、易被误当有效 URL |
| 渲染层即时预览 | 渲染后把 token 替换为 blob URL | 在 markdown 阶段替换 | markdown 阶段替换会让 blob URL 进入保存草稿；只在渲染投影替换，落库正文保持 token |
| 上传触发点 | 预览页「去发布」 | 发布页「确认发布」 | 用户已确认；进发布页时正文已是最终 URL，刷新安全 |
| 暂存持久化 | 内存 Map 不持久化 | IndexedDB | 对齐 `imageRefCache.js`；避免容量/清理复杂度 |
| 后端是否参与 | 仅加发布 token 防呆 | 后端代传/代替换 | 最小改动、职责清晰；上传已有接口 |

## 5. 风险与回滚

- **风险 R-a：渲染管线对非法 `src` 的清洗时机**。`@wenyan-md/core` 或 `sanitizeWenyanHtml` 可能丢弃/改写 `sparkora-img:` src，导致替换失配、预览不显示。缓解：实现阶段先打印 `renderMarkdownHtml('![](sparkora-img:x)')` 的真实输出确认形态；必要时在 `renderMarkdownHtml` 的 markdown 输入侧做**仅用于预览的**临时替换（不影响落库正文）。
- **风险 R-b：ObjectURL 泄漏**。多个 token 引用同一 File、组件卸载等路径需复用既有 `imageRefCache` 的去重/revoke 范式；实现后按 spec 约定自检。
- **风险 R-c：`goPublish` 复杂度上升**。flush 为异步串行，需处理 saving/错误态；保持「失败停留预览页」既有语义。
- **回滚**：纯前端改动 + 一处后端防呆。回滚删新文件、还原 `MarkdownEditor.makePasteHandler`、移除 `StepPreview` flush/拦截与 `PublishService` 检查即可；无数据迁移需回滚。

## 6. 已知限制（写入 spec）

- 刷新/关页/换设备后未上传的暂存图丢失；正文残留 `sparkora-img:` token，预览显示破图，需重新粘贴。
- 暂存图**不登记** `sparkora_article_version_image`（与既有「手动插图不登记」债务一致；沿用「markdown 为渲染真值」口径）。
- 仅支持粘贴入口，不含拖拽。
