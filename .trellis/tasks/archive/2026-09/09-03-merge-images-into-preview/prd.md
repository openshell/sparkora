# 配图并入预览：移除独立配图步骤与 IMAGES_READY 状态

## Goal

将独立配图步骤（Step3 / `StepImages.vue`）并入预览步骤，彻底移除 `IMAGES_READY` 状态，`VERSIONS_READY` 后直接可发布；预览界面内提供配图能力（图库插入 + AI 生图）。车型库图片接入**预留，本任务不开发**。

## Background / 现状问题

- 当前五步流程：简报 → 版本 → **配图(Step3)** → 预览(Step4) → 发布(Step5)，状态机 `VERSIONS_READY → IMAGES_READY → PUBLISHED_DRAFT`。
- 配图与预览割裂：配图步选好封面/插图后必须切到预览步才能看排版效果，来回切换体验差。
- 「选用」与「插入」概念重复：`StepImages.vue` 里"加插图"，`StepPreview.vue` 里又"插入插图"，两处操作同一批图，职责重叠。
- 配图本质是"在正文里插图并看效果"，与预览强耦合，独立成步骤反而打断流程。

## 决策（用户已确认）

1. **彻底移除 `IMAGES_READY` 状态**：`VERSIONS_READY` 后直接可进预览、可发布。状态机变为 `DRAFT → GENERATING_BRIEF → READY → GENERATING_VERSIONS → VERSIONS_READY → PUBLISHED_DRAFT`。
2. **车型库图片接入预留，本任务不开发**（`CarModelEntity.introImages` 官网图转存本地等能力留待后续）。
3. 创建 Trellis 任务并进入规划。

## Requirements

### R1 流程与状态机
- 五步改四步：简报 → 版本 → 预览 → 发布；移除独立配图步骤。
- 彻底移除 `IMAGES_READY` 状态；`VERSIONS_READY` 后直接可进预览、可发布。
- 移除「完成配图」动作及其状态推进（`complete-images` 接口与按钮不再需要）。

### R2 预览内配图能力
- 预览界面内提供配图入口，支持两种来源：
  1. **图库插入**：从图库选图插入正文光标处（复用现有 `insertBodyImage` 机制）。
  2. **AI 生图**：文生图/图生图，产物进图库后插入正文（复用现有 `imageApi.generateText/generateFromImage`）。
- 封面选择能力保留（frontmatter `cover` 元信息，不在正文渲染）。
- 配图落点规则不变：插图落点完全由正文 markdown 引用决定，未被引用的选定插图不自动追加文末。

### R3 车型库图片（预留）
- 本任务不实现车型库图片接入；仅在 spec 中标注为预留能力，不新增接口/UI。

## Acceptance Criteria

- [ ] **AC1**：`ProjectLayout.vue` 步骤条为四步（简报/版本/预览/发布），无独立配图步骤。
- [ ] **AC2**：`constants/project.js` 状态机移除 `IMAGES_READY`；`VERSIONS_READY` 后 `maxReachableStepOf` 解锁预览与发布。
- [ ] **AC3**：后端 `PreviewService.preview` 状态校验改为 `VERSIONS_READY | PUBLISHED_DRAFT`；`PublishService` 同源校验随之生效。
- [ ] **AC4**：`VersionService` 状态守护更新——`VERSIONS_READY` 之后（即 `PUBLISHED_DRAFT`）已触发下一步，再生成版本拒绝。
- [ ] **AC5**：`complete-images` 接口与「完成配图」按钮移除（或改为幂等 no-op，见 design 决策）。
- [ ] **AC6**：预览界面提供图库插入 + AI 生图配图入口，产物可插入正文并实时渲染。
- [ ] **AC7**：`docs/s0-spec.md` 状态机、步骤定义、配图模块、预览/发布契约同步更新；车型库图片标注为预留。
- [ ] **AC8**：前端 `npm run build` 通过；后端 `mvn -q -DskipTests compile` 通过。

## Notes

- 保持 `prd.md` 聚焦需求与验收；技术设计见 `design.md`，执行计划见 `implement.md`。
- 车型库图片接入为后续独立任务，本任务不触碰 `CarModelEntity.introImages` 相关逻辑。
