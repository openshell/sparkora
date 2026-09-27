# 执行计划：预览页剪贴板传图 + 前端暂存 + 发布时转存七牛

## 前置阅读（实现前必读）

- `docs/spec/preview.md`（预览/发布同源契约）、`docs/spec/image.md`（配图链路/上传契约/已知债务）、`docs/spec/publish.md`
- `.trellis/spec/frontend/index.md`（API 层、上传扩展名补全、ObjectURL 去重、组件拆分落位）
- 既有先例：`frontend/src/utils/imageRefCache.js`、`frontend/src/components/AiImageDrawer.vue:237-268`

## 实现步骤（有序）

1. **【勘察先行 · 风险 R-a】** 在浏览器 dev 环境执行 `renderMarkdownHtml('![](sparkora-img:abc)')`（或临时 console），确认 `@wenyan-md/core` 对该 src 的输出形态；据此确定预览替换应在「markdown 输入侧临时替换」还是「渲染后 HTML 侧替换」。先验证再编码。
2. **新建暂存区 `frontend/src/utils/pendingImageStore.js`**：模块级单例；`add/get/entries/remove/clearProject/hasAny/previewUrl`；同 File 去重建 URL；remove/clear 时 revoke（复用 `imageRefCache` 范式）。同文件导出 token 工具（`TOKEN_RE/extractTokens/replaceToken/replaceAllTokens/hasToken`）。
3. **改造 `frontend/src/components/MarkdownEditor.vue`**：`makePasteHandler` 去掉 `imageApi.upload`，改为类型/大小校验 + MIME 补扩展名（抽用/对齐 `AiImageDrawer.extOfMime`）+ `pendingImageStore.add` + 插入 `![](sparkora-img:<id>)`。移除 `imageApi` 依赖若不再使用。
4. **预览即时可见 `usePreviewRender.js`**：渲染结果中把 `sparkora-img:<id>` 映射为 `previewUrl(id)`（按 1 的结论选时机）。确保不污染落库正文。
5. **`StepPreview.vue`**：
   - `goPublish`：在既有 dirty 保存**之后**、跳转之前，调用新增 `flushPendingImages()`（先保存 token 正文→逐张上传替换→保存最终正文→`flushSavePreviewStyle`→跳转）；失败停留并提示。
   - `copyRich`：`hasToken(contentMd)` 时拦截并提示。
   - 项目切换/`loadContent` 初始化：`clearProject(旧 projectId)`。
   - 建议将 flush 逻辑抽到 `frontend/src/composables/usePendingImageFlush.js`（保持 view ≤450 行、可测）。
6. **`StepPublish.vue`**：`doPublish` 开头检查当前版本 `contentMd` 含 token → 提示「请回预览页上传」并中止。
7. **后端 `PublishService.java`**：`publish` 取 `v.getContentMd()` 后、组装 `gzhContent` 前，若 `contains("sparkora-img:")` → `throw new IllegalStateException("正文含未上传的粘贴图，请回到预览页上传后再发布")`。
8. **文档同步**：`docs/spec/preview.md` 增补「剪贴板传图暂存」交互与限制；`docs/spec/publish.md` 增补发布防呆；必要时 `docs/spec/image.md` 补「暂存图不登记关联表」限制。

## 验证命令

- 前端：`cd frontend && npm run build`（必须通过）。
- 后端：`mvn -q -DskipTests compile`。
- 联调（`./dev.sh restart all` 后手测）：
  - AC1 粘贴 → Network 无 `/images/upload`；正文出现 token。
  - AC3 右侧预览即时显示图片。
  - AC4 点「去发布」→ 逐张上传、正文变公网 URL、落库正确、跳发布页。
  - AC5 断网/构造失败 → 停留提示、可重试。
  - AC6 删除占位后去发布 → 不上传、条目清理。
  - AC7 含 token 时复制被拦截。
  - AC8 直接访问发布页并发布含 token 正文 → 前端阻止；用 curl 直调 `POST /publish` → 返回中文 400。
  - AC9 切换项目 → 旧暂存不影响。
  - AC10 无粘贴的既有流程回归 + 构建通过。

## 风险文件与回滚点

- 高风险：`MarkdownEditor.vue`（粘贴主链路）、`usePreviewRender.js`（所有预览渲染都经此）。
- 回滚点：步骤 2-7 各自独立文件，按逆序还原即可；后端防呆单点可单独回退。无 DB 迁移。
- 提交前跑 `trellis-check`（规格合规 + 构建）。

## task.py start 前检查

- [x] `prd.md` 完成（含验收标准、范围、决策）
- [x] `design.md` 完成
- [x] `implement.md` 完成（本文件）
- [ ] `implement.jsonl` / `check.jsonl` 已按 sub-agent 模式填真实 spec 条目
