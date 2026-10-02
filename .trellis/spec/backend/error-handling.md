# Error Handling

> How errors are handled in this project.

---

## Overview

统一响应包装 `com.sparkora.common.R<T>` = `{code, msg, data}`，`code=0` 成功。**所有业务失败（含登录失败、生成失败）均为 HTTP 200 + `R.fail(code, msg)`**，前端不能只依赖 axios 错误拦截器，必须检查 `code`。msg 一律中文提示。

---

## Error Types

| 异常类 | 语义 | 常见抛点 |
|---|---|---|
| `IllegalArgumentException` | 客户端参数/前置条件不满足 | service 校验（项目不存在、缺原文、缺风格等） |
| `IllegalStateException` | 状态冲突/并发防护 | 生成中重触发（409 语义）、下游已触发拒绝回退 |
| `com.sparkora.ai.AiException` | AI 调用失败 | AiClient / BriefService / ImitationService 等 |
| `NotReadyException` | 前置数据未就绪（如未生成 brief） | VersionService |

---

## Error Handling Patterns

### Convention: 控制器按子域拆分，每个只注入自身依赖（09-27-split-monoliths）

项目子资源端点不再堆在单个巨石控制器：`ArticleProjectController` 仅承载 Project CRUD；简报/仿写、版本、配图+配图建议、预览+发布参数、发布各自独立控制器，统一挂 `@RequestMapping("/api/projects/{projectId}")`（对齐 `DeepController` 的 `/api/projects/{projectId}/deep` 先例，无共享基类）。

- **路径/方法/`@PreAuthorize`/返回类型/异常映射逐字等价**：拆分是纯搬迁，方法体不改；HTTP 最终路径必须与拆分前一致（用 `verb + full path` 清单 diff 验证）。
- **已知不一致保留**：`preview` 端点 `IllegalStateException→400`（其余子域→409）是历史行为，搬迁时**逐字保留**，不得借机「修正」。
- 新增子资源端点时放入对应子域控制器；仅注入该子域用到的依赖，避免重新堆积。

### 控制器错误映射（惯例，参照 DeepController / ProjectVersionController / ProjectImageController / ProjectPreviewController）

```java
try {
    return R.ok(service.call(...));
} catch (IllegalArgumentException ex) {
    return R.fail(400, ex.getMessage());   // 参数/前置错误
} catch (IllegalStateException ex) {
    return R.fail(409, ex.getMessage());   // 状态冲突（生成中重触发/状态机回退）
} catch (Exception ex) {
    return R.fail(500, "操作失败: " + ex.getMessage());
}
```

### `@Valid` DTO 校验失败的统一映射（ApiExceptionHandler）

`@Valid @RequestBody` 校验失败时 Spring 默认返回非 `R<T>` 的 400 body，违反「所有业务失败 HTTP 200 + `R.fail`」契约。`ApiExceptionHandler` 已统一兜底：

```java
@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
public R<Void> handleBind(BindException ex) {
    String msg = ex.getBindingResult().getFieldErrors().stream()
            .findFirst().map(f -> f.getDefaultMessage()).orElse("参数校验失败");
    return R.fail(400, msg);
}
```

- 新增带 `@Size`/`@NotBlank` 等约束的 DTO 时无需额外处理，错误信息取首个字段的中文 `message`。
- `MethodArgumentNotValidException extends BindException`，一个 handler 覆盖两者，避免 ambiguous mapping。

### Convention: 禁止把框架内部异常串直接透给用户（09-27-wenyan-stale-conn）

`catch (Exception e) → R.fail(500, "操作失败: " + e.getMessage())` 这条**看似无害的兜底**是反模式：
`e.getMessage()` 在框架包装类上常常是**纯内部串**，既不指向根因也不含可执行动作。

09-27 实际被投诉的报错即此类：

```
发布请求失败: Error while extracting response for type [java.lang.String] and content type [application/octet-stream]
```

用户完全无法据此排障——它既没说「这是超时」，也没说「服务端还在跑」。

**要求**：

