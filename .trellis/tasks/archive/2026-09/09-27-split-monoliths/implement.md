# 拆巨石 — 实施计划

> 三阶段独立可验证：M1 后端 → M2 StepPreview → M3 ImageLibrary。每阶段完成即跑对应验证，再进下一阶段。每阶段单 commit。

## 前置检查

- [ ] `git status` 干净基线（确认与既有 P1 提交无交叉）
- [ ] `mvn -q -DskipTests compile` + `mvn test` 基线（424）通过
- [ ] `cd frontend && npm run build` 基线通过
- [ ] 生成拆分前「路由清单」快照：`rg -n "@(Get|Post|Put|Delete)Mapping|@RequestMapping" src/main/java/com/sparkora/web/controller/ArticleProjectController.java > /tmp/routes-before.txt`
- [ ] 生成拆分前「@PreAuthorize 清单」快照

## Step M1: 后端控制器拆分

- [ ] 新建 `ProjectBriefController`（Brief + Imitation，4 端点）
- [ ] 新建 `ProjectVersionController`（Versions，6 端点）
- [ ] 新建 `ProjectImageController`（Images + Illustration，5 端点，含 `stringListOf`/`doubleOf`）
- [ ] 新建 `ProjectPreviewController`（Preview + publish bridge，4 端点）
- [ ] 新建 `ProjectPublishController`（Publish，1 端点；如判定过薄可并入 Preview 控制器，择一记录）
- [ ] 精简 `ArticleProjectController`（仅 Project CRUD 5 端点 + 其依赖）
- [ ] （可选）`publishOptions` 下沉 service
- 验证：
  - [ ] `mvn -q -DskipTests compile` 通过
  - [ ] `mvn test` 全绿（424）
  - [ ] 路由清单 diff：`rg ... src/main/java/com/sparkora/web/controller/Project*.java ArticleProjectController.java` 与 `/tmp/routes-before.txt` 逐条等价
  - [ ] `@PreAuthorize` 逐端点一致
  - [ ] `wc -l` 各控制器 ≤ ~180 行

## Step M2: StepPreview.vue 拆分

- [ ] 新建 `frontend/src/components/preview/PreviewToolbar.vue`
- [ ] 新建 `frontend/src/components/preview/PreviewPane.vue`
- [ ] 新建 `frontend/src/components/preview/PreviewImageDrawer.vue`
- [ ] 新建 `frontend/src/composables/usePreviewLibrary.js`
- [ ] 新建 `frontend/src/composables/usePreviewSuggestions.js`
- [ ] 新建 `frontend/src/composables/usePreviewStylePersist.js`
- [ ] 改造 `StepPreview.vue` 使用上述文件（保留跨切状态与编排）
- 验证：
  - [ ] `cd frontend && npm run build` 通过
  - [ ] `wc -l` StepPreview.vue ≤ ~450，各新文件 ≤ ~400
  - [ ] 人工核对：预览渲染/主题持久化/保存/复制/发布跳转/配图插入/封面/建议采纳与忽略/滚动同步
  - [ ] grep：无 `.vue` 直调 `http`（API 走 `api/index.js`）；图标显式 import

## Step M3: ImageLibrary.vue 拆分

- [ ] 新建 `frontend/src/components/image/ImageLibraryToolbar.vue`
- [ ] 新建 `frontend/src/components/image/ImageCard.vue`
- [ ] 新建 `frontend/src/components/image/ImageSemanticBar.vue`
- [ ] 新建 `frontend/src/components/image/ImageTagDialog.vue`
- [ ] 新建 `frontend/src/components/image/ImageBulkTagDialog.vue`
- [ ] 新建 `frontend/src/composables/useSemanticSearch.js`
- [ ] 新建 `frontend/src/composables/useBulkSelect.js`
- [ ] 新建 `frontend/src/composables/useImageFilters.js`
- [ ] 改造 `ImageLibrary.vue` 使用上述文件
- 验证：
  - [ ] `cd frontend && npm run build` 通过
  - [ ] `wc -l` ImageLibrary.vue ≤ ~450，各新文件 ≤ ~400
  - [ ] 人工核对：上传/AI 生图/语义搜索/过滤+路由同步/批量选择与删除/批量标签/单图标签/重生成/来源跳转/分页

## Step M4: 文档 + 全量验证

- [ ] 更新 `docs/spec/**` 中指向 `ArticleProjectController` 的方法/文件引用（project-lifecycle/preview/publish/image/imitation/version-generation）
- [ ] 更新 `.trellis/spec/**` 中相关引用
- [ ] `mvn -q -DskipTests compile` + `mvn test`（424）+ `npm run build`
- [ ] `wc -l` 三巨石瘦身核对 + 无死链 grep

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| M1 | 路由/鉴权漂移 | 单 commit revert；AC4/AC5 清单比对拦截 |
| M1 | Spring 映射冲突（同 verb+path） | 启动/编译期即报，编译验证拦截 |
| M2 | 滚动同步/样式 scoped 回归 | 单 commit revert |
| M3 | 路由同步 watcher 生命周期/批量耦合 | 单 commit revert |
| 全局 | 一次改太多难定位 | 三阶段三 commit，禁止合并提交 |

## 提交约定

- `refactor(web): ArticleProjectController 按子域拆分（路由/鉴权等价）`
- `refactor(ui): StepPreview 抽子组件 + composable`
- `refactor(ui): ImageLibrary 抽子组件 + composable`
- `docs(spec): 巨石拆分后的权威代码路径同步`

## start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] 用户已确认最终规划摘要
