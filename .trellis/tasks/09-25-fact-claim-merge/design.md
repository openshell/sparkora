# 设计：事实手册近似条目合并与交叉验证

## 1. 范围与边界

- 只改事实手册汇总层 `com.sparkora.deep.service.FactSheetService.merge` 及其新增纯本地工具；不改搜索路由、provider 策略、`SubAgentRunner`（除上游已完成的降级口径）、`DeepWriterService.verifyNumbers` 规则、前端组件。
- 输入不变：`merge(notesJson)` 的入参格式与调用点（`DeepResearchService.doRunAsync` `:251-255`）不变。
- 输出契约向后兼容：`fact_sheet` JSON 的 `entries[{key,claim,value,sources,crossCount,confidence}]`、`gaps`、`warnings` 主结构不变；仅**增量**新增来源集合字段。

## 2. 架构与数据流

```text
research_notes[] (各 agent factsJson)
  → 展开为 fact 列表 {claim, value, source{type,sourceId,provider,url,modelName,docId}, confidence}
  → ClaimSimilarity 聚类(替代 byClaim 精确匹配)
        ├─ 规范文本(normalize)
        ├─ 数值签名(有数字必须一一相等;一侧有数字一侧没有 → 不合并)
        └─ 相似度(字符 n-gram 重合 + 最长公共连续片段)
  → 每个簇:来源去重(沿用 sameSource 语义,按 url+modelName)
        ├─ 含 KB 且含 WEB → KB 胜出, WEB → alternatives, 警告「以知识库为准」(既有行为)
        ├─ 去重来源数 ≥2 → MULTI / 0.85
        ├─ 纯 KB → 0.9
        └─ 单一 WEB → 0.4 + 「仅单一 WEB 源,待核实」
  → entries(+ 增量 sourcesList/sourceCount) / gaps / warnings
```

## 3. 新增组件：`ClaimSimilarity`（`com.sparkora.deep.service`）

纯静态、无状态、无新增依赖、不调 LLM。

### 3.1 规范化 `normalize(String)`

参照 `ImitationService.normalize`（`:270-280`）思路但独立实现（该实现为跨包不可见的包级静态方法；不复用以免改动仿写语义）：去 Markdown/HTML、去标点空白、小写，仅保留中英文与数字。claim 短，规范化后直接做字符 n-gram。

### 3.2 数值签名 `numberValues(String... texts)`

- 抽取数值 token：`\d+(?:[.,]\d+)*\s*(?:万|亿)?`（覆盖「2000」「239,900」「20万」「1500kW」中的数）。
- 归一：去逗号/空白；`万`×10000、`亿`×1e8；用 `BigDecimal` 比较，规避 `200000` 与 `20万` 的格式差异。
- 返回**去重后的数值集合**（字符串化，稳定排序）。

### 3.3 相似度 `similarity(String a, String b)`

- 字符 n-gram（n=3）重合：`|A∩B| / max(|A|,|B|)`（短句用 Jaccard 风格，避免长度差放大）。
- 最长公共连续片段长度 / `min(len)` 作辅助，取 `max(ngram, runRatio)` 或加权；最终取单一分数。
- 空串或规范化后过短（<4 字）→ 0。

### 3.4 判定 `sameClaim(c1, v1, c2, v2)`

硬前提（不满足直接 false）：

1. 两 claim 规范化后均非空。
2. **数值签名一致**：两侧集合相等。一侧有数值、另一侧没有 → 视为不一致（不合并）。
   - 数值冲突（{2000} vs {1500}）→ 不一致，绝不合并。

阈值（满足硬前提后）：

- 有数值：`similarity ≥ 0.45`（数值已锁定同一事实，阈值可低）。
- 无数值：`similarity ≥ 0.70`（仅措辞级差异才合并，防定性事实误并）。

> 常量集中定义（如 `TH_NUMERIC=0.45` / `TH_TEXT=0.70`），实现阶段用真实样本标定，允许微调但需在测试中固定期望。

## 4. `FactSheetService.merge` 改造

### 4.1 聚类

