# 执行计划：配图链路修复

> 权威需求见 `prd.md`，技术设计见 `design.md`。按顺序执行，每步末尾附验证命令。

## 0. 前置确认（实现前必做）

- [x] 定位 Bug1 的「自动刷新」触发源：代码级已确诊（非应用逻辑），详见「实测记录 · 步骤 0」；浏览器级复核属可选后续，不阻塞收口。
- [x] 用真实数据确认 `@wenyan-md/core` 对 `![](sparkora-img:x)` 的渲染输出含 `<img src="sparkora-img:x">`（沿用上任务实测结论，若已变则调整选择器）。

## 1. 新增正文引用解析（纯函数 + 测试友好）

- [x] 新建 `frontend/src/utils/bodyImageRefs.js`：`parseBodyImageRefs(md)` → `{ urls, tokenIds }`；`countBodyImages(md)`。
- [x] 处理边界：去重、忽略 `sparkora-img:` 前缀、容忍 title/空白。

## 2. 失效 token 可见化

- [x] `frontend/src/utils/pendingImageStore.js`：新增 `projectBodyTokens(html, resolver)`（DOM 级，替换 `mapTokenSrc` 的字符串实现；保留导出名兼容或统一改名并改调用点）。
- [x] `frontend/src/composables/usePreviewRender.js`：渲染链改 `sanitizeWenyanHtml(projectBodyTokens(raw, previewUrl))`。
- [x] `frontend/src/components/preview/PreviewPane.vue`：加 `:deep(.sparkora-img-missing)` 占位样式（虚线框、中文、≥44px）。

## 3. 计数口径统一

- [x] `frontend/src/views/project/StepPreview.vue`：
  - `insertedUrls` 改基于 `parseBodyImageRefs(contentMd).urls`；
  - `insertedCount` = 已就绪正文插图数；角标分母语义去掉封面（`snapshotImages` 仍可用于抽屉，但工具栏不再用它当分母）；如需展示待上传数，用 `tokenIds` 计数。
- [x] `frontend/src/components/preview/PreviewToolbar.vue`：按 AC6 定稿文案（含可选「待上传」提示）。
- [x] `frontend/src/views/project/StepPublish.vue`：`插图 N 张` 改为 `countBodyImages(contentMd)`（正文解析），不再用 `bodyImageIds.length`。

## 4. flush 顺序修正（R1.4）

- [x] `frontend/src/composables/usePendingImageFlush.js`：改为「上传→替换→`saveContent` 成功后才 `remove`」；持久化失败返回可重试错误并保留条目。
- [x] 上传成功后 best-effort `imageApi.addBodyImage`（取 `res.data.id`；失败 warn 不阻断）。

## 5. 阻断与警示

- [x] `frontend/src/views/project/StepPreview.vue`：计算 `unresolvedTokens`，顶部 `el-alert` 警示条。
- [x] `goPublish`/`copyRich` 拦截文案覆盖「失效」语义；`StepPublish.doPublish` 保留 token 阻断（后端防呆不动）。

## 6. 移除 AI 首次自动插入（R2）

- [x] `frontend/src/views/project/StepPreview.vue`：`onGenerated` 删除自动 `insertBodyImage`，仅 `refreshImgSnapshot()`。
- [x] 确认 `onSetCover` 只改封面（已满足），并回归「AI 生成→插入正文」按钮仍可用（用户实测通过，见「实测记录 · 联调手测」）。

## 7. 补登记关联表（R3.3）

- [x] `StepPreview.insertBodyImage`：`insertMd` 后 best-effort `addBodyImage(projectId, img.id)`（失败 warn）。
- [x] 粘贴图：见步骤 4（flush 内登记）。

## 8. 验证

- [x] `cd frontend && npm run build`（须通过）。
- [x] `mvn -q -DskipTests compile`（后端未改亦跑一次兜底；默认仓库只读时加 `-Dmaven.repo.local=/tmp/m2repo`）。
- [x] 联调手测：逐条走 AC1–AC7 → **用户实测通过**（见「实测记录 · 联调手测」）；AC8 为代码级结论，浏览器级复核可选。
- [x] grep 确认无遗留 `mapTokenSrc` 调用点、无 `bodyImageIds.length` 当插图数、无自动插入残留。

## 9. 规格同步

- [x] `docs/spec/image.md`：§7.7 债务更新（补登记范围）、§8 计数口径、失效占位语义。
- [x] `docs/spec/preview.md`：失效 token 占位 + 警示 + 阻断。
- [x] `docs/spec/publish.md`：插图计数口径。
- [x] `.trellis/spec/frontend/index.md`：如产生可复用约定（「会落库的正文引用临时资源 → 失效必须可见」），补一条。

## 实测记录

### 步骤 0 ·「自动刷新」触发源诊断（代码级结论；浏览器级复核待人工）

- **应用侧无任何整页重载逻辑**：`grep -rn "location.reload|window.location|router.go(0)|beforeunload|visibilitychange|pageshow" frontend/src/` → **零命中**；现存 `setInterval` 均为**局部轮询**（项目状态/规划/同步任务），不触发导航或重载。→ 「切走一段时间回来图自己没了」**不是应用逻辑**。
- **两类真实触发源**（均不可由应用拦截）：
  1. **浏览器标签丢弃/回收**（移动端与低内存桌面端常见，标签长时间后台 → 整页重载 → 内存暂存区清空）；
  2. **开发期 Vite 整页 reload**：`frontend/vite.config.js` 的 `optimizeDeps.include` 注释即记录过历史事故「optimized dependencies changed. reloading」；SFC 无法热替换的改动同样触发整页 reload。仅影响 `npm run dev`。
