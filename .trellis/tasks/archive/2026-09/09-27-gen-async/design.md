# 生成链路异步化 技术设计

> 无 schema 变更、无新表、无新依赖。复用 ⑤ 的 `ProjectStatusService` 与既有 `store.startPolling`。
> 两个里程碑：M1（imitation/analyze + generate/versions）、M2（deep/generate 批量）。

## 0. 统一机制（三条链路共用）

### 载体重用：项目状态即占位

不需要新表/新行。三条链路的「进行中」即 `ArticleProjectEntity.status`：
- imitation/analyze：`GENERATING_BRIEF`（既有 `claimBriefGenerating`）
- generate/versions：`GENERATING_VERSIONS`（既有 `claimVersionsGenerating`）
- deep/generate：同 `GENERATING_VERSIONS`（复用 `claimVersionsGenerating`）

「占位」= 同步阶段 `claim*` 把 status 置生成中并提交（毫秒级），异步体完成后 `advance*` 或 `fail*` 推进。前端 `store.startPolling`（`project-detail.js:159`）已在轮询 `project.status` 翻转，无需新轮询端点。

### 同步/异步切分（每服务）

```
public 同步方法 start*(...)   // 校验 + claim*（毫秒级） + self.run*(...) + 返回占位标记
@Async void run*(...)         // 重取状态,执行 AI,advance*/fail*
```

- `@Async` 经自注入代理触发：`@Autowired @Lazy private XxxService self;`，且 `(self == null ? this : self).run*(...)`（单测直 new 兼容）——与 `ClarifyService.java:49-51,93` 逐字同范式。
- 异步体**先重取实体**（status/brief/styles 可能已变），不复用同步阶段快照。

### 响应契约

保持 `HTTP 200 + R.ok`，返回即时占位标记（**不引入 HTTP 202**，与 `DeepController.clarify` L74-78 先例一致）：
- imitation/analyze → `{status:"GENERATING_BRIEF"}`
- generate/versions → `{status:"GENERATING_VERSIONS"}`
- deep/generate → `{status:"GENERATING_VERSIONS", styleCount:N}`

错误映射不变：校验失败 400 / claim 冲突 409（`guardMsg`）/ 其他 500。

### 失败与陈旧

- 异步体失败 → `failBriefToDraft` / `failVersionsToReady`（写 `last_*_error`）→ 状态翻转即可被轮询捕获，前端既有告警渲染：`StepBrief.vue:27/44/114/187`（`lastBriefError`）、`ProjectLayout.vue:27`（`lastVersionError`）。
- 陈旧自愈：`stuckGenerating`（10min）允许生成中重触发；JVM 中途死亡残留的 `GENERATING_*` 由用户重试或 10min 后自愈。**页面刷新恢复**：`ProjectLayout.vue:114` watch status → `isGenerating` → 自动重启轮询。
- 与 ClarifyService 的差异：不清占位行（项目状态由 `fail*` 主动回退，不存在「删占位后按 project 取最新误判」问题）。

## 1. M1 — imitation/analyze 异步化

**改动点**：`ImitationService.java`、`ArticleProjectController.java:205-217`、前端 `StepBrief.vue`/`ProjectEdit.vue`。

### 后端

`analyze(Long projectId)`（`ImitationService.java:77-144`）拆分：

```java
// 同步:校验 + claim + 触发异步 + 返回标记
public Map<String, Object> analyze(Long projectId) {
    ArticleProjectEntity p = projectMapper.selectById(projectId);
    // ...既有校验(存在/IMITATION/原文非空/stuckGenerating)...
    statusService.claimBriefGenerating(projectId, p, "分析原文");
    (self == null ? this : self).runAnalyze(projectId);
    return Map.of("status", "GENERATING_BRIEF");
}

@Async
public void runAnalyze(Long projectId) {
    ArticleProjectEntity p = projectMapper.selectById(projectId);
    try {
        // ...既有 enabledStyles + chatJson + brief insert...
        statusService.advanceReady(projectId, b.getId(), Map.of("imitation_analysis", json...));
    } catch (Exception e) {
        log.warn(...); statusService.failBriefToDraft(projectId, e.getMessage());
        // 异步线程无调用方,catch 内吞掉(不再向上抛 AiException)
    }
}
```

