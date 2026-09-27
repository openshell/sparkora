# 生成链路异步化 实施计划

> 按 M1 → M2 顺序；每个里程碑内含「后端 → 前端 → 验证」，可独立编译/构建。deep 批量改造（M2 第二部分）放最后，风险最高。

## 前置检查

- [ ] `git status` 干净基线
- [ ] `mvn -q -DskipTests compile` + `mvn test` 基线通过（345）
- [ ] `cd frontend && npm run build` 基线通过
- [ ] 确认 `ImitationServiceTest`/`VersionServiceTest`/`DeepWriterServiceTest` 是否存在（`ls src/test/java/com/sparkora/service src/test/java/com/sparkora/deep`）

## M1 — imitation/analyze 异步化

### 3.1 后端

- [ ] `ImitationService`：加 `@Autowired @Lazy private ImitationService self;`；`analyze` 拆为同步 `start`（校验+`claimBriefGenerating`+`self.runAnalyze`+返回 `Map.of("status","GENERATING_BRIEF")`）与 `@Async runAnalyze(Long)`（重取 p → chatJson → brief insert → `advanceReady`；catch 内 `failBriefToDraft` 不抛）
- [ ] `ArticleProjectController.java:205-217`：调用点适配（返回值改占位标记；异常映射不变）
- 验证：`mvn -q -DskipTests compile`

### 3.2 前端

- [ ] `StepBrief.vue:286-300` `onAnalyze`：删除手动 `ensureImitation`，改 `ensureProject(force)`；文案「已开始分析」
- [ ] `ProjectEdit.vue:198-204`：保持 await（毫秒级），确认失败静默分支仍可用
- [ ] 确认 `store.startPolling` 的 READY 翻转已覆盖 `ensureImitation`（`project-detail.js:168-173`，无需改）
- 验证：`npm run build`

## M2a — generate/versions 异步化

### 4.1 后端

- [ ] `VersionService`：加 `@Autowired @Lazy private VersionService self;`；`generate` 拆为同步 `start`（参数/项目/brief/styles 校验 + `claimVersionsGenerating` + `self.runGenerate` + 返回占位）与 `@Async runGenerate(Long, List<Long>)`（重取 p/brief/styles → RAG → 循环 `generateOne`+insert → `advanceVersionsReady` / `failVersionsToReady`）
- [ ] `ArticleProjectController.java:235-257`：返回占位标记；`NotReadyException`→409 映射保留（校验仍在同步阶段）
- 验证：`mvn -q -DskipTests compile`

### 4.2 前端

- [ ] `StepVersions.vue:263-277` 仿写分支：去掉立即 `loadVersions`/计数 toast，改 `ensureProject(force)`；toast「已开始生成 N 版」
- [ ] 保留 `lastStyleIds`/`selectedStyleIds` 清理与失败重试入口 `openAppendWith`
- 验证：`npm run build`

## M2b — deep/generate 异步化（批量改造）

### 5.1 后端

- [ ] `DeepWriterService`：注入 `ProjectStatusService` + `StyleProfileMapper`；新增同步 `startBatch(Long projectId, Long briefId, List<Long> styleIds)`（校验 brief/风格 + `claimVersionsGenerating` + `self.runBatch` + 占位）与 `@Async runBatch(...)`（循环 `write` → `advanceVersionsReady` / `failVersionsToReady`）
- [ ] `DeepController.java:128-160`：`generate` 解析 `styleIds[]`（兼容单 `styleId`/旧 stylePrompt+styleName）；委托 `startBatch`；移除控制器内 `advanceVersionsReadyFromReady` 调用
- [ ] `ProjectStatusService`：移除无调用方的 `advanceVersionsReadyFromReady`；`ProjectStatusServiceTest` 移除对应用例
- [ ] `DeepControllerContractTest` 构造签名/断言同步
- 验证：`mvn -q -DskipTests compile` + `grep -rn "advanceVersionsReadyFromReady" src` 应为空

### 5.2 前端

- [ ] `api/index.js:45` `generateDeep(id, briefId, styleIds)`（数组）
- [ ] `StepVersions.vue:286-297`：串行循环 → 单次调用；`ensureProject(force)`；toast「已开始生成 N 版」
- [ ] grep 确认 `generateDeep` 无其他调用点
- 验证：`npm run build`

## 6. 全量验证

- [ ] `mvn test` 全绿（345 基线 + 新增/改造用例）
- [ ] `grep 'set("status"' src/main/java` 仍只命中 `ProjectStatusService`（+ Car/News job + 创建 INSERT）
- [ ] `grep -rn "updateById" src/main/java` 无项目行全字段回写新增
- [ ] curl 计时（联调环境）：`POST /imitation/analyze`、`/generate/versions`、`/deep/generate` 均 <1s 返回
- [ ] 重复触发 → `R.fail(409)`
- [ ] 手动：生成中刷新页面，`ProjectLayout` 恢复轮询；成功翻转刷新；失败告警可见

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| M1 | 异步体未落状态卡 GENERATING_BRIEF | 顶层 catch 必调 `failBriefToDraft`；10min 自愈兜底；单文件 revert |
| M2a | 部分失败明细丢失 | `advanceVersionsReady` 的 `partialErrors` 语义保留；单文件 revert |
| M2b | API 入参变更、`advanceVersionsReadyFromReady` 移除影响面 | 单独 commit，可单独 revert；grep 确认调用点 |

## start 前检查

- [ ] `implement.jsonl` / `check.jsonl` 已填真实条目
- [ ] 用户已确认最终规划摘要（范围：只做 3 条；deep 批量改造已说明）