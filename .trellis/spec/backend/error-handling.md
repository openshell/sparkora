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