1. **传输层异常必须归因后再上抛**：抽一个纯函数遍历**整条 cause 链**做中文归因
   （先例 `WenyanServerService.describeTransportFailure`：超时类型 → `请求超时`；框架内部串片段 → 中性中文；
   否则取**根因**消息并压空白、截断）。纯函数应抽为可单测的静态方法（先例
   `WenyanServerServiceTransportTest`）。
2. **框架内部串要显式中和**：用「小写片段常量 + 比对前 `toLowerCase()`」的映射表把已知内部串换掉
   （先例 `WenyanServerService.FRAMEWORK_NOISE`）。`Error while extracting response`、
   `No suitable HttpMessageConverter`、`Unknown content type` 是三个高频项。
   **片段必须小写**且按框架**真实拼写**写（camelCase 不插空格）——大小写或空格写错都会静默漏判。
3. **兜底文案要压长度**：`getMessage()` 可能极长，既灌进 `last_*_error` 列（截断 990/1000）又灌进前端黄条。
   归因结果先 `replaceAll("\\s+", " ")` 再截断（先例截 200 字）。
4. **归因顺序：超时优先于框架串**。框架串是「读响应失败」的**外层包装**，若不先判 cause 链里的超时类型，
   超时会被误归因为「响应内容无法解析」，把「服务端可能还在处理」这一关键语义丢掉。

> **Warning**: 非幂等的下游调用（写草稿/写库/扣款/发消息）超时，**错误文案必须说明「可能已生效」并指引先到下游确认**，
> 且**绝不自动重试**。超时不等于失败——详见 `external-cli-integration.md` 的同名约定。

> **Warning（Boot 4 传输引擎漂移，C0 踩坑）**: 上述归因依赖 **JDK HttpClient 的 `HttpTimeoutException`**。
> Boot 4 的 `ClientHttpRequestFactoryBuilder.detect()` 按 `HttpComponents > Jetty > Reactor > Jdk > Simple` 探测；
> 一旦 classpath 出现 Reactor Netty（如引入 `spring-ai-starter-model-openai` 会**传递带入** `spring-boot-starter-webclient` → `reactor-netty-http`），
> `detect()` 会从 JDK HttpClient **静默改选 Reactor**，读超时抛的是 Netty `ReadTimeoutException`（`RuntimeException`，**不属** JDK/Simple 超时族）
> → 归因函数识别不到 → 超时被误判为普通传输失败、并泄漏框架串。
> **约定**：本项目所有 `RestClient` 传输引擎**显式 `.jdk()`**（`ClientHttpRequestFactoryBuilder.jdk()`），
> **禁用 `.detect()`**；超时时长语义（connect/read）不变。回归锁定见 `WenyanServerServiceTransportTest.传输引擎锁定为JdkHttpClient而非自动探测`。

### 状态机生成类服务（BriefService / ImitationService 同构）

1. 前置检查：`IllegalArgumentException`（项目不存在/模式不符/缺素材）。
2. 并发防护：生成中未过期（`updated_at` 距今 < 10 分钟 `STALE_GENERATING_MS`）抛 `IllegalStateException`；陈旧自愈放行。
3. 原子抢占：`UpdateWrapper` 条件更新（WHERE 状态白名单 + 陈旧分支限定生成中状态），`claimed == 0` 抛 `IllegalStateException`（409）。
4. AI 调用失败：**状态回退 DRAFT + 写 last_*_error（截断 1000 字符）**，再抛异常给控制器映射 500/200+fail。
5. 失败回退与成功推进一律用 `UpdateWrapper` **条件更新**（`WHERE id + 状态白名单`）精确 set 目标列，不 `selectById` 再 `updateById`（后者全字段写回会覆盖并发推进的状态/其他列，`fresh` 重取也仍有窗口）。失败分支限定「仅生成中状态可回退」；项目被并发删除时 `update` 影响 0 行，天然幂等、无需判 null。

> **Warning**: 失败回退不得复用方法开头的 `p` 对象做 `updateById` 全字段回写，也不得再走 `fresh = selectById` + `updateById`——两者都会用旧快照覆盖生成期间其他请求更新的列。条件 `UpdateWrapper`（`eq("id", …).in("status", 生成中状态…).set(...)`）是唯一安全写法，先例：`BriefService`/`ImitationService`/`VersionService`（09-27-p0-hardening R2）。

