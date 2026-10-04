# Implement — 遗留优化规划：rerank / 知识中心 UX / KB 运营

> 父任务执行计划（父任务持有任务映射与跨子验收；实现由子任务 A/B/C 承担）。

## 0. 顺序与总门

- A/B/C 相互独立，可并行。无硬依赖。
- 父任务集成门：三子任务各自验收达标后做跨项回归 + 契约一致性复核。

## 1. A — rerank 重排层

- [ ] `Reranker` 接口 + `LlmReranker`（ChatClient）；prompt 模板 `prompts/rag/rerank-system.st`。
- [ ] `CarRagService` 在候选合并后、锚点加权/配额前调用（可开关）。
- [ ] `AiProperties` 新增 `ragRerankEnabled/TopN/timeoutMs`；`application.yml` + `.env.example` 同步。
- [ ] 失败/超时/超预算 → 原序返回 + warn；不阻断生成。
- [ ] `research/rerank-ab.md` 评估（代表 query 前后对比）。
- 验证：`mvn test`；关闭态逐条零回归；开启态 A/B 报告。
- 回退点：R-A = 关开关 / revert。

## 2. B — 知识中心信息架构收敛

- [ ] 知识中心改造为 4 tab（车型/知识库/新闻/检索问答）；吸收管理动作。
- [ ] 路由：移除 `/car`、`/kb`、`/qa`；`/car/:id` 保留；`/knowledge` 承载 tab。
- [ ] `nav.js` 收敛并与 router `meta.auth` 对齐（AppShell 校验通过）。
- [ ] 按 tab 角色门控（浏览 vs 管理）。
- 验证：`cd frontend && npm run build`；手动走查各 tab 与旧路由 404；后端 API 未改。
- 回退点：R-B。

## 3. C — KB 批量导入

- [ ] `POST /api/kb/docs/batch`（CSV/JSON/Markdown 解析）；ADMIN/EDITOR。
- [ ] 复用 E3 normalize/切块/嵌入；逐条 `{index,title,success,error}`；单条失败不阻断。
- [ ] 非法/重复处置策略（默认重复跳过 + 回报）。
- [ ] 前端 `KbLibrary.vue` 批量导入按钮 + 结果面板。
- 验证：`mvn test`；`npm run build`；批量样例端到端。
- 回退点：R-C。

## 4. 父任务集成（三子达标后）

- [ ] 跨项回归：知识中心内管理动作不破坏检索；rerank 开启不改变四态/契约。
- [ ] 契约一致性：后端 API/`RagResult`/`Citation`/角色模型不变。
- 验证：`mvn test` 全绿 + 前端 build + 一键联调冒烟。

## 5. 全局验证命令

```bash
mvn -q -DskipTests compile
mvn test
./dev.sh restart backend
cd frontend && npm run build
```

## 6. 风险清单 / 回退点

| 点 | 风险 | 回退 |
|---|---|---|
| R-A | rerank 增时延/token 或降低效果 | 关开关 / revert |
| R-B | 撤旧路由致外链 404（用户已接受） | revert 前端 |
| R-C | 批量导入解析/落库不一致 | revert 端点 |

## 7. task start 前检查

- [ ] `prd.md`/`design.md`/`implement.md` 完成且一致。
- [ ] `implement.jsonl`/`check.jsonl` 含真实 spec/research 条目。
- [ ] 子任务 A/B/C 已建并链接父任务，各自 `prd.md` 写明依赖（无）。
- [ ] 用户对最终规划摘要给出明确批准。
