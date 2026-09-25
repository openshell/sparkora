# 事实手册近似条目合并与交叉验证

## Goal

让事实手册能把**措辞不同但指向同一事实**的 claim 归并为一条，从而恢复多来源交叉验证：同一事实被至少 2 个独立来源佐证时获得交叉置信（0.85）而非被拆成多条单一 WEB 源（0.4 + 待核实）。目标是提升深度简报事实手册的可信度与可读性，减少「同一件事出现三条、且全部待核实」的噪音。

## Background

- 上游任务 `09-25-brief-web-search` 已落地 Tavily 优先的策略路由与来源治理；本轮是其后续质量修复，**不改变搜索链路与 provider 策略**。
- 事实手册由 `FactSheetService.merge` 汇总：按 `claim` **精确字符串匹配**分组合并，见 `src/main/java/com/sparkora/deep/service/FactSheetService.java:32-49`（`byClaim.computeIfAbsent(claim, …)`；类注释 `:13-16` 已写明「相似条目去重按 claim 精确匹配(实现从简;LLM 辅助归类后续迭代)」）。
- 真实运行（project 50 / brief 64，「如何看待比亚迪宣布建成第2000座高速闪充站」）证据：三条同义 claim 未合并——
  - 「比亚迪第2000座高速闪充站已正式落成」(stnn.cc)
  - 「比亚迪第2000座闪充高速站正式落成」(stnn.cc，**同 URL**，来自另一 agent)
  - 「比亚迪第 2000 座闪充高速站正式落成 - IT之家」(ithome)
  三者均为 type=WEB、provider=TAVILY，全部以 confidence 0.4 + warning「仅单一 WEB 源,待核实」入库。
- 交叉验证的判定依据是「同一 claim 分组内出现 ≥2 个不同来源」，见 `FactSheetService.java:87-95`（`list.size() >= 2 && !sameSource(list)` → `MULTI` 0.85）与 `sameSource`（按 source.url + source.modelName 粗判，`:112-122`）。因此措辞差异直接导致交叉验证失效。
- 置信规则权威出处：`docs/spec/brief-generation.md:87,97`；同 claim 冲突裁决（KB 胜出、WEB 降 `alternatives`）见 `:87` 与 `FactSheetService.java:57-86`。
- 事实手册消费方：`DeepWriterService.write`（手册条目进 prompt，数值唯一来源）与 `DeepWriterService.verifyNumbers`（以 `sheet.toString()` 为 haystack 做数值回查），见 `src/main/java/com/sparkora/deep/service/DeepWriterService.java:104-106,184-200`；前端 `FactSheetSummary.vue`（entries 计数/高置信/待核实）、`CitationList.vue`（entries 类型 KB/WEB/MULTI 映射引用，上限 24，`:43-63`）。
- 既有单测：`src/test/java/com/sparkora/deep/service/FactSheetServiceTest.java`（单 KB/单 WEB/同 claim 双源 KB 胜出/gaps 去重/数值回查）。
- 仓库已有可复用的**本地文本相似度**实现（无外部依赖）：`ImitationService.normalize`（去标点空白、小写、仅留中英文数字，`:270-280`）、`ngramScore`（5-gram 重合率，`:283-295`）、`longestCommonRun`（朴素 DP，`:298-318`）。`pom.xml` 无 commons-text / 中文分词 / Lucene 依赖。
- 上一轮已修复：`SubAgentRunner` 在 LLM 汇总降级（`FALLBACK`）时保留 `webCount`/`search.resultCount` 口径并区分 `LLM_FALLBACK`（commit 1fc81d5）；本轮不改该部分。

## Requirements

