# Design — B 知识中心信息架构收敛

> 配套 `prd.md`。纯前端；后端 API / 角色模型零改动。

## 1. 目标壳（KnowledgeCenter.vue）

```
知识中心（<h2> + el-tabs, 懒挂载 loadedTabs 范式）
  ├─ 车型 tab   → knowledge/CarKnowledgePanel.vue（吸收 CarLibrary 管理动作）
  ├─ 知识库 tab → knowledge/KbLibraryPanel.vue（迁 KbLibrary.vue，保留抽屉）
  ├─ 新闻 tab   → knowledge/NewsKnowledgePanel.vue（保留）
  └─ 检索问答 tab → knowledge/QaChatPanel.vue（迁 QaChat.vue）
```

- 顶层不再自渲染完整 `.page-header` 之外的重复标题；`<h2>知识中心</h2>` 保留。
- 各 panel 不自带 `.page-header`；管理动作下沉为该 panel 的工具栏按钮（行内）。

## 2. 路由与导航

- `router/index.js`：
  - 移除 `/car`（`CarLibrary`）、`/kb`（`KbLibrary`）、`/qa`（`QaChat`）顶级路由。
  - 保留 `/car/sync`、`/car/:id`、`/knowledge`。
- `constants/nav.js`：移除「知识问答」「车型库」「知识库」三项；保留 项目/图库/风格库/知识中心/设置。
- **关键**：`知识中心` 增加 `matches: ['/car']`，使 `/car/sync`、`/car/:id` 仍映射到「知识中心」高亮
  —— 既满足 AppShell dev-only「auth 路由必须被导航覆盖」校验，又让车型详情/同步在语义上归属知识中心。
- 回链修复：`CarSync.vue` 的「返回车型库」、`CarDetail.vue` 的「返回」→ `/knowledge`（车型 tab）。

## 3. 组件迁移与删除

| 旧 | 新 | 处置 |
|---|---|---|
| `views/CarLibrary.vue` | `views/knowledge/CarKnowledgePanel.vue`（合并管理动作） | 删旧 |
| `views/KbLibrary.vue` | `views/knowledge/KbLibraryPanel.vue` | 删旧 |
| `views/QaChat.vue` | `views/knowledge/QaChatPanel.vue` | 删旧 |
| `views/knowledge/NewsKnowledgePanel.vue` | 不变 | 保留 |

- `CarKnowledgePanel` 现有只读网格 + 搜索/筛选；合并 CarLibrary 的：统计概览条、同步进度面板、
  「同步车型」（→ `/car/sync`）、「重建全部向量」（ADMIN）、单车型 详情/同步/删除。
- `KbLibraryPanel` = 现 `KbLibrary.vue` 整体搬入（保留新建/编辑抽屉、重建、删除；`kbApi.domains()`）。
- `QaChatPanel` = 现 `QaChat.vue` 整体搬入。

## 4. 布局（最大风险点）

- QaChat 是**满高主从工作台**（`.page.qa-page { flex:1; min-height:0 }`），期望填满 `.app-shell__body`。
- 嵌入 `el-tab-pane` 时，需保证从 `.app-shell__body` → `.el-tabs` → `.el-tabs__content` → 激活
  `.el-tab-pane` → panel 的**高度链**打通（否则 qa-layout 塌陷）。
- 方案：`.kc-tabs` 用 `:deep()` 让 `.el-tabs__content`/激活 `.el-tab-pane` 撑满；问答 panel
  用 `height:100%`。**车型/知识库/新闻 tab 仍可正常滚动**（内容型），仅问答 tab 走满高。
- 单页懒挂载沿用 `loadedTabs`（现 KnowledgeCenter 已有），避免无谓请求、保留状态。

## 5. 角色门控

- 进入知识中心 = `loggedIn`（nav `require: 'loggedIn'`）。
- 各 tab 内管理动作沿用组件内 `user.isEditorOrAbove` / `user.isAdmin` 判断：
  - 车型：同步/删除 = editor；重建全部 = admin；详情 = 所有人。
  - 知识库：新建/编辑/重建/删除 = editor；列表 = 所有人可读。
  - 问答：所有人。
- 后端 API 与角色模型不变。

## 6. 兼容 / 回退

- 纯前端；后端契约不变；旧路由移除（用户明确接受直接 404，不重定向）。
- 回退 = `git revert` 前端提交。

## 7. 验证

- `cd frontend && npm run build` 通过。
- AppShell dev-only 校验：`npm run dev` 控制台无「路由未被导航覆盖」告警。
- 手工冒烟：知识中心 4 tab 切换；车型同步/删除/重建；知识库 CRUD/重建；新闻；问答（含配图/引用）；
  `/car/:id` 详情、`/car/sync` 同步页可达且高亮「知识中心」；旧 `/car` `/kb` `/qa` = 404。
