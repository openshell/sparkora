# design.md — M: 信源 metadata 补全（URL + 权威档透传）

> 来源：`10-05-source-web-fusion` 复核发现的 2 个跨任务接线缺口。
> 父设计：`../10-05-self-hosted-sources/design.md` §2.4/§2.5.1/§2.6。
> 现状依据：`CarRagService.java`、`SourceDocService.java`、`NewsDocService.java`、`KnowledgeSearchTool.java`、`FactSheetService.java`。

## 1. 边界

| 层 | 文件 | 改动 |
|---|---|---|
| 数据模型 | `CarRagService.UnifiedHit` / `Citation` | 增可空 `url` / `authorityTier` + 兼容构造器 |
| 读路径 | `CarRagService.toUnified` | 读 metadata `url`/`authorityTier` |
| 写路径 | `NewsDocEntity`、`SourceDocService`、`NewsDocService` | 增非持久化 `url`/`authorityTier`；`sourceMeta`/BYD metadata 写入 |
| 取数 | `KnowledgeSearchTool` | `SearchHit.source(...)` 传 `c.url()`/`c.authorityTier()` |
| 工厂 | `SearchTool.SearchHit.source(...)` | 增 `url` 承载（当前恒 null） |
| 迁移 | `V16__news_source_metadata_url_tier.sql` | 幂等回填存量 NEWS metadata `url`/`authorityTier` |
| 文档 | `docs/spec/brief-generation.md` §4/§5、`retrieval.md` §3、`ai-rag-guidelines.md` | 缺口 → 已补齐 |

**不做**：改 domain 名/重嵌；融合算法本身；采集侧 URL 真伪。

## 2. 数据流（补齐后）

```
[写] SourceCollectService → sparkora_news(url) 
      SourceDocService: metadata{sourceType,category,publishDate,+url,+authorityTier}
      NewsDocService(BYD): metadata{sourceType=byd-news,category=官方新闻,+url,+authorityTier=official}
[读] toUnified: metadata → UnifiedHit{...,url?,authorityTier?}
      → 组 cites → Citation{...,sourceType?,category?,URL?,authorityTier?}
[取] KnowledgeSearchTool: SOURCE 命中 → SearchHit.source(title,modelName,docId,snippet,score,
                                         sourceType, authorityTier, crossCounted)   // 且 url 承载
[融] FactSheetService: distinctSources 按 url 跨 type 去重(已就绪) + 按 authorityTier 取置信(已就绪)
```

## 3. 关键决策

### 3.1 `url` 落 metadata 还是落 `sparkora_news_doc`？
**决策：只落 store metadata（不新增 doc 列）。** 理由：`url` 是检索/融合用信号，非块持久字段；`NewsDocEntity` 已有非持久化 `publishDate/sourceType/category` 先例，`url` 同构处理，零 DDL。`toUnified` 已从此 metadata 读取。

### 3.2 `authorityTier` 来源
- BYD 路径：固定 `official`（BYD 官方新闻，权威档明确）。
- 通用信源：从 `SourceEntity.authorityTier` 取（注册表权威档，`official|industry|media|ugc`）；缺省（null）**不写键**（检索层走 0.7 保守兜底，零回归）。
- 粒度：栏目级暂不细分（`sparkora_source_channel` 无 authority 列）；沿用源级（当前实现 `SourceCatalog` 亦按源+栏目确定性推导 sourceType，authority 同理按源级）。

### 3.3 相对/绝对 URL
`NewsEntity.url` 为官方相对路径。写 metadata 时若为相对，按该栏目 `detail_base_url` 补全（复用 B 已实现的基址解析思路）为绝对 URL；否则 `FactSheetService` 的 `normalizeUrl` 比对会与外部绝对 URL 失配。若无法补全（无基址），写原值并接受「可能不去重」（降级，不报错）。

### 3.4 兼容性
`UnifiedHit`/`Citation` 均为 record；按仓库范式**只增兼容构造器**（旧参数组合委托新构造器，新字段 null）。既有 `Citation` 6/7 参调用方与测试零改动。

## 4. 迁移 V16

- 仅 `UPDATE vector_store SET metadata = metadata || '{"url": ...}' WHERE metadata->>'domain'='NEWS' AND metadata->'url' IS NULL`（`url` 由 `refId→news_doc.news_id→news.url` 派生 JOIN）。
- `authorityTier`：`WHERE metadata->>'sourceType'='byd-news' AND metadata->'authorityTier' IS NULL` → 固定 `'official'`；通用信源由 `SourceEntity` JOIN 写（若无法纯 SQL 派生，仅回填 byd-news，通用源待下次重建 metadata）。
- 幂等：每条带 `IS NULL` 守卫；不动 `id`/`embedding`；V1–V15 不改。

## 5. 风险与回滚

| 风险 | 缓解 | 回滚 |
|---|---|---|
| 迁移误改 id/embedding | 只 `metadata || jsonb`，不碰 id/embedding | revert V16 |
| 相对 URL 拼错域 | 按 `detail_base_url` 解析（B 先例）；无基址写原值 | 关闭去重（url 为空即跳过） |
| 分档误判 | 默认 off；缺档 0.7 | `DEEP_SOURCE_AUTHORITY_ENABLED=false` |
| record 字段破坏旧调用 | 仅增兼容构造器 | revert |

## 6. 测试计划

- `CarRagServiceTest`：`toUnified` 读 `url`/`authorityTier`（构造 metadata Document）；`Citation` 兼容构造器新字段 null；贯通到 `Citation`。
- `SourceDocServiceTest`：`sourceMeta` 写 `url`/`authorityTier`（有则写、无则不写）。
- `NewsDocService`/`NewsDocTransactionTest`：BYD metadata 写 `authorityTier=official` + `url`。
- `KnowledgeSearchToolTest`：SOURCE 命中 `SearchHit.url`/`authorityTier` 非空（构造 `Citation`）。
- `FactSheetServiceTest`：AC-M1 用真实链路字段（SOURCE.url）触发跨 type 同 URL 去重 `sourceCount==1`；AC-M2 按 `authorityTier=official` 取 0.9。
- 迁移 AC-M3：临时 schema `BEGIN…ROLLBACK` 复现 V16 幂等（参考 V13/V14 check 先例）。
