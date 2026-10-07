# prd.md — 知识中心信源管理重构

> 父任务：`10-05-self-hosted-sources`（需求集与设计论证持有者）
> 依赖：[`10-05-source-crawl-base`](../10-05-source-crawl-base/prd.md)、[`10-05-source-domain-retrieval`](../10-05-source-domain-retrieval/prd.md)。
> 现状依据：`docs/spec/knowledge/center.md`（现为「车型/新闻」两 Tab 的聚合浏览）。

## Goal

在知识中心新增**信源管理**与**采集任务监控**能力（信源注册/启停/手动采集/进度/失败重试），并把采集内容统一浏览（现有 BYD 新闻并入「信源内容」视图），使自建信源可运营、可观测。

用户价值：没有 UI，信源启停/采集失败只能改库或看日志；采集是长期运营动作，必须可视化。

## 依赖（显式声明，不依赖任务树位置隐含）

- **前置**：`10-05-source-crawl-base`（信源注册/任务 API）+ `10-05-source-domain-retrieval`（内容查询契约）。
- **后继**：无。

## Requirements

- **U-R1 信源管理 Tab**：知识中心新增「信源」Tab（或独立 `/sources` 页面）：信源列表（名称/类型/垂直/排期/启停/最近采集状态/栏目数）、新建/编辑/启停、手动触发采集。**多栏目（channel）支持**：源详情/编辑内可查看与维护其**栏目列表**（栏目名/`list_url`/`category`/选择器/启停），可对单个栏目单独手动触发（对应 B 的 `channels[]` 契约与 `/collect?channelId=`）。
- **U-R2 采集任务监控**：信源采集任务列表与详情（进度 `total/success/failed`、`failed_items`、起止时间、错误）；失败项重试；进行中 2s 轮询（照现有新闻同步轮询范式）。
- **U-R3 采集内容浏览（并入，非并列）**：统一「信源内容」列表（分页 + 关键词 + 来源/category 筛选）+ 详情；现有 BYD 新闻作为 `source=byd`、`category=官方新闻` 的一个来源**并入信源内容视图**。**「信源」Tab 取代「新闻」Tab**（领域模型已统一为信源域，UI 须镜像领域而非历史实现）。**BYD 新闻的专属能力必须保留**：同步按钮、切块数、官方原文链接、同步轮询——`SourceContentPanel` 对 `source=byd` 的条目**条件保留**这些能力，不做一刀切通用列表。
- **U-R4 API 分层约定**：页面一律经 `src/api/index.js` 具名导出（`sourceApi`/`sourceJobApi`），禁止 `.vue` 直调 `http`（照 `center.md` §2）。
- **U-R5 权限**：读三角色；写/触发采集 ADMIN/EDITOR；按钮按角色显隐（照现有知识中心范式）。
- **U-R6 导航（现状修正）**：知识中心 Tab **不是路由**（`router/index.js` 只有 `/knowledge` 单入口，无 `/kb`、`/news` 路由——原 PRD 表述有误）。U 只在 `KnowledgeCenter.vue` 的 `el-tabs` 内调整：**移除「新闻」Tab、新增「信源」Tab**；AppShell 左 rail 与 `/knowledge`、`/car`、`/car/:id` 路由不动。
- **U-R7 零回归**：`npm run build` 通过；既有**车型 / 知识库 / 问答**三 Tab 行为不变；**原「新闻」Tab 的能力在「信源」Tab 内保留**（BYD 来源的同步/切块/原文链接/轮询）。

## Acceptance Criteria

- [ ] **AC-U1 管理可用**：可新建/编辑/启停信源、手动触发采集并看到任务进度与失败明细，失败可重试。
- [ ] **AC-U2 内容可浏览且并入**：信源内容列表可分页/筛选/查看详情；**BYD 新闻在信源内容视图中可访问**，且其专属能力（同步按钮/切块数/官方原文链接/同步轮询）对 `source=byd` 条目仍可用；「新闻」Tab 已移除，「信源」Tab 承载原能力。
- [ ] **AC-U3 权限正确**：viewer 只读、写接口 403；按钮按角色显隐。
- [ ] **AC-U4 API 分层**：所有调用经 `src/api/index.js`；`npm run build` 通过。
- [ ] **AC-U5 零回归**：车型/知识库/问答既有 Tab 不回归；`/knowledge`、`/car`、`/car/:id` 路由不变；BYD 新闻能力未丢失。

## Out of Scope

- 采集/解析/调度后端（→ 前置任务）。
- 信源内容的人工编辑/审核。
- 采集内容的**视频**浏览；采集图片的独立浏览（正文配图已由 B/E 转入既有**图库**，经既有图库 UI/配图链路消费，不在「信源内容」页另做图片区）。
- 移动端适配（PC-only 桌面工作台）。

## Notes

- 「信源管理」是新能力，不复用「知识中心仅聚合浏览、不做编辑」的旧定位——这是对该限制的**有意突破**，需同步更新 `docs/spec/knowledge/center.md`。
- **决策（用户 2026-10-05）**：「信源」Tab **替换**「新闻」Tab，不做并列。依据：B/E 后存储层已统一为「信源域 + source 列」，BYD 新闻降为 `source=byd` 的一个类目，UI 须镜像统一后的领域模型；两 Tab 读同一张表会造成重复实现与认知不一致。代价（新闻面板专属能力的迁移）在 design 中处理。