- R1 事实手册合并阶段必须把**近似 claim** 归并为同一条目，而不只按字符串精确相等分组；归并必须在 `FactSheetService.merge` 内完成，不改调用方签名与 `fact_sheet` JSON 主结构。
- R2 归并必须优先保证**正确性**：仅归并高置信同义的不同措辞，不得把语义不同的数值/结论误合并。归并判定必须满足「**数值签名一致**」（claim/value 中抽出的数值集合一致）这一硬前提；数值冲突（如 2000 座 vs 1500 座、不同功率/价格）**绝不合并**。
- R3 合并后的条目必须正确反映多来源交叉：来源集合包含 ≥2 个**不同来源**（不同 URL 或不同 provider）时，`crossCount` 反映去重后的来源数并标注 `MULTI` 0.85；同一 URL 的重复命中不得被计为交叉来源（沿用 `sameSource` 语义）。
- R4 合并后必须保留所有来源证据：条目的来源集合（URL/provider/type）不得丢失，供 `alternatives`、warnings、前端引用面板与人工核对使用；现有 KB 胜出冲突裁决行为（`FactSheetService.java:57-86`）必须保持不回归。
- R5 归并阈值/算法必须可测且确定（纯本地、无新增重依赖、不调用 LLM），对既有测试用例保持向后兼容。
- R6 事实手册 JSON 契约保持向后兼容：`entries[{key,claim,value,sources,crossCount,confidence}]` 主字段不变；如为保留来源集合而新增字段（如 `sourcesList`/`sourceCount`），必须为增量字段且前端旧逻辑不读也不报错。
- R7 数值回查（`verifyNumbers`）不得因合并而回归：合并条目的 `key/value/claim` 必须仍能被 haystack 覆盖；合并不得删除原有数值。
- R8 同步维护 `docs/spec/brief-generation.md`（claim 合并与置信规则）与 `.trellis/spec/backend/ai-rag-guidelines.md`，并与最终实现一致。
- R9 本轮不改变搜索策略路由、provider 顺序、`webCount`/`search` 观测口径；不引入 Crawl4AI、reranker、LLM 归类或跨 agent 的语义聚类服务。
- R10 **引用面板与事实手册必须展示 WEB 来源的实际 provider**（TAVILY/SEARXNG），使同一「WEB 搜索」条目可区分来源；provider 取自 `fact_sheet` 条目 `sources.provider`（缺失时回退旧文案，不报错）。背景：`CitationList.vue:55,83` 当前只渲染域名与固定「WEB 搜索」文案，`FactSheetSummary.vue:42-43` 同样，导致无法判断来源。

## Acceptance Criteria

- [ ] AC-01 对「比亚迪第2000座高速闪充站已正式落成」「比亚迪第2000座闪充高速站正式落成」(同 URL) 两条近义 claim，合并为一条。
- [ ] AC-02 对「…第2000座…正式落成」(stnn.cc) 与「…第 2000 座…正式落成 - IT之家」(ithome) 两条**不同 URL** 近义 claim，合并为一条并标注 `MULTI`、confidence 0.85、`crossCount`=2。
- [ ] AC-03 数值冲突的两个 claim（如「第2000座」vs「第1500座」）**不得**合并，保持两条。
- [ ] AC-04 语义不同但共享部分文字的 claim（如「起售价 200000」vs「续航 700km」）不得合并。
- [ ] AC-05 同一 URL 的重复命中即使措辞不同也**不**被计为交叉来源（不产生 0.85；保持单一来源语义）。
- [ ] AC-06 合并条目保留全部来源信息（至少每条来源的 URL/type 可取），KB+WEB 同 claim 时仍 KB 胜出、WEB 进 `alternatives` 且不标「待核实」。
- [ ] AC-07 既有 `FactSheetServiceTest` 全部用例保持通过（向后兼容）；`mvn test` 全绿。
- [ ] AC-08 `fact_sheet` JSON 主结构兼容：前端 `FactSheetSummary.vue`/`CitationList.vue` 不因新增字段报错，`entries` 计数与高/低置信统计仍正确。
- [ ] AC-09 数值回查（`verifyNumbers`）对既有用例不回归。
- [ ] AC-10 `mvn -q -DskipTests compile` 通过；如触及前端契约则 `npm run build` 通过。
- [ ] AC-11 `docs/spec/brief-generation.md` 与 `.trellis/spec/backend/ai-rag-guidelines.md` 记录合并规则（数值签名硬前提、误合并防护、`MULTI` 判定），与实现一致。
- [ ] AC-12 引用面板与事实手册对 WEB 条目展示实际 provider（TAVILY/SEARXNG）；provider 缺失时回退旧展示且不报错（对应 R10）；`npm run build` 通过。

## Out of Scope

- 不改变外部搜索 provider 策略/顺序、开关门控与 `WebSearchRouter`。
- 不实现 LLM 辅助语义归类、跨语言/跨 agent 主题聚类或外部向量相似度服务。
- 不改变 `verifyNumbers` 的数值抽取规则本身（仅保证不回归）。
- 前端范围限于**展示 provider**（R10/AC-12）；不新增其他交互/功能，不改变既有 JSON 契约。
- 不处理 #4（有命中却 0 事实抽取）与 #5（query 稀释）；它们属于 LLM 行为/query 构造的独立范围。

## Resolved Decisions

- 采用**纯本地启发式归并**（规范化 + n-gram/字符重合度 + 数值签名约束），参照 `ImitationService` 既有相似度思路，不新增依赖、不调 LLM。
- 归并策略＝**数值硬门槛 + 无数字高阈值**（用户已确认）：有数值的 claim 必须以数值签名一致为前提，无数字的定性 claim 仅在高相似度阈值下才合并；误合并防护优先于召回。
- 「数值签名一致」作为归并的**硬前提**，优先保证不误合并；相似度阈值仅在同数值前提下生效。
- 合并后来源集合保留并以去重来源数判定 `MULTI`；同 URL 不计交叉。
- 阈值与数值抽取规则在 `design.md` §3 定稿（有数值 0.45 / 无数值 0.70），实现阶段用真实样本标定并固化进测试。