- 新增字段 `@Autowired @Lazy private ImitationService self;`。
- 异步体**不再抛异常**（无调用方接收）——失败只落 `last_brief_error` + 日志。
- 控制器 `ArticleProjectController.java:209` 返回值语义变为占位标记（`R.ok` 不变，前端据 `data.status` 判断）；异常映射维持 400/409/500（校验/claim 仍在同步阶段抛）。

### 前端

- `StepBrief.vue:286-300` `onAnalyze`：改为「发起 + `ensureProject(force)`」，**删除手动 `ensureImitation`**；等待 `ProjectLayout` 的 status watch 自动 `startPolling`，`READY` 翻转时 `project-detail.js:168-173` 已 `ensureBrief`+`ensureImitation`。提示文案改「已开始分析」。
- `ProjectEdit.vue:198-204`：保持 `await projectApi.analyzeImitation(id)`（现在毫秒级返回）；成功后导航（分析结果由详情页轮询补齐）；失败静默（状态/`lastBriefError` 可见）。
- API timeout `api/index.js:50` 120000 → 可下调默认（非必须；保留无害）。

## 2. M2 — generate/versions 异步化

**改动点**：`VersionService.java`、`ArticleProjectController.java:235-257`、前端 `StepVersions.vue`。

### 后端

`generate(Long projectId, List<Long> styleIds)`（`VersionService.java:76-138`）拆分：

```java
public Map<String, Object> generate(Long projectId, List<Long> styleIds) {
    // 既有参数校验(1..10)+ 项目/brief/styles 存在性 + stuckGenerating
    statusService.claimVersionsGenerating(projectId, p, "生成版本");
    (self == null ? this : self).runGenerate(projectId, styleIds);
    return Map.of("status", "GENERATING_VERSIONS", "styleCount", styleIds.size());
}

@Async
public void runGenerate(Long projectId, List<Long> styleIds) {
    ArticleProjectEntity p = projectMapper.selectById(projectId);
    ArticleBriefEntity brief = briefMapper.selectById(p.getCurrentBriefId());
    List<StyleProfileEntity> styles = styleMapper.selectBatchIds(styleIds);
    // RAG 检索 + 循环 generateOne + insert(单版失败收集) 
    // created 非空 → advanceVersionsReady(projectId, first.getId(), partialErrors)
    // 全失败/异常 → failVersionsToReady(projectId, msg)
}
```

- 新增 `@Autowired @Lazy private VersionService self;`。
- 校验（含 styles 存在性）保留在同步阶段（400 语义在触发时即时反馈）；异步体重取 brief/styles。
- 失败在异步体内吞掉（落错误列）；不再向控制器抛 `AiException`。

### 前端

- `StepVersions.vue:263-277`（仿写分支）：`await generateVersions` 后**不再立即 `loadVersions()`/计数 toast**；改 `ensureProject(force)`（status→GENERATING_VERSIONS → watch 启轮询 → `VERSIONS_READY` 翻转 `ensureVersions`，`project-detail.js:174`）。toast 改「已开始生成 N 版」。清 `selectedStyleIds`、记 `lastStyleIds`。
  - 全失败时轮询翻转 `DRAFT`/`READY` → `project-detail.js:174` 的 `ensureVersions` + `last_version_error` 展示；`openAppendWith` 重试入口保留（失败风格预选）。
- 生成中骨架屏（`StepVersions.vue:13-20`）已由 `project.status` 驱动，无需改。

## 3. M2 — deep/generate 异步化（批量改造）

**问题**：当前 `/deep/generate`（`DeepController.java:128-160`）单风格单版，前端 `StepVersions.vue:286-297` 按风格**串行多次**调用。异步化后第 2 次调用会撞第 1 次占的 `GENERATING_VERSIONS`（409），多风格不可行。

**方案**：改为**批量触发一次** `{briefId, styleIds:[...]}`，一次 claim + 一个异步作业循环生成，语义对齐 `VersionService.generate`。

