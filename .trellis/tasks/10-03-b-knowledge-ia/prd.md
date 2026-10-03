# B 知识中心信息架构收敛

## Goal

消除「知识中心 vs 车型库/知识库/知识问答」入口重叠：除图库外所有知识类数据（车型/知识库/新闻）
归入知识中心，含浏览与管理。

## Depends On

无（可与 A/C 并行）。纯前端，后端 API 不变。

## Requirements

- 父 R3、D3。
- 知识中心 = 4 tab：车型 / 知识库 / 新闻 / 检索问答。
- 车型 tab 吸收 `CarLibrary` 管理动作；知识库 tab 承载 `KbLibrary`；问答 tab 承载 `QaChat`；
  新闻 tab 承载现有 `NewsKnowledgePanel`。
- 移除 `/car`、`/kb`、`/qa` 顶级路由（**不重定向**）；`/car/:id` 详情保留。
- `nav.js` 收敛并 router `meta.auth` 对齐（AppShell dev-only 校验通过）。
- 按 tab 角色门控（浏览 loggedIn；管理动作 editor/admin）。
- **后端 API/角色模型零改动**。

## Acceptance Criteria

- [ ] AC-B1 知识中心 4 tab 齐备，浏览 + 管理动作可用。
- [ ] AC-B2 `/car`、`/kb`、`/qa` 移除且无残留引用；`/car/:id` 可用。
- [ ] AC-B3 按 tab 角色门控正确；既有后端 API 契约不变。
- [ ] AC-B4 `nav.js` 与路由一一对应，AppShell 校验通过；`npm run build` 通过。

## Out of Scope

- 后端接口/角色模型变更；图库入口调整。
