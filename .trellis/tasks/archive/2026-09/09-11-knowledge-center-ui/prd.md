# C3 知识中心浏览页（车型/新闻）

> 父任务：`09-11-knowledge-base-data-foundation`。依赖：车型 Tab 可先行；新闻 Tab 依赖 C2。

## Goal

新增统一「知识中心」入口 `/knowledge`，以 Tab 组织「车型」「新闻」两类知识浏览；保留现有 `/car`、`/kb` 路由兼容。问答不是 Tab。

## Requirements

- **R1 路由**：新增 `/knowledge`；现有 `/car`、`/car/:id`、`/kb` 保留可用。
- **R2 Tab**：仅「车型」「新闻」两个 Tab。
- **R3 车型 Tab**：复用 `carApi`（列表/详情/同步入口）。
- **R4 新闻 Tab**：用 C2 的 `GET /news`、`GET /news/{id}`；列表分页、详情正文展示。
- **R5 API 封装**：新增 `newsApi`；顺手补 `kbApi`（现有 `KbLibrary.vue` 直调 `http`，规范为 api 封装）。
- **R6 响应式**：移动端优先，触控目标 ≥44px，沿用 Element Plus。

## Acceptance Criteria

- [ ] AC1：`/knowledge` 可访问，含「车型」「新闻」两个 Tab。
- [ ] AC2：车型 Tab 列表/详情可用。
- [ ] AC3：新闻 Tab 列表分页/详情正文可用（依赖 C2）。
- [ ] AC4：`/car`、`/kb` 路由仍可用。
- [ ] AC5：`npm run build` 通过。

## Out of Scope

- 问答 UI（C4）。
- 新闻/车型的编辑管理（同步走 C1/C2）。

## Notes

- 与父任务 `design.md` §5 接口契约衔接。
