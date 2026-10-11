# 技术设计：图库 UI 优化

对应 `prd.md` R1~R4。前端 only，不触后端、不触 `docs/spec` 字段契约（纯展示交互重排）。

## 1. 改动面与组件边界

| 文件 | 动作 | 摘要 |
|---|---|---|
| `frontend/src/components/image/ImageCard.vue` | 改 | hover 层瘦身 + 点击穿透模型 + 分数角标；去掉 `el-image` 内置 viewer |
| `frontend/src/components/image/ImageLibraryToolbar.vue` | 改 | 「批量管理」去 `plain`；主操作/浏览组间加分隔 |
| `frontend/src/components/image/ImagePreviewViewer.vue` | 新建 | `el-image-viewer` 单实例宿主 + 右侧信息面板 |
| `frontend/src/views/ImageLibrary.vue` | 改 | 预览打开/切换/关闭状态管理；`onCardClick` 接 index |
| `frontend/src/utils/imageDisplay.js` | 改 | 新增面板行拼装纯函数（`viewerMetaOf` 等），多组件复用 |

组件落位遵循 spec「大组件拆分落位」：viewer 收进 `components/image/`，派生拼装进 `utils/`。

## 2. 交互模型（核心变化）

**Before**：预览 = 点 `el-image`（内置 viewer）；hover 层吞掉中下部点击。
**After**：预览 = 点卡片根元素（宿主托管单实例 viewer）；hover 层非交互行鼠标穿透。

```
.img-card @click="emit('card-click', img, index)"
  el-image            —— 纯展示（src=thumbUrl/url, 无 preview-src-list）
  .src-tag            —— pointer-events:none 不变
  .check-box          —— 选择模式勾选视觉（点击仍走卡片根 → toggleSelect）
  .hover-panel        —— pointer-events: none 恒定（去 focus-within 的 auto）
     .hp-name/.hp-sub/.hp-score   非交互文本，点击穿透到卡片根 → 预览
     .hp-tag / .hp-src-text       pointer-events:auto + @click.stop（保留既有 emit）
     .hp-actions 按钮组           @click.stop 不变
```

- `@click.stop` 的交互元素与预览互斥关系不变；非交互行穿透形成「点卡片任意处=看图」。
- 移除 hover-panel 的 `pointer-events: auto` 切换后，`:hover` 显隐动画/`:focus-within` 兜底逻辑保持。
- 选择模式（`selectMode`）：卡片点击=勾选（现状保留）；卡片不再提供预览入口（非目标）。

## 3. 全屏预览：宿主托管单实例 viewer

### 3.1 数据流

```
ImageLibrary.vue（宿主持有状态）
  viewerVisible: ref(false)
  viewerIndex: ref(0)                 // 由 v-for 索引直传，修 indexOf 错位
  onCardClick(img, index): selectMode ? toggleSelect : (viewerIndex=index; visible=true)
        │ v-model / props / emits
        ▼
ImagePreviewViewer.vue
  props: urlList(pageOriginUrls), index, img(当前对象), sourceInfo(当前图来源追溯), canClosePanel?
  emits: update:visible, switch(i)→宿主同步 viewerIndex, open-news(sourceInfo)
  内部: <el-image-viewer :url-list :initial-index @switch @close> + <aside .viewer-info>
```

- `img` / `sourceInfo` 由宿主 `computed(() => images.value[viewerIndex.value])` / `sourceInfo(cur.id)` 派生，viewer 组件保持纯声明式（props in / emits out，不得改宿主状态）。
- `switch` 事件由 EP viewer 左右箭头/键盘触发 → 宿主更新 `viewerIndex` → 面板信息跟随。索引边界：宿主 clamp `[0, urlList.length)`。
- `close` → `update:visible=false`；ESC/关闭键均由 EP viewer 内置处理。
- **原图约定**：`urlList` 继续用 `pageOriginUrls`（原图 `url`），面板字段从当前 `img` 对象读取——不引新请求。

### 3.2 信息面板行清单（自上而下）

