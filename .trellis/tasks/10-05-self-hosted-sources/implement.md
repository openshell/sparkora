# implement.md — 自建汽车资讯信源与外部搜索融合基座（执行计划）

> 父任务**不是实现目标**；本文件定义子任务执行顺序、集成评审门、验证命令与回滚点。

## 0. 执行原则

- 父任务只做：契约仲裁（design.md §2）、跨子任务集成评审、集成验收（AC-P*）。
- 每个子任务**独立实现、独立验收、独立归档**；父任务在全部子任务完成后做一次集成评审。
- 任何共享契约变更（信源注册表字段、向量域 metadata、事实来源类型）必须**先改父 `design.md` §2**，再改代码。

## 1. 依赖排序（推荐执行序）

```
阶段 1（可并行，无依赖）
  ├─ 10-05-crawl4ai-transport      （C：抓取通道）
  └─ 10-05-source-crawl-base       （B：采集基座，未接 C 时仅 HTTP）
阶段 2
  └─ 10-05-source-domain-retrieval （E：NEWS 域内来源细化 + 入库检索）依赖 B
阶段 3（可并行）
  ├─ 10-05-source-center-ui        （U：知识中心）依赖 B + E
  └─ 10-05-source-web-fusion       （F：融合）依赖 E + 10-04 A
阶段 4
  └─ 父任务集成评审（AC-P1..P9）
```

> `10-04-brief-retrieval-sources` 与本事并行推进；F 至少等其 A（Serper 契约）落地后再实现。

> **时序风险（评审新增）**：AC-P1 要求「≥1 个 B 级源（乘联会）」，其采集强依赖 C（Crawl4AI）。若 C 延后，则 AC-P1 无法验收。建议 C 与 B 同批或先于 B 完成；无法满足时，AC-P1 的 B 级部分顺延到 C 完成后补验，其余 A 级源部分先行验收。
> **任务复杂度（评审修正）**：B/E/F/U 均为复杂任务，`task.py start` 前须各有 `design.md` + `implement.md`（B/E/F/U 已补齐，U 因其 PRD 明写「交互在 design 中定」+ 涉及 KnowledgeCenter 多面板改造，不属轻量）；C 相对轻量，PRD-only 可接受。
> **依赖修正**：C（Crawl4AI）拥有 `FetchTransport` 接口，B 显式依赖 C 的接口——消除父图 `C→B` 与 B PRD 旧「无前置」的矛盾。

## 2. 每个子任务的交付检查

- [ ] `prd.md` 有可测 AC，且无阻塞开放问题
- [ ] 复杂任务补 `design.md` + `implement.md`
- [ ] `implement.jsonl` / `check.jsonl` 有真实 spec/research 条目（非 seed）
- [ ] `mvn -q -DskipTests compile` / `mvn test`（后端）或 `npm run build`（前端）通过
- [ ] 涉及检索/新闻的改动做了对拍或回归证据
- [ ] 文档与 `.env.example` 同步

## 3. 集成评审门（父任务收口，AC-P1..P7）

1. **AC-P1 端到端**：≥3 个 A 级源 + ≥1 个 B 级源（乘联会）可采集入库，内容进入 `fact_sheet`。
   - 验证：真机跑一次采集 → 建项目 → `/deep/run` → 检查 `fact_sheet` 含本地信源条目。
2. **AC-P2 发布节奏**：多源 cron 不互相阻塞、同源防重。
   - 验证：并发触发多源采集，查任务表与日志。
3. **AC-P3 资源红线**：并发 ≤2、同站 ≤2/天生效。
   - 验证：构造并发抓取探针，观察被限流而非击穿。
4. **AC-P4 域隔离不回归**：CAR/KB 候选不被信源块挤占，且 NEWS 域内 BYD 新闻不被采集源挤占。
   - 验证：复现 `news.md` §5 口径对比 CAR 候选数；构造 BYD 新闻 + 采集源并发命中，验证二级隔离。
5. **AC-P5 融合可观测**：本地/外部占比、同 URL 去重、置信分层可见；同源多通道不误判交叉。
6. **AC-P6 零回归**：未启用信源时 `mvn test` 全绿 + `npm run build` 通过，BYD 新闻与深度研究等价。
7. **AC-P7 文档契约**：spec 与 `.env.example` 同步，URL 键 `_BASE_URL` 结尾。
8. **AC-P8 结构化内容**：表格数值切块后不丢、可命中。
9. **AC-P9 SOURCE 事实**：`SOURCE` 类型不被拒、不误当 KB，权威分档生效。

## 4. 关键验证命令

```bash
# 后端
mvn -q -DskipTests compile
mvn test
# 前端
npm run build            # 在 frontend/ 下
# 联调
./dev.sh restart backend
./dev.sh logs backend -f
# 抓取探针（示例，具体端点以 .env 为准）
curl -s --max-time 20 "$CRAWL4AI_BASE_URL" ...
```

## 5. 回滚点

| 变更 | 回滚方式 |
|---|---|
| Flyway 泛化迁移 | `git revert` 迁移脚本 + 由 doc 重建向量（回滚前备份） |
| 采集基座 | 停用全部信源（`enabled=false`），保留表 |
| Crawl4AI 通道 | 清空 `CRAWL4AI_BASE_URL`，B 级源自动降级 |
| 融合规则 | 关闭融合开关，回退现有 `FactSheetService` 行为 |
| 知识中心 UI | 前端回退，不影响后端 |

## 6. 待用户批准

- 本父任务与 5 个子任务当前状态为 `planning`。
- **未获明确批准前不得 `task.py start` 或改产品代码。**
- 用户已确认的决策：D1 混合形态、D2 泛化 NEWS 为通用信源域、D3 数据型站点优先、D4 父+5 子拆分、D5 Crawl4AI 独立成子任务。