### 后端

- 控制器 `DeepController.generate`：body 解析 `styleIds` 数组（保留单 `styleId`/旧 `stylePrompt`+`styleName` 兼容）；委托新的批量方法。
- 新增编排方法（放 `DeepWriterService`，它已注入 `ArticleProjectMapper`，加 `ProjectStatusService`+`StyleProfileMapper`）：
  ```java
  public Map<String,Object> startBatch(Long projectId, Long briefId, List<Long> styleIds) // 同步:校验 brief/style + claim + self.runBatch
  @Async void runBatch(Long projectId, Long briefId, List<Long> styleIds) // 循环 write(projectId,briefId,prompt,name) + insert 已由 write 内部完成
  ```
  - claim 复用 `claimVersionsGenerating`（源态 READY/VERSIONS_READY + 陈旧）。
  - 成功：收集 created versionIds → `statusService.advanceVersionsReady(projectId, firstId, partialErrors)`（源态 `GENERATING_VERSIONS`，与 M2 versions 同路径）。
  - 失败：`failVersionsToReady`。
- `ProjectStatusService.advanceVersionsReadyFromReady`（`ProjectStatusService.java:148-164`）在 DeepController 改造后**无调用方** → 一并移除（含 `ProjectStatusServiceTest` 对应用例），避免死代码与状态源态白名单重复。
- `writerService.write` 已内含 brief 校验与 version insert（`DeepWriterService.java:69-130`），编排层只负责循环与状态推进。

### 前端

- `StepVersions.vue:286-297`（深度分支）：串行循环 → **单次** `generateDeep(id, briefId, styleIds)`；`ensureProject(force)`；toast「已开始生成 N 版」；清 `selectedStyleIds`。
- `api/index.js:45` `generateDeep` 签名 `(id, briefId, styleId)` → `(id, briefId, styleIds)`（数组）。
- 部分失败明细由 `last_version_error` → `ProjectLayout.vue:27` 顶部告警展示（移除前端逐风格 toast）。

## 4. 兼容性 / 回滚 / 测试

### 兼容性

- **API 契约变更**：三个端点的响应体从「产物实体」变为「占位标记」，且 `POST /deep/generate` 入参 `styleId`→`styleIds[]`。前端同批更新，无第三方消费者。
- 现有令牌/权限/错误码不变；DB 无变更。
- 单线程执行器：沿用 Spring Boot 默认 `@Async` 执行器（自定义线程池属 P2-⑮，本任务不做）；三条链路并发量低（内部系统），风险可接受。

### 回滚

纯代码回退（3 个服务 + 1 控制器 + 1 前端 store/3 视图 + api/index.js），无数据影响。回退后恢复同步阻塞行为。

### 测试

- **后端**：`ImitationServiceTest`/`VersionServiceTest`（如有）适配同步/异步拆分——`start*` 断言 claim 调用与占位返回；`run*` 断言 advance/fail 分支（mock `ProjectStatusService` + `AiClient`）。`ProjectStatusServiceTest` 移除 `advanceVersionsReadyFromReady` 用例。`DeepControllerContractTest` 构造/签名同步。
- **回归**：`mvn test` 全绿（当前 345）。
- **前端**：`npm run build`；手动核验三条链路的翻转刷新与失败告警。
- **AC 验证**：curl 计时确认端点 <1s 返回；重复触发 409；生成中刷新页面恢复轮询。

### 风险

| 风险 | 缓解 |
|---|---|
| 异步体内异常未落状态 → 卡 GENERATING_* | `run*` 顶层 try/catch 必调 `fail*`；`stuckGenerating` 10min 自愈兜底 |
| deep 批量 API 变更遗漏前端调用点 | grep `generateDeep` 仅 `StepVersions.vue` + `api/index.js` |
| 默认执行器无界 | 三条链路手动触发、并发低；线程池独立任务记录风险 |
| 轮询期间重复提交 | `isGenerating`（`constants/project.js:31-33`）已禁用按钮；后端 claim 二次兜底 409 |