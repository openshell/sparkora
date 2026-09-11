# design.md — 预览到发布衔接修复与预览优化

## 1. 设计目标与边界

在**不改变现有渲染核**（前端 `@wenyan-md/core`、后端本机 `wenyan CLI` + 远程 `wenyan-server`）的前提下，补齐「预览 → 发布」的数据衔接，并把 frontmatter 的职责从「预览输入」收敛为「发布组装」。所有改动限定在既有分层：前端 `StepPreview.vue` / `StepPublish.vue` / `wenyanRender.js` / `api`，后端 `ArticleProjectEntity` / `ProjectRequest`（不改）/ 新增轻量 DTO / `ArticleProjectController` / `PreviewService` / `PublishService` / `schema.sql`。

## 2. 数据模型增量（项目级持久化）

用户决策：author/source_url 与预览样式均**落库项目级**。新增 6 列（全部幂等 ALTER，旧行为 NULL）：

| 表.列 | 类型 | 语义 |
|---|---|---|
| sparkora_article_project.author | VARCHAR(100) | 发布 frontmatter author（手填，可空） |
| sparkora_article_project.source_url | VARCHAR(500) | 发布 frontmatter source_url（手填，可空） |
| sparkora_article_project.preview_theme | VARCHAR(64) | 预览页当前主题（跨会话保持） |
| sparkora_article_project.preview_highlight | VARCHAR(64) | 预览页当前高亮主题 |
| sparkora_article_project.preview_mac_style | BOOLEAN | 预览页 Mac 代码块开关 |
| sparkora_article_project.preview_footnote | BOOLEAN | 预览页链接转脚注开关 |

- 命名：实体驼峰 `author/sourceUrl/previewTheme/previewHighlight/previewMacStyle/previewFootnote`，MyBatis-Plus 自动映射。
- `schema.sql` 用 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，与既有 S5/S6 迁移段同格式。
- 同步更新 `docs/s0-spec.md` §11 字段级表格。

## 3. 后端接口契约（全部 `R<T>` 包装）

### 3.1 新增：保存预览样式

```
PUT /api/projects/{id}/preview-style        ADMIN/EDITOR
body: { theme?: string, highlight?: string, macStyle?: boolean, footnote?: boolean }
→ R<Void>；项目不存在 404；参数非法 400
```

- 控制器直调 mapper 更新（沿用 `updateVersionTitle` 等轻量写法的风格），只更新非 null 字段。
- 主题白名单校验复用 `PreviewService` 的校验口径（或控制器内联，见 implement）。非法主题返回 `R.fail(400)`。

### 3.2 新增：保存发布元信息

```
PUT /api/projects/{id}/publish-meta         ADMIN/EDITOR
body: { author?: string, sourceUrl?: string }
→ R<Void>；author ≤100、sourceUrl ≤500（@Size），超长 400
```

- 允许空串（用户清空）；只更新请求中出现的字段。

### 3.3 扩展：publish-options 响应

在既有 map 增加：`author`、`sourceUrl`、`previewTheme`、`previewHighlight`、`previewMacStyle`、`previewFootnote`。发布页据此初始化表单（优先级高于全局默认），无需额外 GET。

### 3.4 扩展：发布 payload（gzhContent）

`PublishService.publish` 组装 `gzhContent` 时追加：

```java
if (p.getAuthor() != null && !p.getAuthor().isBlank()) gzh.put("author", p.getAuthor().trim());
if (p.getSourceUrl() != null && !p.getSourceUrl().isBlank()) gzh.put("source_url", p.getSourceUrl().trim());
```

- 键名对齐 wenyan `FrontMatterResult` / `publishToWechatDraft`：`author`、`source_url`（core/wrapper 实测支持，映射微信 `author` / `content_source_url`）。
- `PreviewService.buildMarkdown` 同步扩展签名 `(title, coverUrl, author, sourceUrl, contentMd, bodyImageUrls)`，在 CLI frontmatter 中也写 `author`/`source_url`（CLI 会剥离、不影响 HTML，但保持 frontmatter 契约完整、预览/发布同源）。值经 `sanitizeFrontmatterValue` 清洗换行。

> 风险：远程 wenyan-server 2.0.11 是否透传 `author`/`source_url` 未实测（现有 spec §12 只记录 `title/content/cover`）。未知 JSON 键通常被忽略，不影响发布；如实测被拒，降级为不发送这两键（保留落库与前端展示）。列入 implement 验证项。

## 4. 前端改动

### 4.1 R3：预览只渲染正文（核心修复 D3）

- `StepPreview.vue`：删除 `buildFullMd()` 的 frontmatter 拼接，预览与复制统一用**纯正文** `contentMd`：
  - `renderMarkdown()` → `renderMarkdownHtml(contentMd.value)`。
  - `copyRich()` → `buildWechatHtml(contentMd.value, {...})`。
  - 剪贴板 plain text 的标题回退改为 `props.project?.topic`（原 `project.title` 不存在）。