### 状态机写权收敛（09-27-state-machine-service 先例：ProjectStatusService）

项目 `status` / `last_brief_error` / `last_version_error` / `last_publish_error` 的**全部写入收敛到 `com.sparkora.service.ProjectStatusService` 单一服务**（`.trellis` P1-⑤ 整改）。生成链路服务（BriefService/ImitationService/VersionService/DeepController/PublishService/ClarifyService）**纯委托**，不得再手写状态 `UpdateWrapper`：

```java
// 抢占(claimed==0 服务内抛 409 语义,提示语用调用方刚读的 p 快照保证不变)
statusService.claimBriefGenerating(projectId, p, "生成简报");
// 成功推进(extraCols 业务列同条 UPDATE 写入,如 imitation_analysis,保持原子性)
statusService.advanceReady(projectId, b.getId(), Map.of("imitation_analysis", json));
// 首版两拆分/失败回退/发布终态同理:
statusService.claimDeepVersionsGenerating(projectId, p, "生成版本");            // 深度链路抢占,源态 READY/DRAFT/VERSIONS_READY
statusService.advanceVersionsReady(projectId, first.getId(), partialErrors);   // 源态 GENERATING_VERSIONS(多版本与深度批量共用)
statusService.failBriefToDraft(projectId, reason);     // 截断 1000 收在服务内
statusService.failVersionsToReady(projectId, reason);
statusService.markPublished(projectId, mediaId, theme, now);
statusService.markPublishFailure(projectId, message);  // 压缩空白+截断 990+吞异常口径收在服务内
statusService.writeBriefError(projectId, reasonOrNull); // 单列写入/清空(ClarifyService 异步链路)
```

- **唯二例外**（不走状态服务）：`ArticleProjectController` 创建时 INSERT 初始 DRAFT（非状态机转换）；`V1__baseline.sql` 启动回填（存量数据修复，已随 Flyway 子任务固化为基线，不再每次启动执行）。
- **常量与判定收编**：`STALE_GENERATING_MS`（10 分钟）唯一定义在状态服务；`stuckGenerating(p)` / `guardMsg(p, action)`（409 守卫提示语）由服务持有，调用方不再各自复制。
- **语义不变契约**：各转换的 WHERE 状态白名单、SET 列、两拆分顺序、截断口径（1000/990）、409 提示语与 P0 修复后实现逐字等价——新增/修改转换时必须在 `ProjectStatusServiceTest` 补对应断言（WHERE 白名单/两拆分/截断）。
- **源态白名单不同的转换不合并**：多版本链路源态 `READY/VERSIONS_READY`（`claimVersionsGenerating`），深度批量链路源态 `READY/DRAFT/VERSIONS_READY`（`claimDeepVersionsGenerating`，因 `/deep/generate` 可从 DRAFT「跳过简报」或 VERSIONS_READY「追加」进入）——语义不同，显式化为两个方法。
- 历史教训：状态推进逻辑散落多处曾产出 09-10-versions-page-fix（深度链路漏推状态机）与 P0-②（8 处 updateById 并发回写）两类缺陷；收敛后新链路只做委托，落库语义单点维护。

### 生成链路异步切分（09-27-gen-async 先例：start*/run*）

需要长耗时 AI 的端点改为「同步毫秒级返回 + 后台 `@Async` 执行 + 前端轮询状态翻转」时，统一按此切分（进度载体复用项目状态机，不新建表）：

```java
// 同步:校验(400/409 语义即时反馈) + claim(置 GENERATING_*) + 自注入代理触发异步 + 返回占位标记
public Map<String,Object> start(Long projectId, ...) {
    ArticleProjectEntity p = projectMapper.selectById(projectId);
    // ...既有校验(存在/模式/素材/并发防护)...
    statusService.claimVersionsGenerating(projectId, p, "生成版本");
    (self == null ? this : self).runGenerate(projectId, ...);   // self==null 兼容单测直 new
    return Map.of("status", "GENERATING_VERSIONS", "styleCount", n);
}

@Async
public void runGenerate(Long projectId, ...) {
    try {
        ArticleProjectEntity p = projectMapper.selectById(projectId);   // 重取,不复用同步阶段快照
        // ...AI 调用 + 产物落库...
        statusService.advanceVersionsReady(projectId, firstId, partialErrors);
    } catch (Exception e) {
        log.warn(...); statusService.failVersionsToReady(projectId, e.getMessage());   // 顶层 catch 必调 fail,不 rethrow
    }
}
```

