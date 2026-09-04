# 现状调研:统一检索与外部搜索(deep-research 前置,2026-09-04)

## 一、检索层现状(A 阶段改造对象)

### 双源隔离现状(S7 后)

- 车型域:`CarDocEmbeddingMapper.searchTopK(modelId, …)` — **检索被 model_id 过滤**;调用前提:项目关联车型(`BriefService`/`VersionService` → `listModelIds(projectId)` → `retrieveForGeneration(modelIds, …)`)。
- 通用域:`KbChunkEmbeddingMapper.searchTopK(vec, …)` 全库,但 `retrieveForGeneration` 中**独立配额合并**(kbPassed 单独注入),不是同空间排序。
- S7 短路逻辑:`!hasModels && !isRagKbEnabled() → EMPTY`。
- 文章 18 事故实锤:未关联车型 + KB 块最高分 0.316 < rejectScore 0.5 → LOW_CONFIDENCE 全抛弃;而车型 55/56(海狮08EV/DM-i)在库内有 48 块含价格/尺寸,因门禁未被检索——**数据可达性被用户手动关联卡死**。

### 统一检索设计要点(A 阶段)

1. `searchTopKUnified(queryVec, limit)`:一次 SQL 同时查 `car_doc_embedding`(JOIN car_doc, deleted=0)与 `kb_chunk_embedding`(JOIN kb_doc, enabled+deleted=0),UNION ALL 后按余弦分排序取 topK;返回行加 `source` 列(CAR/KB)+ `chunkType` + `modelId`(可空)。
2. 块类型配额沿用:CAR 的 PARAM_GROUP/MODEL_INFO 优先、RIGHTS 上限 1/3;KB_CHUNK 维持独立配额(AI_RAG_KB_TOPK)。
3. **写作锚点**:项目关联车型 id 列表传入检索层,命中锚点车型的块 + 加权(如 ×1.15)或保底入选;不再作为检索门禁。
4. `retrieveForGeneration` 重构签名:`retrieveForGeneration(query, topK, anchorModelIds)`,anchor 可空。
5. 状态判定与来源标注语义不变(OK/LOW_CONFIDENCE/FAILED/NO_KNOWLEDGE + 【通用知识】前缀 → 改为行内 source 标注)。
6. 回归验证:项目 18 主题(未关联车型)生成简报应命中海狮08 价格块,ragStatus=OK。

### 前端关联车型 UI

- 项目编辑页车型选择保留,文案改为「写作锚点(可选):关联后相关车型数据优先引用」;不强制。

## 二、外部搜索现状(B 阶段工具层)

### SEARXNG(本机,已配 SEARXNG_BASE_URL)

- 实测(2026-09-04):服务存活 `/search?q=test&format=json` 返回 HTTP 200,**但全部上游引擎不可用**:brave「Suspended: too many requests」、duckduckgo「timeout/CAPTCHA」、startpage「Suspended: CAPTCHA」——中文/英文真实查询 results 均 0。
- 结论:当前 SEARXNG 实例**引擎配置不可用**(公共引擎被封/超时),需修 engines(启用 bing/googleduckduckgo 备用源或加代理)后才有真实产出;**不可作为唯一外部搜索源**。

### Tavily(用户提供密钥,待写入 .env)

- `TAVILY_API_KEY`(用户将配置);REST API `POST https://api.tavily.com/search`,body {api_key, query, max_results, search_depth, include_domains…},返回 results[] {title, url, content, score}。
- 实现为 `TavilyClient`(RestClient,超时/重试与 BydCmsClient 同款);免费层额度有限,**仅在深度模式研究子代理中按需调用**(每次研究计划 1~3 次),不做高频轮询。
- 密钥只放 .env,`.env.example` 加 `TAVILY_API_KEY=` 占位与 `SEARCH_WEB_ENABLED` 开关。

### 工具抽象(B 阶段,研究子代理用)

```
SearchTool 接口(或 record SearchHit(title, url, snippet, source)):
  - KnowledgeSearchTool → 统一检索(A 阶段产出)
  - SearxngSearchTool   → 本机 SEARXNG(引擎可用性降级容忍:results 空→标记该源不可用)
  - TavilySearchTool    → Tavily API
子代理按研究计划标注的工具集调用;每个来源条目必须带 {source: KB|WEB+url} 进研究笔记 → 事实手册条目带出处。
```

- 外部内容可信度:WEB 来源条目置信度默认低于 KB(数值类 WEB 条目需 ≥2 源交叉才进手册高置信层,否则进「待核实」+ factRisks)。
- 配置:`SEARCH_WEB_ENABLED`(总开关)、`TAVILY_API_KEY`、`SEARXNG_BASE_URL`(已有);全部走 .env。

## 三、B 阶段生成链路现状(将重构)

- 现状:同步两步(BriefService.generate → VersionService.generate),RAG 一次共用,S6.1 四态+coveredText,S6.2 分层配额+子查询。
- 项目状态机:TOPIC_READY→GENERATING_BRIEF→BRIEF_READY→…(spec §5);**深度模式需新增状态**:CLARIFYING(等用户填表单)→ RESEARCHING(子代理进行中)→ …;或作为 brief 前置扩展字段(设计阶段定)。
- 成本:深度模式 6~10 次 LLM 调用(理解 1 + 澄清生成 1 + 子代理 N(2~4) + 汇总 1 + 写作 1~2 + 校验 1);用虚拟线程并行,`AiClient.chatJson` 复用。
- 前端:StepBrief.vue 需加「澄清问题表单」交互 + 研究进度展示;StepVersions 保留快速模式入口。