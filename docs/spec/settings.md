# 系统检索设置（知识库/外部搜索开关）

> 回链：[系统说明总览](../README.md)

职责：页面控制生成链路的资料检索来源——内部知识库与外部搜索两个独立开关。09-09-brief-gen-redesign（2026-09-09）。

---

## 1. 语义

- 页面控制生成链路的资料检索来源——内部知识库与外部搜索两个独立开关，**运行时读取**（`SettingService` 单行表 + 内存缓存，写后刷缓存），**非 `.env` 部署级配置**。
- 设置变更仅影响**之后**的生成，已生成产物不追溯。

---

## 2. 存储

`schema.sql` 幂等单行表（固定 `id=1`，首次读取自动初始化默认行）：

| 列 | 类型/默认 | 语义 |
|---|---|---|
| `kb_enabled` | `BOOLEAN NOT NULL DEFAULT FALSE` | 内部知识库（CAR 车型域 + KB 通用域）启用；**默认停用**（知识库数据质量治理中，停用期间优先外部搜索资料） |
| `web_search_enabled` | `BOOLEAN NOT NULL DEFAULT TRUE` | 外部搜索（SEARXNG→Tavily 降级链）启用 |
| `updated_by` / `updated_at` / `deleted` | `BIGINT` / `TIMESTAMP NOT NULL DEFAULT now()` / `SMALLINT NOT NULL DEFAULT 0` | 审计（手工赋值）/逻辑删除惯例 |

- 实体 `com.sparkora.domain.entity.SettingEntity`（`@TableName("sparkora_setting")`）、`SettingService`。
- 说明：`deleted` 用 `SMALLINT`（全局配置惯例），与业务表的 `BOOLEAN` 逻辑删除不同。

---

## 3. API 契约

全部 `R<T>`；路径前缀 `/api`。

| 接口 | 方法 | 角色 | 请求/响应 |
|---|---|---|---|
| `/settings` | GET | ADMIN, EDITOR | `data: {id, kbEnabled, webSearchEnabled, updatedBy, updatedAt, deleted}`（首次访问自动插默认行） |
| `/settings` | PUT | **仅 ADMIN** | `@Valid {kbEnabled?, webSearchEnabled?}`（null 不改）；响应同 GET（写后刷缓存） |

---

## 4. 生效点（深度链路，快速模式已下线）

| 开关 | 生效行为 |
|---|---|
| `kbEnabled=false` | `DeepResearchService.applySettingGates` 剔除 KB 工具（子代理不装配本地检索）；产物 `rag_status=DISABLED`；锚点车型仅保留写作偏好语义，不触发本地检索 |
| `webSearchEnabled=false` | 剔除 WEB 工具（SEARXNG/Tavily 不调用） |
| 双关 | 子代理无资料工具，LLM prompt 注入「未检索任何外部资料,不得编造,数据未核实」；生成继续不阻断（沿用不硬阻断决策），`factRisks`/`gaps` 标注 |
| 两者全开 | 维持 S9 现状：KB 优先（R2 冲突裁决 KB>WEB），WEB 单源 0.4 进 warnings |

- `DISABLED` 检索状态语义见 [retrieval.md](retrieval.md)。
- 深度链路工具装配与 `toolHealth` 门控见 [brief-generation.md](brief-generation.md)。

---

## 5. 前端

- `/settings` 路由（TopBar「设置」，EDITOR 及以上可见）；双 `el-switch` + 说明文案 + 双关警示；写入口仅 ADMIN（`user.isAdmin` 隐藏保存按钮，后端 `@PreAuthorize` 兜底）。
- 前端文件：`views/SettingsView.vue`、`layouts/TopBar.vue`、`api/index.js`（`settingApi`）。

---

## 6. 与 `.env` 的关系

- `AI_RAG_KB_ENABLED`（部署级）仅在本地统一检索通道内继续生效（`kbEnabled=true` 时）；深度链路的工具装配以设置页为准。
- `SEARCH_WEB_ENABLED`（`sparkora.deep.search-web-enabled`）同理仅作 SEARXNG/Tavily 的部署级可用性控制。
- 浏览/问答**不受** `kb_enabled` 控制（见 [knowledge/qa.md](knowledge/qa.md)）。

---

## 7. 已知限制

- 设置变更不追溯已生成产物。
- 单行表固定 `id=1`，不支持多租户/多 workspace 差异化设置。