- **结论与处置**：符合 design §6 风险 R-a 的「应用无法阻止」分支，故按 design §7 收敛为「**可见 + 可处置 + 阻断发布**」（失效占位块 + 顶部警示 + 三处阻断），**未加 `beforeunload` 离开确认**（design 已定此取舍；PRD R1.5 的兜底项属未实现项，登记在 `docs/spec/preview.md` §5 已知限制）。
- 浏览器级复核（Network 面板 document 请求 / `performance.navigation`）**需人工在真机做**，本次未执行。

### 本次 check 已执行的验证

| 项 | 方式 | 结果 |
|---|---|---|
| 前端构建 | `npm run build`（frontend/） | ✅ 通过 |
| 失效占位样式真的进产物 | 查 `dist/assets/StepPreview-*.css` | ✅ `[data-v-c0cf7c9b] .sparkora-img-missing{...}`（`:deep()` 在 scoped+v-html 下正确编译，且 `.wenyan-preview` 容器带同 scope id） |
| 占位块不会被 sanitize 摘掉 | 读 `utils/wenyanRender.js:sanitizeWenyanHtml` | ✅ 只删 `script/iframe/object/embed/link/meta` + `on*`/`javascript:`，`class`/`role`/文本保留；`projectBodyTokens` 在其**之前**调用 |
| 正文引用解析边界 | node 直跑 `utils/bodyImageRefs.js` 20 个用例 | ✅ 见函数注释「已知边界」；两处刻意偏差（转义 `\![a](x)`、代码围栏内 `![](x)`）均为「多算」方向，不影响渲染/发布 |
| 渲染器与解析器口径一致 | node 直调 `@wenyan-md/core`（真实包）渲染 token 正文，再与 `parseBodyImageRefs` 对拍 | ✅ 渲染产物为 `<p><img src="sparkora-img:i1abc" alt="" title=""></p>`（`src` 属性在前，匹配 `img[src^="sparkora-img:"]`）；`![](x)` / `![图 [1]](x)` / 重复 token / 同 URL 不同 title 四组用例，解析出的 urls/tokenIds 与渲染出的 `<img>` 一一对应 |
| 补登记/上传接口契约 | 读 `ProjectImageController#modifyBodyImage` / `ImageController#upload` 与 `api/index.js` | ✅ `POST /api/projects/{id}/images/{imageId}/body?action=add`（`R<Void>`，幂等，ADMIN/EDITOR）；`POST /api/images/upload` multipart `file`+`projectId` → `R<ImageAssetEntity>` 取 `id`/`url`。两者权限均为 EDITOR，与 flush 唯一触发路径（去发布，EDITOR）一致，不存在 VIEWER 403 分支 |
| 后端兜底编译 | `mvn -q -DskipTests compile` | ✅ 通过（后端未改动，跑一遍兜底） |
| 死代码/残留 | grep `mapTokenSrc`/`snapshotCount`/`snapshotImages`/`bodyImageIds.length`/自动插入分支 | ✅ `frontend/src/` 零命中（旧标识符连历史说明注释也一并改写，避免误读为仍存在） |
| flush 重试收敛 | 代码推演（无法自动化，见下） | ✅ 第二次点「去发布」先走 `isDirty → saveContent` 落库 URL 版正文，`extractTokens` 转空、`changed=false` 直接放行，**不存在「不保存正文就跳转」** |
| 手动浏览器联调（AC1–AC7） | 用户在真实前端手工逐条走查 | ✅ 通过（用户确认「已正常」）——含失效占位可见、三处阻断、flush 后刷新不丢、封面不入正文、计数口径一致 |


## Review Gates

- Gate A（步骤 3 后）：计数口径自检 —— 封面不计入、无 token 时与旧值一致。
- Gate B（步骤 4 后）：flush 失败路径演练 —— 保存失败不跳转、条目保留。
- Gate C（全量后）：AC1–AC8 手测清单逐条勾选。

## Rollback

- 纯前端改动：`git revert` 本任务提交即可；无 DB 迁移与后端改动（后端防呆保留）。

### 联调手测（用户手工实测，2026-09-27）

用户在真实前端逐条走查 AC1–AC7，确认**「已经正常」**。覆盖点：

| AC | 走查内容 | 结论 |
|---|---|---|
| AC1 | 粘贴后刷新 → 失效占位块 + 顶部警示条可见 | ✅ |
| AC2 | 复制排版 / 去发布 / 确认发布 三处阻断 | ✅ |
| AC3 | 点「去发布」上传后刷新，图片仍在 | ✅ |
| AC4 | 保存失败不跳转、保留 token、可重试 | ✅ |
| AC5 | AI 生成 n=1 设为封面不入正文，「插入正文」仍可用 | ✅ |
| AC6 | 3 图库图 + 1 粘贴图 + 1 封面 → 预览/发布均 4 张，封面不计入 | ✅ |
| AC7 | `bodyImageIds` 与正文实际引用一致 | ✅ |

AC8（自动刷新触发源）为**代码级结论**：应用无任何整页重载逻辑，触发源是浏览器标签丢弃与开发期 Vite HMR，
两者均不可由应用拦截，故收敛为「可见 + 可处置 + 阻断发布」，未加 `beforeunload`。浏览器级复核非阻塞项。

> 补充：本任务收口前另完成 `09-27-wenyan-stale-conn`（发布超时 30s→180s），期间 docker 重建已确认
> 线上产物含本任务全部修复标记（`sparkora-img-missing` 样式与 JS 均在 `dist` 中），故用户走查的即最新代码。
