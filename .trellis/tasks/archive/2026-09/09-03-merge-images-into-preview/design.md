# 配图并入预览：技术设计

## 1. 目标状态机

```
DRAFT → GENERATING_BRIEF → READY → GENERATING_VERSIONS → VERSIONS_READY → PUBLISHED_DRAFT(终态,可重发)
```

`IMAGES_READY` 彻底移除。`VERSIONS_READY` 后直接可进预览、可发布。

## 2. 后端改动

### 2.1 状态校验点（三处）

| 文件 | 现状 | 改为 |
|---|---|---|
| `PreviewService.preview` (L65) | `IMAGES_READY \| PUBLISHED_DRAFT` | `VERSIONS_READY \| PUBLISHED_DRAFT` |
| `PublishService` (注释 L55) | 依赖 preview 校验 | 随 preview 自动生效，仅更新注释 |
| `VersionService` (L102-106) | 状态守护 `READY/VERSIONS_READY`，注释提到 IMAGES_READY 之后拒绝 | 逻辑不变（`READY/VERSIONS_READY` 白名单已天然排除 PUBLISHED_DRAFT），仅更新注释 |

### 2.2 移除「完成配图」

- `ImageService.completeImages`（L251-275）：删除。
- `ArticleProjectController.completeImages`（L298-303）：删除接口 `POST /{id}/complete-images`。
- 前端 `imageApi.completeImages`（api/index.js L89）：删除。

> 决策：`complete-images` 直接删除而非保留 no-op。因为状态机已无 `IMAGES_READY`，该接口无存在意义；前端不再调用。内部接口，无外部依赖。

### 2.3 配图快照接口保留

`GET /api/projects/{id}/images`（`projectImages`）保留——预览/发布组装封面与插图仍依赖它。`setCover`/`addBodyImage`/`removeBodyImage` 保留——预览内配图仍需要。

## 3. 前端改动

### 3.1 步骤条（`ProjectLayout.vue`）
- `STEPS` 五步改四步：简报/版本/预览/发布（移除 `images`）。
- `routeStepIndex` 映射调整：`project-preview`→2，`project-publish`→3。

### 3.2 状态常量（`constants/project.js`）
- `PROJECT_STATUS` 移除 `IMAGES_READY`。
- `VERSIONS_READY` 的 `step` 改为 2（预览）。
- `maxReachableStepOf` 逻辑不变（`Math.min(activeStepOf(s), 4)` 改为 `Math.min(activeStepOf(s), 3)`）。
- `isPublishable` 改为 `s === 'VERSIONS_READY' || s === 'PUBLISHED_DRAFT'`。
- `statusMeta` 增加 `IMAGES_READY` 兼容映射到 `VERSIONS_READY`（处理历史残留数据，见 §6）。

### 3.3 版本步下一步（`StepVersions.vue`）
- `gotoNext`（L238-239）：`VERSIONS_READY` 后一律跳 `project-preview`（不再有 `project-images` 分支）。
- 按钮文案（L116）：`VERSIONS_READY` 时「选定当前版本 · 进入下一步（预览）→」。
- 注释（L113）更新。

### 3.4 预览步配图能力（`StepPreview.vue`）
- 移除 `previewable` 对 `IMAGES_READY` 的依赖：改为 `['VERSIONS_READY','PUBLISHED_DRAFT']`。
- 工具栏「插图」popover 扩展为配图面板，提供两个来源 tab：
  1. **图库**：现有 `bodyImages` 网格 + 全量图库可选（复用 `snapshotImages`），点击插入正文（现有 `insertBodyImage`）。
  2. **AI 生图**：文生图/图生图表单（从 `StepImages.vue` 迁移），产物进图库后插入。
- 封面选择：保留 frontmatter `cover` 机制，在配图面板提供「设为封面」入口（复用 `imageApi.setCover`）。
- 移除「完成配图」按钮与 `completeImages` 调用。

### 3.5 删除 `StepImages.vue`
- 删除文件；路由 `project-images` 移除。

## 4. 路由改动

- `router` 中 `project-images` 路由删除（需确认路由定义位置）。

## 5. 文档同步（`docs/s0-spec.md`）

- §4 当前进度、§状态机（L148-166）、§步骤条（L181-184）、§10 配图模块（L253-362）、§11 预览、§12 发布契约——全部同步：移除 `IMAGES_READY`、配图并入预览、`complete-images` 删除、`VERSIONS_READY` 后直接可发布。
- 车型库图片接入标注为**预留**（不新增接口/UI）。

## 6. 兼容性与回滚

- 状态机移除 `IMAGES_READY` 后，历史已处于 `IMAGES_READY` 的项目如何处理？——`IMAGES_READY` 在 DB 中可能残留。后端校验白名单不含 `IMAGES_READY` 会导致历史项目无法预览/发布。**决策**：前端 `statusMeta` 增加 `IMAGES_READY` 兼容映射到 `VERSIONS_READY`（归一化），避免数据迁移风险。
- 回滚：改动集中在状态校验白名单与前端步骤/常量，回滚成本低。

## 7. 车型库图片（预留，不实现）

`CarModelEntity.introImages`（官网 URL）转存本地后进图库/插入正文的能力，留待后续独立任务。本任务仅在 spec 标注预留，不触碰相关代码。
