# 预览到发布衔接修复与预览优化

## Goal

修复「排版预览 → 公众号发布」衔接中的 4 个问题：正文自动保存、发布页同步预览主题、预览只展示正文而发布时才补 frontmatter、预览显示效果优化。目标是用户从预览点「去发布」后，发布页参数与内容与预览完全一致，且预览区不再出现 frontmatter 渲染残留。

## Background / 已确认事实（代码证据）

### 现状数据流

```
StepPreview.vue（纯前端渲染，不调后端 preview 接口）
  GET /projects/{id}/images    → currentVersionId / coverImage / bodyImageIds
  GET /projects/{id}/versions  → version.title / contentMd
  GET /images/preview-options  → themes/highlights/默认 theme/highlight/macStyle/footnote
  localStorage 草稿恢复（key=sparkora-preview-draft-{projectId}）
  buildFullMd() = `---\ntitle:..\n[cover:..]\n---\n\n` + contentMd
  renderMarkdownHtml(buildFullMd())  ← 浏览器内 @wenyan-md/core
  [编辑] contentMd（仅 localStorage 暂存；仅点「保存正文」才 PUT 后端）
  goPublish() → router.push(publish)   ← 不保存、不带主题参数
StepPublish.vue
  GET /publish-options → theme = publishTheme || defaultTheme（预览选择不参与）
  GET /images + /versions → 摘要
  POST /publish?theme=&highlight=&macStyle=&footnote=
PublishService.publish
  PreviewService.preview → buildMarkdown(title,coverUrl) → 本机 wenyan CLI render
  → gzhContent JSON {title≤64, content, cover?} → server /upload → /publish
```

### 缺陷清单（含 file:line）

- **D1 去发布不保存正文**：`StepPreview.vue:580-583` `goPublish()` 仅 `router.push`，未调用 `saveContent()`（`:542-557`）。未保存编辑只存于 localStorage（`:651-654`），而 `StepPublish`/`PublishService` 读 DB `contentMd`，导致发布内容落后于预览。
- **D2 发布页不同步预览主题**：`StepPreview` 的 theme/highlight/macStyle/footnote 是本地 ref（`:292-297`），从不持久化；`StepPublish.vue:242-245` 初始化自 `publishTheme || defaultTheme`，`publishTheme` 只在发布成功后由 `PublishService.java:107` 写入，故首次发布/未发布前预览选择丢失。
- **D3 预览渲染出 frontmatter 残留（已实测）**：`StepPreview.vue:386-393` `buildFullMd()` 把 frontmatter 拼进 markdown，再交给 `renderMarkdownHtml`（`wenyanRender.js:72-75` 直接调 core 的 `renderMarkdown`）。实测 `@wenyan-md/core` 的 `renderMarkdown` **不解析/剥离** frontmatter，输出 `<hr>` + `<h2>title: ... cover: ...</h2>` 等残留；`copyRich`（`StepPreview.vue:560-578` → `buildWechatHtml`）同源，同样带残留。官方流程是 `handleFrontMatter(md)` 先剥离并提取 `{title,cover,author,source_url,content}`，再渲染 `content`（core.js:201-256、wrapper.js:955-971）。后端 CLI 路径无此问题（`wenyan render --file` 实测正常剥离）。
- **D4 发布 payload 无 frontmatter 衍生字段**：`PublishService.java:81-87` 手工组 `gzhContent {title,content,cover}`。wenyan 官方 frontmatter 支持 `author`→微信 `author`、`source_url`→微信 `content_source_url`（wrapper.js:480-531），当前完全未传。
- **D5 预览显示效果**：现状手机拟真 + 双栏（`StepPreview.vue:100-128`、样式 `:776-802`），缺宽度档位与间距打磨。

### 数据模型现状

- `ArticleProjectEntity` 无 `title/cover/author/source_url/theme` 字段；标题在 `ArticleVersionEntity.title`，封面在 `ArticleVersionEntity.coverImageId`，主题仅 `publishTheme`（发布后写）。
- `UserEntity.displayName` 存在；`SecurityUtil.current().getUsername()` 可用。
- `source_url` 在 topic 创作模式下无数据来源；仿写模式（IMITATION）只有 `imitationText`（参考全文），亦非 URL。

## 关键决策（用户确认）

- **D4 字段范围**：完整支持 `title/cover/author/source_url` 四字段。`title` 自动取当前版本标题、`cover` 取已选封面配置；`author`/`source_url` **默认留空、由用户手动填写**。
- **author/source_url 入口与持久化**：在**发布页**新增输入框，**项目级落库**（刷新/重发不丢）。
- **R2 主题同步**：预览页主题/高亮/Mac/脚注**落库项目级，跨会话保持**。
- **R4 优化范围**：① 工具栏与整体间距打磨；② 预览宽度档位切换（手机/平板/全宽）。

## Requirements

- R1：预览页点「去发布」时自动保存当前正文（等价于先执行保存正文），保存成功后才跳转发布页；保存失败停留在预览页并提示。
- R2：发布页的主题/高亮/Mac/脚注默认值应与预览页当前选择一致，且跨刷新/跨会话保持。
- R3：预览页（左编辑区 + 右侧预览渲染 + 复制排版）只呈现正文；wenyan 所需 frontmatter 仅在发布链路由后端组装。
- R4：优化预览页显示效果：工具栏与整体间距打磨 + 预览宽度档位切换。
- R5：发布时 frontmatter 支持 `title/cover/author/source_url` 四字段；title 自动、cover 取配置、author/source_url 由发布页手填并落库。

## Acceptance Criteria

- [ ] AC1：预览页编辑正文后直接点「去发布」，不点「保存正文」也能成功跳转，且 `GET /versions` 返回的 `contentMd` 与编辑器内容一致；保存失败时不跳转并有错误提示。
- [ ] AC2：预览页选择主题 T（及高亮/Mac/脚注）后刷新页面，预览页仍显示 T；进入发布页，主题下拉默认即为 T；直接重发时发布使用 T（`publish_theme`=T）。
- [ ] AC3：预览区（右侧）与「复制排版」输出均不含 `---`/`title:`/`cover:` 等 frontmatter 文本或 `<hr>`+`<h2>` 残留；发布链路仍带完整 frontmatter（CLI 渲染 HTML 正常）。
- [ ] AC4：发布页存在「作者」「原文地址」输入框；填写后点发布，后端 `gzhContent` 含 `author`/`source_url`；刷新页面输入值仍在（落库）；留空时不发送这两个字段。
- [ ] AC5：预览区支持手机/平板/全宽三档宽度切换且生效；工具栏/整体间距经打磨（视觉可辨）。
- [ ] AC6：`mvn -q -DskipTests compile` 与 `cd frontend && npm run build` 均通过。

## Out of Scope

- 不为 `author/source_url` 做自动来源推断（AI 生成/当前用户自动填充）——按用户决策默认留空手填。
- 不新增 `source_url` 的抓取/校验（仅按原文存储与透传）。
- 不改造后端 `PreviewService.preview` 的整体渲染方案，仅补充 frontmatter 字段。
- 不引入移动端编辑/预览 tab 切换、字号缩放（用户未选）。

## Open Questions

（无阻塞项，已全部收敛。）