- **`@Async` 必须靠自注入代理触发**：`@Autowired @Lazy private XxxService self;` + `(self == null ? this : self).runXxx(...)`（`this.runXxx` 不走代理，`@Async` 失效；`self==null` 兼容单测直 `new`）——与 `ClarifyService`/`DeepResearchService`/`CarSyncJobService`/`NewsSyncJobService` 同范式。
- **异步体顶层 catch 必落状态**：无调用方接收异常，失败只能靠 `fail*` 回写 + 日志；**严禁 rethrow**，否则状态卡 `GENERATING_*` 直到 10min `stuckGenerating` 自愈（自愈只是兜底，不是设计路径）。
- **异步体先重取实体**：status/brief/styles 在同步 claim 与异步执行之间可能已变，复用同步阶段快照会基于陈旧数据。
- **响应契约**：`HTTP 200 + R.ok(占位标记)`，**不引入 HTTP 202**（与 `/deep/clarify` 先例一致）；前端不 await 结果，靠 `store.startPolling`（项目状态翻转）刷新。
- **`@Async` 入参应为值快照**：传 id / 已构造好的不可变列表，不传后续会被修改的可变实体引用（异步线程读到的引用状态不可控）。
- 先例：`ImitationService.analyze`、`VersionService.generate`、`DeepWriterService.startBatch`（09-27-gen-async）。

---

## API Error Responses

| code | 语义 | 场景 |
|---|---|---|
| 0 | 成功 | — |
| 400 | 参数/前置不满足 | `@Valid` DTO 校验、业务校验（如 IMITATION 缺原文） |
| 401 | 未登录 | http.js 统一跳 /login |
| 403 | 权限不足 | `@PreAuthorize`（viewer 调写接口） |
| 404 | 资源不存在 | selectById 为 null |
| 409 | 状态冲突 | 生成中重触发、状态机下游已触发拒绝回退 |
| 410 | 接口已封死 | FAST 生成入口（`R.fail(410, "生成流程已升级为深度模式...")`） |
| 500 | 服务端异常 | AI 失败、意外异常 |

---

## Common Mistakes

### Common Mistake: 封死接口误封新模式的合法调用

**Symptom**: 新模式（如仿写 IMITATION）复用历史接口（`POST /generate/versions`），但控制器层仍无条件返回 `R.fail(410)`，新模式链路完全堵死（AC 全部不可达）。

**Cause**: 接口封死是针对旧模式（FAST 主题创作）的收敛，而新模式例外放行的逻辑只写到 service 层，控制器忘同步。

**Fix**: 控制器按模式分支——旧模式维持封死语义，新模式放行并补全错误映射（IllegalArgumentException→400 / IllegalStateException→409 / NotReadyException→409 / 其他→500）。

**Prevention**: 改「封死/放行」类接口时，spec 中声明的每个例外路径都要在控制器找到对应分支；spec-check 时核对契约表逐行。

### Common Mistake: 状态翻转轮询漏刷新产物

**Symptom**: 生成完成后（状态 READY）前端仍显示引导语。

**Cause**: `startPolling` 状态翻转只刷旧数据源（brief/versions），新模式新增的数据（如 imitation 分析）未纳入翻转回调。

**Fix**: 翻转回调按模式补拉：`if (after === 'READY') { ensureBrief(...); 仿写时 ensureImitation(...) }`。

**Prevention**: 新增 store 数据域时，同步检查 `ensure*` 三处触发：组件 onMounted/watch 兜底、`startPolling` 状态翻转、动作成功后的 force 重取。

### Common Mistake: 轮询可删除的占位行时按「最新行」查询

**Symptom**: 异步任务（如 clarify 研究计划）失败后占位行被删除，前端轮询 `/deep/status` 却拿到**更早的旧行**（旧 READY/CLARIFYING），误判为「已完成/进行中」，永远检测不到失败（AC「失败可见可重试」不可达）。