| 行 | 内容 | 数据源 | 容错 |
|---|---|---|---|
| 文件名 | `fileName` | img | 空则整区块隐藏 |
| 基础行 | 来源 · 项目 · 宽×高 | `sourceLabel/hpSubText` 复用 | `projectId==null` 不显示「全局」臆断（`projectLabel` 现约定） |
| 创建 | `createdBy · shortTime(createdAt)` | img | 空段过滤 |
| 生成信息 | `genModel · genSize` | img（AI 来源才有） | 非空才渲染 |
| Prompt | `promptText` 全文（可滚动块） | img（AI 来源才有） | 非空才渲染；空态隐藏 |
| 标签 | tag pills（只展示，不交互） | img.tags | 数组空隐藏 |
| 语义 | 相关度 % · `sourceText | img.score == null 隐藏（浏览态恒无） |
| 来源追溯 | 「来源：<标题> · <日期>」点击跳原文 | sourceInfo.news | emit('open-news') 上抛宿主 |

- 拼装逻辑抽 `utils/imageDisplay.js` 纯函数（如 `viewerRowsOf(img, projects, sourceInfo)` 返回 `{rows: [{key,label,value|full}], prompt: str|null, news: …}`），便于单测与复用、避免组件内长模板条件堆叠。
- 面板显隐开关：本地 ref（组件内部状态，不持久化），关闭时 `aside` 收起、viewer 仍在——纯看图。

### 3.3 `el-image-viewer` 集成注意

- 自动引入应可解析 `<el-image-viewer>`；实现时验证，若失效显式 `import { ElImageViewer } from 'element-plus'`（spec「动态组件必须显式 import」精神的兜底条款）。
- 叠层：viewer 组件 `:teleported="false"` 于包裹层，包裹层 `position:fixed; inset:0; z-index`，信息面板同壳右侧 `position:absolute`，z-index 高于 viewer 背景罩但低于其工具条，不遮操作按钮。
- `hide-on-click-modal`：保留点击遮罩关闭（现状语义）。
- 明暗主题：面板配色走 `main.css` 变量（`--card/--ink/--line/--brand`），暗色自动跟随；深底白字区沿用 hover 层同款做法。

## 4. 按钮样式修正细节

- `.hp-actions :deep(.el-button) { padding: 4px 2px; }`——消除文本按钮 11px 水平内边距造成的视觉空白；行内间距仍由 `gap: var(--sp-3)` 控制。
- 「批量管理」：`text type="danger" plain` → `text type="danger"`（红字无框，语义为轻量入口）。
- 审计范围：`grep -n "text.*plain" frontend/src` 仅图库域（`ImageLibrary.vue`/`components/image/*`）修改，其它模块同样问题只记录不动（本任务边界）。
- 工具条分组分隔：`tb-primary` 与 `tb-browse` 之间插 `<el-divider direction="vertical" />`，不重排控件。

## 5. 相关度角标（语义模式）

- `.hp-score` 文本行移除，改 `.score-badge`：绝对定位 `top right`（选择模式下与勾选框上下错位或仅浏览态显示），`pointer-events:none`，样式仿 `.src-tag`（半透明深底白字小圆角）。
- 分数解释原文（`sourceText`）在列表层不再展示 → 预览面板承接。

## 6. 兼容与回滚

- **行为兼容**：`filter-tag`/`edit-tags`/`regen`/`delete`/`open-news`/勾选/批量/密度切换/筛选 chip 语义全部不变；唯一改变=预览触发方式与详情承载位置。
- **视觉基线**：`image-library.visual.spec.js` 基线需重录（`--update-snapshots`），其余 spec 原则上不动像素。
- **回滚形状**：纯前端展示层改动，无迁移无接口变更；`git revert` 单 commit 即可完整回退。新组件独立文件，删除即回滚到内置 viewer 行为（保留旧 `ImageCard` el-image 用法在 git 历史）。
- **渐进验证**：面板字段行全部 `v-if` 非空渲染，语义命中（`ImageSearchHit` 子集）与浏览态实体两种数据形状分别实测。

## 7. 验证计划（概要，详见 implement.md）

`npm run build` → `npx playwright test tests/visual/image-library`（更新基线）→ 全量 `npm run test` → 手工回归 AC-2/AC-4 关键路径（明暗两主题）。