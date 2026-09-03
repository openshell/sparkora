# 配图并入预览：执行计划

## 阶段 1：后端状态机调整

- [ ] 1.1 `PreviewService.preview` 状态校验 `IMAGES_READY` → `VERSIONS_READY`（L65）。
- [ ] 1.2 `PublishService` 注释更新（L55）。
- [ ] 1.3 `VersionService` 注释更新（L102）。
- [ ] 1.4 删除 `ImageService.completeImages`（L251-275）。
- [ ] 1.5 删除 `ArticleProjectController.completeImages` 接口（L298-303）。
- 验证：`mvn -q -DskipTests compile`

## 阶段 2：前端流程与状态

- [ ] 2.1 `constants/project.js`：移除 `IMAGES_READY`、`VERSIONS_READY.step=2`、`maxReachableStepOf` 上限 3、`isPublishable` 改 `VERSIONS_READY|PUBLISHED_DRAFT`、`statusMeta` 加 `IMAGES_READY`→`VERSIONS_READY` 兼容映射。
- [ ] 2.2 `ProjectLayout.vue`：`STEPS` 四步、`routeStepIndex` 调整。
- [ ] 2.3 `StepVersions.vue`：`gotoNext` 跳预览、按钮文案、注释。
- [ ] 2.4 路由：删除 `project-images` 路由。
- 验证：`npm run build`

## 阶段 3：预览内配图能力

- [ ] 3.1 `StepPreview.vue`：`previewable` 改 `VERSIONS_READY|PUBLISHED_DRAFT`。
- [ ] 3.2 扩展配图面板：图库插入 + AI 生图（文生图/图生图）两个 tab，产物进图库后插入。
- [ ] 3.3 封面选择入口（复用 `setCover`）。
- [ ] 3.4 移除「完成配图」按钮与 `completeImages` 调用。
- [ ] 3.5 删除 `StepImages.vue` 文件。
- 验证：`npm run build`

## 阶段 4：文档同步

- [ ] 4.1 `docs/s0-spec.md`：状态机、步骤条、配图模块、预览/发布契约同步；车型库图片标注预留。
- 验证：人工核对 spec 与实现一致。

## 阶段 5：验收

- [ ] 5.1 前端 `npm run build` 通过。
- [ ] 5.2 后端 `mvn -q -DskipTests compile` 通过。
- [ ] 5.3 按 prd.md AC1-AC8 逐条核对。
- [ ] 5.4 提交 commit（中文，scope 如 `feat(S6)`）。

## 回滚点

- 每阶段结束可独立回滚；改动集中在状态白名单与前端常量/步骤，回滚成本低。
