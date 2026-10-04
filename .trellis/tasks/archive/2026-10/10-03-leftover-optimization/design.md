# Design — 遗留优化规划：rerank / 知识中心 UX / KB 运营

> 配套 `prd.md`。父任务记跨子技术设计与边界；子任务 A/B/C 各自另有 `design.md`。

## 1. 已决前提（prd D1–D4）

- D1 父 + 3 子（A/B/C）并行；D2 A=LLM 重排（ChatClient）；D3 B=知识中心 4 tab + 撤旧路由；
  D4 C=仅批量导入。

## 2. 目标与边界

```
知识中心（前端 IA, B）                         检索层（后端, A）
  车型 tab ─┐                                    VectorStore.searchDomains
  知识库 tab ├─ 浏览 + 管理（按 tab 门控）           │ 候选合并（域隔离窗口）
  新闻 tab ─┤            │                         ▼
  问答 tab ─┘            │                   [A] LLM rerank（新增，可开关/降级）
        │                │                         ▼
        └── 批量导入(C) ─┘                    锚点加权 → 分层配额 → 门槛/四态（不变）
```

- **A 只插入一个可选重排阶段**，不改变锚点/配额/门槛/四态语义（关闭时零回归）。
- **B 只改前端 IA 与路由**，后端 API 与角色模型不变（复用现有 controller）。
- **C 只新增导入入口与解析**，复用 E3 的 normalize/切块/嵌入链路。

### 2.1 组件映射

| 遗留 | 现状 | 目标 | 子任务 |
|---|---|---|---|
| rerank | 无 | `CarRagService` 候选后、配额前加 `RerankService`（ChatClient） | A |
| 知识中心 IA | 2 tab（车型/新闻）+ 并列 `/car` `/kb` `/qa` | 4 tab（车型/知识库/新闻/问答）；撤旧路由 | B |
| KB 运营 | 单条 CRUD | + 批量导入（CSV/JSON/Markdown） | C |

## 3. 关键契约与设计

### 3.1 A — LLM rerank（核心设计）

- **插入点**：`CarRagService.retrieveForGeneration` 中，候选 `merged`（主查询 + 子查询合并、去重）
  之后、锚点加权与配额**之前**。重排后沿用既有分数门槛与配额选择。
- **契约**：
  ```java
  // 新服务（独立可测）
  interface Reranker {
      List<UnifiedHit> rerank(String query, List<UnifiedHit> candidates, int keepTopN);
  }
  // 实现：LlmReranker（ChatClient + prompt 模板 prompts/rag/rerank-system.st）
  // 失败/超预算/关闭 → 原序返回（identity）
  ```
- **覆盖域**：CAR/KB/NEWS 统一重排；NEWS 块量大，只对其**域窗口 top-N** 重排后回填，避免 token 爆炸。
- **参数（`.env` → AiProperties）**：`AI_RAG_RERANK_ENABLED`（默认 false）、
  `AI_RAG_RERANK_TOPN`（默认 20）、`AI_RAG_RERANK_TIMEOUT_MS`（默认沿用 AI_TIMEOUT_MS 或更短）。
- **降级**：LLM 调用异常/超时/返回不可解析 → 原序返回 + warn；**绝不阻断生成**（四态判定基于原分数）。
- **预算**：重排输入截断（每候选 chunk_text 截断）+ 输出仅需候选序号重排，控制 token。
- **评估**：`research/rerank-ab.md` + 脚本，代表 query 前后 top-K 相关性对比。

### 3.2 B — 知识中心 IA（前端）

- 路由（`frontend/src/router/index.js`）：`/knowledge` 承载 4 tab（子路由或 query tab）；
  `/car/:id` 详情保留；移除 `/car`、`/kb`、`/qa` 顶级路由。
- 导航（`frontend/src/constants/nav.js`）：保留「知识中心」「图库」；移除「知识问答/车型库/知识库」独立项；
  `NAV_MODULES` 与 router `meta.auth` 一一对应（AppShell dev-only 校验）。
- 角色门控：知识中心进入=loggedIn；各 tab 内管理动作按 `user.isEditorOrAbove`/`isAdmin`
  （沿用现有组件内判断，如 `CarLibrary` 的按角色按钮）。
- 组件复用：`CarKnowledgePanel`/`NewsKnowledgePanel` 保留并吸收 `CarLibrary.vue` 的管理动作；
  新增知识库 tab 承载 `KbLibrary.vue`；问答 tab 承载 `QaChat.vue`。旧视图文件按复用情况迁移/删除。
- **后端零改动**：继续用 `carApi`/`newsApi`/`kbApi`/`qaApi`。

### 3.3 C — KB 批量导入（后端 + 前端）

- 入口：`POST /api/kb/docs/batch`（multipart file 或 JSON body），ADMIN/EDITOR。
- 支持格式：CSV（列 title/domain/content/source/tags/effectiveFrom/effectiveTo）、JSON（对象数组）、
  Markdown（含 frontmatter 或标题分段）。
- 逐条：复用 `KbDomain.normalize` + tags normalize + `KbDocService.create` 的切块+嵌入；
  返回 `[{index,title,success,error}]`；**单条失败不阻断其余**。
- 非法/重复：非法 domain → 该条失败并回报；重复（同 title+domain）→ 可按策略跳过或新建（默认跳过+回报）。
- 前端：`KbLibrary.vue` 增「批量导入」按钮 + 结果面板。

## 4. 兼容 / 回退

- A：默认关闭 → 不启用于生产即零回归；开启后失败降级回原序。回退 = 关开关 / revert。
- B：纯前端；后端契约不变；旧路由移除（用户明确接受 404）。回退 = revert 前端。
- C：纯新增端点；不影响既有单条 CRUD。回退 = revert。

## 5. 权衡

- **A LLM 重排的代价**：每次生成多一次 LLM 调用（时延/token）；用「默认关 + 只重排 top-N +
  可配超时 + 失败降级」控制。收益是相关性提升，需 A/B 证明。
- **B 4 tab 单页**：信息集中，但单页组件变大；用懒挂载（现有 `loadedTabs` 范式）控制。
- **C 批量导入格式选择**：CSV/JSON/MD 三种解析入口，统一走同一 normalize/落库；格式解析差异封装。

## 6. 运维 / 回退要点

- A 新增 `.env` 键需同步 `.env.example`；prompt 模板走 `resources/prompts/**`（C1 范式）。
- B 无后端迁移；C 无数据库迁移（复用现有表）。
- 每子任务独立提交可 `git revert`。