- 保持 `byClaim` 展开为有序 fact 列表，改为**贪心簇**：按出现顺序遍历 fact，若与某簇**代表 fact**（簇内第一条）满足 `sameClaim` 则归入，否则新开簇。
- 复杂度 O(n²)，n 为单 brief fact 条数（实践中数十条内），可接受。
- 代表 fact 决定条目 `key/claim/value`（保持「首条为准」，与既有精确匹配行为一致）。

### 4.2 来源聚合与类型判定

- 来源去重键沿用 `sameSource`（`url + modelName`）；`distinctSources` = 去重后的来源集合。
- 类型/置信优先级**不变**：KB+WEB → KB 胜出 + `alternatives` + 警告；否则去重来源数 ≥2 → `MULTI` 0.85；纯 KB → 0.9；单一 WEB → 0.4 + 待核实。
- `crossCount` 由「原始 fact 条数」改为**去重来源数**（语义更准；AC-03 要求）。

### 4.3 增量字段

- 新增 `sourcesList`：`[{type,sourceId,provider,url,modelName,docId}]`（保留全部来源证据，R4）。
- 新增 `sourceCount`：去重来源数（= `crossCount`，显式别名便于前端/审计）。
- 均为增量字段，前端旧逻辑不读、不报错。

### 4.5 前端 provider 展示（R10/AC-12）

- `CitationList.vue`：WEB 条目的 `modelName` 改为「provider · 域名」（如 `Tavily · stnn.cc`；provider 缺失则退化为现域名展示）；`sourceLabel` 保持「WEB 搜索」，或对 WEB 追加 provider 后缀。仅读 `fact_sheet.entries[].sources.provider`。
- `FactSheetSummary.vue`：`srcLabel`（`:42-43`）对 WEB 追加 provider（`WEB · TAVILY` 形式），provider 缺失回退现值。
- 兼容：`sources.provider` 为 09-25 增量字段，历史 fact_sheet 可能缺省 → 前端必须容错（空则走旧文案）。
- 不改任何请求/响应结构，纯展示层。

### 4.4 兼容与回归防护

- `verifyNumbers` 以 `sheet.toString()` 为 haystack：合并仅保留代表 `value`，但数值签名一致保证被合并 claim 的数值已存在于代表 claim/value 中（数值集合相等），haystack 仍覆盖，故不回归。格式化差异（`200000` vs `20万`）由数值归一化 + 回查正则的宽松匹配兜底。
- KB+WEB 冲突裁决路径与既有测试 `同claim两源_KB胜出_R2冲突裁决` 必须保持通过。
- `gaps`/`warnings` 去重逻辑保留。

## 5. 取舍

| 决策 | 选择 | 理由 |
|---|---|---|
| 归并位置 | `FactSheetService` 内聚类 | 唯一消费点，避免新增跨层服务与调度 |
| 算法 | 本地字符 n-gram + 数值签名 | 无依赖、可测、确定；中文短 claim 下 n-gram 足够 |
| 误合并防护 | 数值签名硬门槛优先 | 数值冲突（座数/功率/价格）是本域最危险误并，必须阻断 |
| 无数字 claim | 高阈值 0.70 | 召回让位于正确性（用户已确认） |
| 复用 ImitationService | 不复用其包级方法 | 跨包不可见且语义不同（长文 vs 短 claim）；独立实现避免回归仿写 |
| 来源字段 | 增量新增 | 保住旧契约，同时满足 R4 证据保留 |

## 6. 回滚

- 纯 Java 变更 + JSON 增量字段，**无数据库迁移**。
- 回滚点：`git revert` 本轮 commit；`FactSheetService` 与新增 `ClaimSimilarity` 及测试独立，前端/DB 无需回滚（旧的 `entries` 字段仍存在，旧数据可继续读）。
- 已生成的历史 `fact_sheet` 不追溯重算（与既有「设置/规则变更不追溯已生成产物」一致）。

## 7. 风险

- 阈值过高导致应合并未合并（收益不足）→ 用 AC-01/02 真实样本 + 测试覆盖标定。
- 阈值过低导致误合并 → 数值签名硬门槛 + 无数字高阈值 + AC-03/04 反向用例。
- `crossCount` 语义变化 → 文档同步（`brief-generation.md:96-97`）；前端不依赖其数值。
- 数值签名归一是否引入异常 → `BigDecimal` 解析失败时回退为原字符串 token 比较，不抛异常。
