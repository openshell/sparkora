# 执行计划：图库 UI 优化

前置：`prd.md` / `design.md` 已确认。建议实施顺序 = 依赖顺序；每步末尾有验证口径，失败即停修不越步。

## 0. 准备

- [ ] `npm run build` 起点必须是绿的（记录基线）。
- [ ] `grep -rn "text.*plain" frontend/src/components/image/ frontend/src/views/ImageLibrary.vue` 记录现有叠加组合（R1 审计底稿）。
- [ ] 截改前图库页对照（dev 起服或复用既有视觉基线 PNG 作「Before」参照）。

## 1. R1 按钮留白（独立、可先行）

- [ ] `ImageLibraryToolbar.vue:57`「批量管理」去 `plain` → `text type="danger"`。
- [ ] 审计步骤 0 底稿：图库域内其余 `text + plain` 一并去叠加（只改图库域）。
- [ ] 验证：`npm run build`；dev 页目检工具条按钮。

## 2. R2 ImageCard 交互模型（与 R1 无耦合，顺序执行）

- [ ] 移除 `el-image` 的 `:preview-src-list` / `:initial-index` / `preview-teleported`（保留纯展示）。
- [ ] 卡片根 `@click` 透传 `index`（`v-for` 加 index → `card-click` payload 从 `img` 变 `(img, index)`）。
- [ ] `.hover-panel` `pointer-events: none` 恒定；`.hp-tag` / `.hp-src-text` / `.hp-actions` 保持 `auto + @click.stop`（核对 template 里 stop 已在按钮上）。
- [ ] hover 层删行：`hp-score` 文本行、`hp-gen` 行、`hp-src` 行、副行第三行（`score` 分支的重复 `.hp-sub`）——同步清理对应 scoped CSS。
- [ ] 新增 `.score-badge`（语义模式、非选择态显示，pointer-events none）。
- [ ] `.hp-actions :deep(.el-button)` padding 收敛 `4px 2px`（R1/A C-1 同步）。
- [ ] 验证：`npm run build`；dev 手测——点卡片空白区/文件名/副行都能开预览入口（此时预览入口暂由宿主补占位，见 §3）；点标签筛选不被劫持。

## 3. R3 ImagePreviewViewer 新组件 + 宿主接线

- [ ] `utils/imageDisplay.js` 新增 `viewerRowsOf(img, projects, sourceInfo)` 纯函数（行清单见 design §3.2，空值容错）。
- [ ] 新建 `components/image/ImagePreviewViewer.vue`：`el-image-viewer`（`:teleported="false"`）+ `<aside>` 信息面板 + 面板显隐开关；若自动引入解析失败，显式 `import { ElImageViewer }`（build + dev 双验证）。
- [ ] `ImageLibrary.vue`：`viewerVisible` / `viewerIndex` 状态；`onCardClick(img, index)` → 选择模式勾选 / 预览态打开；`computed` 派生当前 `img` + `sourceInfo`，clamp 索引；`@switch` 同步、`@close` 关、`@open-news` 上抛既有 `openNews`。
- [ ] 验证：dev 手测 AC-5——当前页连播、首张定位正确；面板字段在「AI 图 / 上传图 / 语义命中」三类数据上的空值表现；面板关合；ESC/遮罩点击关闭；明暗两主题。

## 4. R4 工具条分隔

- [ ] `tb-primary` 与 `tb-browse` 间加 `<el-divider direction="vertical" />`。
- [ ] 验证：`npm run build`；小窗口（1280 宽）下工具条换行不错乱。

## 5. 收尾验证门禁

- [ ] `npm run build` ✓
- [ ] `npx playwright test tests/visual/image-library --update-snapshots` 重录图库基线（确认 diff 仅图库页）。
- [ ] `npx playwright test`（全量门禁）✓
- [ ] 手工回归清单（AC-7）：浏览态预览 / 语义态角标+预览 / 选择模式勾选 / 批量管理入口 / 明暗主题。

## 6. 收尾（Phase 3 对接）

- [ ] 更新 `docs/spec/image.md` 图库交互相关小节（预览承载方式变化）。
- [ ] 若产出新约定（点击穿透模型 / viewer 宿主范式）→ `trellis-update-spec` 入 `.trellis/spec/frontend/`。
- [ ] commit：`fix(ui): 图库按钮留白与预览交互优化 + 全屏预览详情面板`（风格对齐仓库惯例）。

## 回滚点

- Step 1~4 均为独立小步 commit 候选；任一步失败 `git checkout -- <file>` 即回该步。
- 整体回滚 = revert 全部任务 commit（纯前端展示层，无迁移/接口）。