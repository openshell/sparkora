---
name: sparkora-spec-check
description: 对照 docs/s0-spec.md（或指定的 S 阶段规格）逐条核对实现：路由/权限/字段/接口契约/状态机是否一致，输出偏差清单。当用户要求“按规格检查/验收/核对实现”时使用。
---

# Sparkora 规格核对

`docs/s0-spec.md` 是唯一权威规格：路由/权限结构（§1）、登录（§2）、工作台（§3）、状态机（§4）、字段级表与接口契约。实现偏差必须修代码或修规格，不允许静默分叉。

## 步骤

1. 读 `docs/s0-spec.md`，列出本轮相关条目（路由、`@PreAuthorize` 角色、字段、响应形状、状态值）。
2. 逐条对照代码：控制器在 `src/main/java/com/sparkora/web/controller/`，权限注解、实体字段在 `domain/entity/`、建表在 `src/main/resources/db/schema.sql`、前端路由在 `frontend/src/router/index.js`。
3. 输出核对表：每条 ✅ 一致 / ⚠️ 有偏差（给出 file:line 与建议：改代码还是改规格）。
4. 若实现与规格分叉且规格已过时，先在回复中说明，征得确认后更新 `docs/s0-spec.md` 对应表格。

## 要点

- 角色：ADMIN / EDITOR / VIEWER；viewer 只读，接口级授权用 `@PreAuthorize("hasAnyRole(...)\")`。
- 响应统一 `R<T>` = `{code, msg, data}`，`code=0` 成功；列表接口返回 `{rows[],total,page,size}`（`PageResult`）。
- 前端请求带 `Authorization: Bearer <token>`，401 统一跳 `/login`（`frontend/src/api/http.js`）。
- 新增字段要三处同步：`schema.sql` + entity + 规格文档字段表。
