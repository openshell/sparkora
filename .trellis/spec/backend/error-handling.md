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

### 控制器错误映射（惯例，参照 DeepController / ArticleProjectController）

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

### 状态机生成类服务（BriefService / ImitationService 同构）

1. 前置检查：`IllegalArgumentException`（项目不存在/模式不符/缺素材）。
2. 并发防护：生成中未过期（`updated_at` 距今 < 10 分钟 `STALE_GENERATING_MS`）抛 `IllegalStateException`；陈旧自愈放行。
3. 原子抢占：`UpdateWrapper` 条件更新（WHERE 状态白名单 + 陈旧分支限定生成中状态），`claimed == 0` 抛 `IllegalStateException`（409）。
4. AI 调用失败：**状态回退 DRAFT + 写 last_*_error（截断 1000 字符）**，再抛异常给控制器映射 500/200+fail。
5. 失败回退时用 `fresh = mapper.selectById(projectId)` 重取再改（防覆盖生成期间其他字段变更）；**fresh 需判 null**（项目被并发删除时不得 NPE 掩盖原始异常）。

> **Warning**: 失败回退若直接复用方法开头的 `p` 对象，可能把生成期间被其他请求更新的字段覆盖回去。必须重查。

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

### Common Mistake: 异步生成成功后未清空 last_*_error

**Symptom**: 失败后重试成功，页面仍显示红色「上次生成失败」横幅。

**Cause**: 成功分支只写了产物与状态，未清空 `project.last_brief_error`（BriefService 成功分支会清空）。

**Fix**: 成功分支 `fresh = projectMapper.selectById(...)` 重取 + 判 null，清空 `lastBriefError`（截断/失败回退同理见 `docs/spec/brief-generation.md` 与 `docs/spec/overview.md` 状态机）。

**Prevention**: 新增异步生成链路时，成功分支对齐 BriefService：写产物 + 推进状态 + 清 last_*_error 三件事齐全。

### Common Mistake: 新生成链路漏推状态机与漏填展示字段

**Symptom**: 新增的生成链路（如深度单版 `/deep/generate`）落库成功后，前端仍卡上一步：步骤导航锁定、无「下一步」按钮；版本卡片字段渲染 `undefined·undefined`、字数统计空白。

**Cause**: 生成链路只写了产物表，没对齐既有链路（VersionService.generate）的完整语义：① 不推进项目状态机（停在 READY，`maxReachableStepOf` 锁死下游步骤）；② 不设 `current_version_id`；③ 漏填展示字段（version_label/style_tag/word_count/title）。FAST 封死、新模式成唯一主路径后，历史「补充链路」的缺陷必现。

**Fix**: 双保险——① 生成成功分支对齐既有链路语义（状态白名单推进 READY/DRAFT→VERSIONS_READY + `currentVersionId==null` 才设默认当前，追加不覆盖用户已选）；② 展示字段全部落库（title=正文首 H1 回退 topic / label 按版本数续编 / styleTag 传风格名回退兜底 / word_count=length）；③ 前端模板层对 null 字段兜底（`v.styleTag || '深度'`）+ schema.sql 幂等回填存量 NULL 行（**含同根因的项目状态自愈**）。

**Prevention**: 新增任何「落库产物」的链路时，对照既有主链路逐字段核对：状态机推进点、current 指向、展示字段清单；「只写产物不改状态」的旧先例不是放行理由——一旦旧路径被封死（模式收敛），新路径就是主路径，缺陷即必现。