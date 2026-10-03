# Implement — B 知识中心信息架构收敛

> 纯前端。执行顺序、验证与回退点。

## 0. 前置

- 工作区干净，基于最新 main（A 已归档）。
- 目标：知识中心 4 tab；撤 `/car` `/kb` `/qa`；后端零改动。

## 1. 组件迁移

- [ ] `CarLibrary.vue` → `knowledge/CarKnowledgePanel.vue`：
      - 合并 CarLibrary 的统计概览条、同步进度面板、工具栏（搜索/网络/状态）、卡片管理动作
        （「同步车型」→`/car/sync`、「重建全部向量」ADMIN、详情/同步/删除）、`onRebuildAll`/轮询逻辑。
      - 保留现有 panel 的只读网格/筛选。
      - 去掉自身 `.page-header` 与 `.container`（由知识中心壳承载标题）。
- [ ] `KbLibrary.vue` → `knowledge/KbLibraryPanel.vue`（整体搬迁，保留抽屉/重建/删除；去 `.container`）。
- [ ] `QaChat.vue` → `knowledge/QaChatPanel.vue`（整体搬迁；去 `.page` 外层或改为 `height:100%` 适配）。

## 2. 知识中心壳

- [ ] `KnowledgeCenter.vue` 改 4 tab：车型 / 知识库 / 新闻 / 检索问答；懒挂载 `loadedTabs` 扩展为 4 键。
- [ ] 高度链：`.kc-tabs` 用 `:deep()` 打通 `.el-tabs__content` / 激活 `.el-tab-pane` 撑满；
      问答 panel `height:100%`；内容型 tab 正常滚动。

## 3. 路由与导航

- [ ] `router/index.js`：删 `/car`(`car`)、`/kb`(`kb`)、`/qa`(`qa`) 三条；保留 `/car/sync`、`/car/:id`、`/knowledge`。
- [ ] `constants/nav.js`：删「知识问答」「车型库」「知识库」；`知识中心` 加 `matches: ['/car']`。
- [ ] `CarSync.vue` 返回按钮 → `/knowledge`；`CarDetail.vue` 返回 → `/knowledge`。

## 4. 删除旧视图

- [ ] 删除 `views/CarLibrary.vue`、`views/KbLibrary.vue`、`views/QaChat.vue`（确认无残留 import）。

## 5. 验证

```bash
cd frontend && npm run build
```
- [ ] build 通过；无残留引用（`grep -rn "views/CarLibrary\|views/KbLibrary\|views/QaChat" frontend/src` 为空）。
- [ ] `npm run dev` 控制台无「路由未被导航覆盖」告警（`/car/:id`、`/car/sync` 被知识中心覆盖）。
- [ ] 冒烟：4 tab 切换、各管理动作、`/car/:id`、`/car/sync`、旧路由 404。

## 6. 回退

- R-B = B 的前端提交；`git revert` 即恢复旧 IA（旧视图文件随提交还原）。
