# implement.md — U: 知识中心信源管理重构

> 依赖：`10-05-source-crawl-base`（API）+ `10-05-source-domain-retrieval`（内容查询契约）。
> 共享契约以父 `../10-05-self-hosted-sources/design.md` 与本文 `design.md` 为准。

## 实施顺序

1. **依赖核对**：确认 B 的 `/api/sources*`、`/api/source-jobs*` 与 E 的内容查询接口契约稳定。
2. **API 层**：`src/api/index.js` 新增 `sourceApi` / `sourceJobApi` 具名导出（禁止 `.vue` 直调 `http`）。
3. **面板**：`SourceManagePanel.vue`（列表/编辑抽屉/启停/手动采集）→ `SourceJobPanel.vue`（任务+失败明细+2s 轮询+重试）
   → `SourceContentPanel.vue`（分页/筛选/详情，表格保留行列；`source=byd` 条件保留同步/切块数/官方原文/轮询，见 design §2.1）。
4. **接入 Tab（替换）**：`KnowledgeCenter.vue` **移除「新闻」Tab、新增「信源」Tab**（`loadedTabs` 去 `news` 加 `sources` 懒加载）；
   车型/知识库/问答 Tab 不动。迁移 `NewsKnowledgePanel` 的 BYD 能力到 `SourceContentPanel`（可先复用子结构）。
5. **权限**：写按钮按 `store/user.js` 角色显隐。
6. **文档**：`docs/spec/knowledge/center.md` 补信源 Tab 契约。
7. **验证**：`npm run build` + 手工联调。

## 实现期第一件核对事项

- **[核对] 三 Tab 零回归**：确认车型/知识库/问答三面板代码与懒加载未被改动；「新闻」Tab 移除后其 BYD 能力已在「信源」Tab 内可用（同步/切块/原文/轮询）。
- **[核对] 分层**：`grep` 确认无 `.vue` 直调 `http`。
- **[核对] 轮询清理**：任务面板 `onUnmounted` 清 `setInterval`。

## 验证命令

```bash
cd frontend && npm run build
```

## 测试/联调清单

- 新建/编辑/启停信源 → 列表状态正确。
- 手动采集 → 任务区出现 RUNNING 并轮询 → 终态 SUCCESS/PARTIAL/FAILED。
- 失败项重试可用。
- 内容浏览：分页/关键词/category 筛选；**BYD 新闻在信源内容中可访问**，且同步/切块数/官方原文/轮询可用。
- 「新闻」Tab 已移除，「信源」Tab 在车型/知识库/问答之间正常切换。
- viewer 只读（写按钮隐藏）；写接口 403。
- `npm run build` 通过；车型/知识库/问答 Tab 与 `/knowledge`、`/car`、`/car/:id` 不回归。

## 风险检查点（实现期）

| 检查点 | 何时 | 判定 |
|---|---|---|
| 三 Tab 不回归 | 接入后 | 车型/知识库/问答正常/懒加载正常 |
| BYD 能力不丢 | 替换后 | 同步/切块/原文/轮询可用 |
| 无直调 `http` | review | grep 零命中 |
| 轮询无泄漏 | 卸载面板 | 定时器清除 |
| 表格展示不丢结构 | 详情抽屉 | 行列保留 |

## 回滚点

删除新面板 + 移除 Tab 项即回滚；既有 Tab/路由未动。

## 交付物

- 三个新面板、`sourceApi`/`sourceJobApi`、KnowledgeCenter Tab 接入、center.md 同步、`npm run build` 通过。
- 不包含：后端采集/入库（B/E）；新闻并入信源（后续）。