- `wenyanRender.js`：更新 `renderMarkdownHtml` 注释——输入为**纯正文**（不含 frontmatter）；前端预览不再依赖 core 的 frontmatter 处理。
- frontmatter 组装职责完全移到后端发布链路（§3.4），满足「预览只展示正文，发布时才补 frontmatter」。

### 4.2 R1：去发布自动保存（修复 D1）

- `saveContent()` 改为返回布尔成功标志（内部仍 catch 并提示，不抛）。
- `goPublish()` 改 async：
  1. 若 `saving` 中则忽略重复点击；
  2. `if (dirty.value) { const ok = await saveContent(); if (!ok) return }`；
  3. `router.push({ name: 'project-publish', params: { id } })`。
- 保持「保存失败停留预览页 + 错误提示」；成功则清 localStorage 草稿（saveContent 既有行为）。

### 4.3 R2：主题同步（修复 D2）

- 初始化：`theme/highlight/macStyle/footnote` 优先取 `props.project.preview*`，缺失回退 `preview-options` 全局默认。
- 变更：`onPreviewStyleChange` / footnote 开关变更时，防抖（~400ms）调用 `projectApi.savePreviewStyle(id, {theme,highlight,macStyle,footnote})`；失败仅 warn，不阻塞预览。
- 发布页 `loadOptions()`：`theme.value = d.previewTheme || d.publishTheme || d.defaultTheme`；highlight/mac/footnote 同理优先 `preview*`。
- 跨刷新：数据在项目实体，`GET /projects/{id}` 与 `publish-options` 均返回。

### 4.4 R5：发布页 author/source_url

- `StepPublish.vue` 表单新增「作者」「原文地址」输入（`el-input`，maxlength 100/500，移动端单列，触控 ≥44px）。
- 初始化自 `publish-options.author/sourceUrl`；变更防抖调用 `projectApi.savePublishMeta(id, {author, sourceUrl})`；发布前如 dirty 先保存再 publish。
- 留空不发送（后端 §3.4 已判空）。

### 4.5 R4：预览显示优化

- **宽度档位**：工具栏新增分段控件 `previewWidth ∈ {phone, tablet, full}`（默认 phone），作用于右侧预览容器宽度（phone 430px / tablet 720px / full 100%）。仅影响预览视觉，不改变渲染内容。
- **间距打磨**：`ctrl-bar` 粘性（`position: sticky; top: 0; z-index`）减少长文滚动时操作丢失；分组间距/对齐统一；状态标签与主操作按钮视觉层次（保存/复制/发布）强化；移动端断点复查。
- 复用现有 CSS 变量（`--line/--muted/--brand` 等），不引入新 UI 框架。

### 4.6 api 层

`frontend/src/api/index.js` 新增：
```js
savePreviewStyle: (id, data) => http.put(`/projects/${id}/preview-style`, data),
savePublishMeta: (id, data) => http.put(`/projects/${id}/publish-meta`, data),
```

## 5. 数据流（改后）

```
StepPreview
  project.preview* → 初始化样式；样式变更 → PUT /preview-style（落库）
  编辑 contentMd → renderMarkdownHtml(contentMd)（纯正文，无 frontmatter）
  去发布 → dirty? saveContent()（PUT /versions/{vid}/content）→ push publish
StepPublish
  publish-options → preview* 初始化样式 + author/sourceUrl
  author/sourceUrl 变更 → PUT /publish-meta（落库）
  POST /publish?theme=... 
PublishService
  PreviewService.preview → buildMarkdown(title,cover,author,source_url,body) → CLI（剥离 frontmatter，输出正文 HTML）
  gzhContent {title≤64, content, cover?, author?, source_url?} → /upload → /publish
```

## 6. 兼容性与迁移

- 旧数据 6 列为 NULL：前端回退全局默认；发布时 author/source_url 判空不发送，行为与现状一致。
- `PreviewService.buildMarkdown` 签名变更：仅 `preview()` 内部调用，无外部调用者（已确认）。
- 不改 `ProjectRequest` / `PUT /projects/{id}`：避免项目编辑表单把新字段清空。
- `publish_theme` 语义保持「发布所用主题」不变；`preview_theme` 为独立字段。

## 7. 回滚

- 后端：移除两个新端点与 entity/schema 列即可回退；schema 列为增量，回滚不影响旧功能。
- 前端：`buildFullMd` 恢复拼接 + `goPublish` 同步即可回退；无破坏性数据迁移。

## 8. 验证策略

- 后端 `mvn -q -DskipTests compile`。
- 前端 `cd frontend && npm run build`。
- 手动：AC1~AC5 场景；重点验证 AC3（预览无 frontmatter 残留）与 AC4（gzhContent 含 author/source_url，后端日志可查）。
