# implement.md — 预览到发布衔接修复与预览优化

> 复杂任务执行清单。按层分组，组内顺序执行；每完成一组跑对应验证命令。所有后端注释/commit 用中文。

## 验证命令（每组收尾）

```bash
mvn -q -DskipTests compile            # 后端编译（根目录；仓库只读时加 -Dmaven.repo.local=/tmp/m2repo）
cd frontend && npm run build          # 前端构建
./dev.sh restart backend              # 联调时重启后端
```

---

## 阶段 A：数据模型 + 后端接口（先落库，前端才有依赖）

- [ ] A1 `src/main/resources/db/schema.sql`：新增 6 列幂等 ALTER（author/source_url/preview_theme/preview_highlight/preview_mac_style/preview_footnote），段注释标 `-- S5+：预览到发布衔接`。`mvn -q -DskipTests compile` 不验证 SQL，靠启动自检。
- [ ] A2 `ArticleProjectEntity.java`：新增 6 个字段（`author`/`sourceUrl`/`previewTheme`/`previewHighlight`/`previewMacStyle`/`previewFootnote`），中文注释。
- [ ] A3 新增 DTO `domain/dto/PreviewStyleRequest.java`（theme/highlight/macStyle/footnote，均可选）与 `domain/dto/PublishMetaRequest.java`（author `@Size(max=100)`、sourceUrl `@Size(max=500)`，均可选）。
- [ ] A4 `ArticleProjectController.java`：
  - `PUT /{id}/preview-style`（ADMIN/EDITOR）：校验主题白名单（复用 PreviewService 暴露的白名单或新增校验方法），非 null 才 set，`updated_at=now`，`mapper.updateById`。
  - `PUT /{id}/publish-meta`（ADMIN/EDITOR）：非 null 才 set author/sourceUrl，`updated_at=now`。
  - `publishOptions()` 响应 map 追加 `author/sourceUrl/previewTheme/previewHighlight/previewMacStyle/previewFootnote`。
- [ ] A5 `PreviewService.java`：`buildMarkdown` 扩展为 `(title, coverUrl, author, sourceUrl, contentMd, bodyImageUrls)`，frontmatter 追加 `author`/`source_url`（非空时，经 `sanitizeFrontmatterValue`）；`preview()` 调用处传 `v`/项目字段（author/source_url 从 project 取）。
- [ ] A6 `PublishService.java`：`gzhContent` 追加 `author`/`source_url`（非空才 put）。注意 `p` 在 1.5) 已 selectById，复用。
- [ ] 验证：`mvn -q -DskipTests compile`；`./dev.sh restart backend` 后 `curl` 探活 `PUT /preview-style`、`GET /publish-options` 新字段。

## 阶段 B：前端预览页（R1 自动保存 + R3 纯正文 + R4 优化）

- [ ] B1 `frontend/src/api/index.js`：新增 `savePreviewStyle`、`savePublishMeta`。
- [ ] B2 `wenyanRender.js`：更新 `renderMarkdownHtml` 注释为「输入纯正文（不含 frontmatter）」。函数体不变。
- [ ] B3 `StepPreview.vue` R3：
  - 删除 `buildFullMd()`（或改为返回纯 `contentMd`）；`renderMarkdown()` 用 `contentMd.value`。
  - `copyRich()` 用 `contentMd.value`，plain text 标题回退 `props.project?.topic || ''`。
- [ ] B4 `StepPreview.vue` R1：`saveContent()` 返回 `true/false`；`goPublish()` 改 async（saving 去重 → dirty 时 await saveContent → 失败 return → push）。
- [ ] B5 `StepPreview.vue` R2：样式初始化优先 `props.project.preview*`；`onPreviewStyleChange`/footnote 变更防抖 `savePreviewStyle`。
- [ ] B6 `StepPreview.vue` R4：工具栏加宽度档位分段控件；`ctrl-bar` sticky + 间距/层次打磨；移动端复查。
- [ ] 验证：`npm run build`；联调手测 AC1/AC3/AC5。

## 阶段 C：前端发布页（R2 初始化 + R5 author/source_url）

- [ ] C1 `StepPublish.vue` R2：`loadOptions()` 主题/高亮/Mac/脚注优先 `preview*`，回退 `publishTheme`/全局默认。
- [ ] C2 `StepPublish.vue` R5：新增「作者」「原文地址」输入；初始化自 options；变更防抖 `savePublishMeta`；发布前 dirty 先存。
- [ ] C3 表单移动端单列、触控 ≥44px 复查。
- [ ] 验证：`npm run build`；联调手测 AC2/AC4。

## 阶段 D：文档 + 规格同步

- [ ] D1 `docs/s0-spec.md` §11 字段级表格补 6 列；§11/§12 接口契约表补两个新端点；§12 gzhContent 字段说明补 `author?/source_url?`。
- [ ] D2 `AGENTS.md`（如需）补一句衔接说明——仅在确有跨会话价值时改。

## 阶段 E：全量验证（完成前必跑）

- [ ] E1 `mvn -q -DskipTests compile` + `cd frontend && npm run build`。
- [ ] E2 AC1~AC6 逐条手测/日志核对：
  - AC1 去发布自动保存（不点保存正文，查 `GET /versions` contentMd）。
  - AC2 预览选主题 → 刷新保持 → 发布页默认一致 → 重发 `publish_theme` 正确。
  - AC3 预览/复制无 frontmatter 残留（肉眼 + 复制 HTML 检查）。
  - AC4 发布页填写 author/source_url → 刷新仍在 → 后端日志 gzhContent 含两键（若 server 拒绝则记录并降级）。
  - AC5 宽度档位切换生效、间距打磨可见。
  - AC6 两命令通过。
- [ ] E3 若 A6 的 author/source_url 被远程 server 拒绝：降级为不发送（保留落库+前端），并在 spec §12 登记实测结论。

---

## 风险与回滚点

- **R-1（中）远程 wenyan-server 不透传 author/source_url**：未知键通常忽略；若拒绝则按 E3 降级。回滚点：A6。
- **R-2（低）主题白名单**：`preview-style` 需与 `PreviewService` 白名单一致，防止前端传入非法主题被持久化后在发布时 `R.fail(400)`。实现时复用同一校验。
- **R-3（低）`buildMarkdown` 签名变更**：仅内部调用，已确认无外部调用者。
- **R-4（低）预览样式落库频率**：防抖 400ms，失败仅 warn，不阻塞。
- **R-5（低）AC3 回归**：删除 `buildFullMd` 后需确认「复制排版」不再需要 frontmatter（复制目标是公众号编辑器，frontmatter 无意义），与 R3 一致。

## 完成后需更新的规格/文档

- `docs/s0-spec.md` §11 字段级表格 + 接口契约表；§12 gzhContent 字段。
- 任务内 `prd.md` AC 勾选；必要时 `journal`。
