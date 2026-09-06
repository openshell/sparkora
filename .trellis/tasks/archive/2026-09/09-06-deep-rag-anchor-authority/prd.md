# 深度研究知识库锚点检索与 KB 优先级裁决

## Goal

修复深度研究链路两处缺陷(项目 28「大唐EV 定价」实测暴露):

- **R1 检索选拔缺陷**:子代理 KB 检索走裸 `retrieveUnified(query, 8)`(全库无锚点 top-8 单查询),「大唐EV 售价块」(相似度 0.76)被权益/促销块挤出注入面,知识库该命中而不命中,WEB 论坛帖趁虚而入;
- **R2 汇总裁决缺陷**:`FactSheetService.merge` 平行聚合 KB 与 WEB 条目,同 claim 冲突时无裁决规则——KB(0.9)与单一 WEB(0.4)并存时 AI 写作仍可能引用论坛帖说法。

## 源需求与已确认决策(2026-09-05/06,用户批准:dec「两个都做」)

1. KB 先行且带锚点:子代理 KB 检索接 `retrieveForGeneration` 统一通道(锚点加权 + 参数级子查询 + 分层配额全复用);
2. 锚点来源:项目关联车型(clarify 锁定写作锚点车型后自动关联)+ 主题车型识别兜底;
3. WEB 只补 KB 缺口(gap 驱动),不得覆盖 KB 已回答部分;
4. 同 claim 冲突裁决:KB 胜出,WEB 条目降级为佐证/warning 留证据;
5. 不比分数(相似度 vs 置信度不可比),按来源类型定优先级。

## Requirements

### R1 锚点检索接入

- `SubAgentRunner.research` 的 KB 检索改为「项目锚点感知」:
  - `DeepResearchService` 解析锚点 modelIds(项目关联 `ArticleProjectCarService.listModelIds` 为主;为空时用 `CarModelMatcherService` 按主题识别兜底),随子代理调用下传;
  - `KnowledgeSearchTool` 增加 `search(query, maxResults, anchorModelIds)`,委托 `retrieveForGeneration(query, topK, anchors)`;
  - 子代理查询语料强化:KB 检索 query 用「主题 + 锚点车型 + 问题」复合语料(纯问题如「价格对比」缺车型上下文,相似度必散);
  - 保持 `SearchTool` 抽象兼容:原 `search(query, maxResults)` 保留(无锚点语义),新重载为深度链路专用。
- `research_notes` 落库结构不变(前端 ResearchProgress 无需改)。

### R2 merge 冲突裁决

- `FactSheetService.merge` 按 claim 聚合时,若同 claim 存在 KB 与 WEB 两类来源:
  - KB 条目胜出:confidence 按 KB 规则(0.9),`sources` 记 KB 来源;
  - WEB 条目不删:降级为该条目的 `alternatives` 字段(URL+域名),并写 warnings「WEB 有异说(url 域名),以知识库为准」;
  - 双方同源同类(都是 KB 或都是 WEB)维持现规则(多源交叉 0.85 / 单一 WEB 0.4)。
- 置信度规则不变:KB 0.9 > 多源交叉 0.85 > 单一 WEB 0.4 + 待核实 warning。

## 约束

- 不改项目状态机;不改 brief/version 表结构;`rag_citations` 语义延续(本地知识库);
- WEB 全关(`SEARCH_WEB_ENABLED=false`)时纯 KB 行为不回归;检索失败降级可见语义不变;
- spec 同步:§9 工具层契约补「KB 优先、锚点感知、冲突裁决」。

## Acceptance Criteria

- [ ] AC1: 项目 28(或重建同题项目)深度研究后,事实手册「大唐EV 售价区间」条目 `source.type=KB`(239,900-309,900 或 23.99-30.99 万写法),论坛帖 WEB 条目降级为 alternatives/warnings;
- [ ] AC2: `mvn -q -DskipTests compile` 通过;深度模式端到端冒烟(AI 服务可用时):clarify→run→fact_sheet 含 KB 条目;
- [ ] AC3: `SEARCH_WEB_ENABLED=false` 回归:纯 KB 研究行为不变;
- [ ] AC4: `docs/s0-spec.md` §9 工具层契约已更新。

## Notes

- 项目 28 复现证据:research_notes 显示 4 代理 KB 命中 0、WEB 3 条(汽车之家论坛帖);知识库 model_id=39 有 MODEL_INFO 价块(239,900-309,900)且单车型检索相似度 0.761 TOP1(实测)。
- 若 AI 服务不可用,AC1 以单测(FactSheetService 裁决逻辑)+ 数据证据(research_notes KB 命中>0)替代端到端验证。