**Cause**: `/deep/status?briefId` 缺省时按 `project_id + gen_mode=DEEP ORDER BY id DESC LIMIT 1` 取最新行；占位行一旦删除，最新行回退到历史行。

**Fix**: 轮询必须携带**本次启动返回的 briefId** 精确定位（`/deep/status?briefId=<本次占位id>`）；后端按 id 查不到时返回 `stage=NONE`，前端据此回引导态。

**Prevention**: 任何「落占位 → 异步生成 → 失败删占位」的链路，前端轮询一律带占位 id；后端 status 对「按 id 查无」返回 NONE 而非回退最新。

### Common Mistake: 异步逐 agent 回写未加锁 → 丢失更新

**Symptom**: 并行子代理「完成即回写」后，前端轮询看到某 agent 的 `DONE` 又变回 `RUNNING`/`PENDING`，或 `factsJson`/`webCount`/`search` 相互覆盖（多 agent 结果串味）。

**Cause**: `updateAgent` 是「读整段 JSON → 改指定 agentId → 写回」的非原子读改写。改造为每个 agent 独立收集器后，多个收集器线程并发调用同一 brief 的 `updateAgent`，后写者基于**过期快照**覆盖先写者的结果（经典 lost update）。

**Fix**: 对每个 briefId 加锁（`ConcurrentHashMap<Long,Object>` + `synchronized(lock)`），串行化同一 brief 的读改写；不同 brief 互不阻塞。启动阶段「批量置 RUNNING」整体覆写也走同一把锁。批次结束（`runAsync` finally）`notesLocks.remove(briefId)` 清理，避免 map 无界增长。

**Prevention**: 任何「读整段 → 改局部 → 写回」的异步/并发更新（JSON 列表、聚合字段），默认按业务 id 加锁或改原子更新；新增并发回写链路时单测必须断言「各 agent 终值互不覆盖」（先例 `DeepResearchServiceProgressTest.并发回写不丢字段`）。

### Common Mistake: 异步生成成功后未清空 last_*_error

**Symptom**: 失败后重试成功，页面仍显示红色「上次生成失败」横幅。

**Cause**: 成功分支只写了产物与状态，未清空 `project.last_brief_error`（BriefService 成功分支会清空）。

**Fix**: 成功分支 `fresh = projectMapper.selectById(...)` 重取 + 判 null，清空 `lastBriefError`（截断/失败回退同理见 `docs/spec/brief-generation.md` 与 `docs/spec/overview.md` 状态机）。

**Prevention**: 新增异步生成链路时，成功分支对齐 BriefService：写产物 + 推进状态 + 清 last_*_error 三件事齐全。

### Common Mistake: 新生成链路漏推状态机与漏填展示字段

**Symptom**: 新增的生成链路（如深度单版 `/deep/generate`）落库成功后，前端仍卡上一步：步骤导航锁定、无「下一步」按钮；版本卡片字段渲染 `undefined·undefined`、字数统计空白。

**Cause**: 生成链路只写了产物表，没对齐既有链路（VersionService.generate）的完整语义：① 不推进项目状态机（停在 READY，`maxReachableStepOf` 锁死下游步骤）；② 不设 `current_version_id`；③ 漏填展示字段（version_label/style_tag/word_count/title）。FAST 封死、新模式成唯一主路径后，历史「补充链路」的缺陷必现。

**Fix**: 双保险——① 生成成功分支对齐既有链路语义（状态白名单推进 READY/DRAFT→VERSIONS_READY + `currentVersionId==null` 才设默认当前，追加不覆盖用户已选）；② 展示字段全部落库（title=正文首 H1 回退 topic / label 按版本数续编 / styleTag 传风格名回退兜底 / word_count=length）；③ 前端模板层对 null 字段兜底（`v.styleTag || '深度'`）+ 幂等回填存量 NULL 行（**含同根因的项目状态自愈**；该回填已固化为 Flyway 基线 `V1__baseline.sql`）。

**Prevention**: 新增任何「落库产物」的链路时，对照既有主链路逐字段核对：状态机推进点、current 指向、展示字段清单；「只写产物不改状态」的旧先例不是放行理由——一旦旧路径被封死（模式收敛），新路径就是主路径，缺陷即必现。