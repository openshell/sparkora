# prd.md — 信源 metadata 补全（URL + 权威档透传）

> 父任务：`10-05-self-hosted-sources`（需求集与设计论证持有者）。
> 来源：`10-05-source-web-fusion`（F）独立复核发现的 2 个跨任务接线缺口。
> 现状依据：`src/main/java/com/sparkora/car/service/CarRagService.java`、`source/service/SourceDocService.java`、`news/service/NewsDocService.java`、`deep/tool/KnowledgeSearchTool.java`、`deep/service/FactSheetService.java`。

## Goal

补全本地自建信源事实在检索链路中的**两个缺失字段**，使 F 已实现但当前"空转"的两条能力**端到端真正生效**：

1. **本地命中 URL 透传**：让 `Citation` 携带内容原文 URL，使 F-R3「跨源同 URL 去重」与 F-R2「本地优先同 claim 裁决」在真实链路可触发（当前 `SearchHit.source(...)` 恒 `url=null`，去重永不命中）。
2. **权威档 `authorityTier` 透传**：把信源注册表的权威档写入 NEWS 向量 metadata 并贯通到 `Citation`，使 `DEEP_SOURCE_AUTHORITY_ENABLED=true` 时官方 0.9 / 行业 0.7 / 自媒体 0.5 分档真正取到数据（当前恒落保守档 0.7）。

用户价值：没有它，「自建信源 + 外部搜索」的融合质量提升（去重、权威分级）只是账面上的；补齐后 F 的设计意图才真正落地。

## 依赖（显式声明）

- **前置**：`10-05-source-domain-retrieval`（E，`Citation` 已带 `sourceType`/`category`）+ `10-05-source-web-fusion`（F，`FactSheetService` 已读 `source.url`/`authorityTier`/`crossCounted`）。
- **后继**：无（10-05 树收尾）。

## Background / Confirmed Facts（仓库证据）

- `CarRagService.Citation{source, modelName, chunkType, score, chunkText, docId, sourceType, category}`（`:98`）——**无 `url`、无 `authorityTier`**；`UnifiedHit`（`:71`）同。
- `toUnified`（`:534-545`）从 store metadata 读 `domain/modelId/name/refId/chunkType/sourceType/category`；**未读 `url`/`authorityTier`**。
- `SourceDocService.sourceMeta`（`:152-159`）写 `sourceType/category/publishDate`；**未写 `url`/`authorityTier`**。
- `NewsDocService`（BYD 路径）写 `sourceType=byd-news/category=官方新闻`；**未写 `url`/`authorityTier`**。
- `NewsDocEntity` 已有非持久化 `publishDate/sourceType/category`；**无 `url`/`authorityTier`**。
- `NewsEntity.url`（`:25`）存在（官方相对路径），`SourceCollectService` 落库时写入。
- `SourceEntity.authorityTier`（`:30`，`official|industry|media|ugc`）**存在但从未被检索/事实链路读取**。
- `KnowledgeSearchTool`（F）：`SearchHit.source(title, modelName, docId, snippet, score, sourceType, null, crossCounted(...))`——**`authorityTier` 恒 null**，`url` 由工厂恒 null。
- `FactSheetService`（F）：已读 `s.path("url")`（`:212,369`）、`s.path("authorityTier")`（`:227,258`）、`s.path("crossCounted")`（`:363`）——**消费侧已就绪，只差生产侧供给**。
- `SourceCatalog.crossCounted(sourceType)` 已按 sourceType 推导；权威档目前**无**对应推导。

## Requirements

- **M-R1 URL 透传**：NEWS 域向量 metadata 新增 `url` 键；`NewsDocEntity` 增非持久化 `url`；`SourceDocService`/`NewsDocService` 写入；`toUnified` 读入 `UnifiedHit.url`；`Citation` 增 `url`（兼容构造器保持旧调用）；`KnowledgeSearchTool` 把 `c.url()` 传给 `SearchHit.source(...)`（工厂增 `url` 承载）。
- **M-R2 权威档透传**：NEWS 域向量 metadata 新增 `authorityTier` 键；写入侧从 `SourceEntity.authorityTier` 取（BYD 官方固定 `official`；通用信源按注册表值，缺省不写）；`toUnified` 读入；`Citation` 增 `authorityTier`；`KnowledgeSearchTool` 传给 `SearchHit.source(...)`。
- **M-R3 存量回填迁移**：新增 Flyway `V16__news_source_metadata_url_tier.sql`——幂等（`metadata->'key' IS NULL` 守卫）补 NEWS 行 `url`（`news_doc → news.url` 派生）与 `authorityTier=byd-news` 行固定 `official`；**不改 `id`/`embedding`（零重嵌）**；不动 V1–V15。
- **M-R4 零回归**：不开分档（`DEEP_SOURCE_AUTHORITY_ENABLED=false`）时融合行为不变；未采集自建信源时无 SOURCE 命中；`mvn test` 全绿 + `npm run build` 通过。
- **M-R5 降级不阻断**：URL/档缺失（老数据/未回填）→ 字段可空，`FactSheetService` 已有兜底（缺档 0.7、无 url 跳过去重），不抛异常。
- **M-R6 契约与文档**：`docs/spec/brief-generation.md`/`retrieval.md` 与 `.trellis/spec/backend/ai-rag-guidelines.md` 把 F 的"已知接线缺口"改为"已补齐"；`Citation` 字段级契约同步。

## Acceptance Criteria

- [x] **AC-M1 URL 端到端**：采集一条含 URL 的信源内容（或构造 input）后，其 `Citation.url` 非空并出现在 `SearchHit(SOURCE).url`；构造「本地 SOURCE url = 外部 WEB url」时 `FactSheetService.sourceCount==1`、`dedupedSameUrl>=1`（复用 F 的去重逻辑，输入改为真实链路字段）。→ `FactSheetServiceTest.AC_M1_生产链路SOURCE_url触发跨type同URL去重`（`toUnified`(:565)→锚点重建(:341)→cites(:476)→`KnowledgeSearchTool`(:56-58)→`SearchHit.source(...,url)`→`rawFallback`→`dedupUrlKey`，断言 sourceCount==1/dedupedSameUrl>=1）。
- [x] **AC-M2 权威档端到端**：`SourceEntity.authorityTier=official` 的源，其命中 `Citation.authorityTier=official`；`DEEP_SOURCE_AUTHORITY_ENABLED=true` 时该 fact 置信 0.9（industry 0.7 / ugc 0.5 同理）；缺档 0.7。→ `FactSheetServiceTest.AC_M2_生产链路authorityTier触发分档`（写入 `NewsDocService:149` BYD 固定 official / `SourceDocService.sourceMeta:166` 取注册表值；`toUnified:566` 读入；断言 confidence==0.9）。
- [x] **AC-M3 迁移幂等**：V16 在真实库 `BEGIN…ROLLBACK` 或临时 schema 复现：首跑仅补缺键、二次跑 0 行变更、`id`/`embedding` 不动；编号 V16 紧随 V15，V1–V15 未改。→ check 用会话级 TEMP 表+`BEGIN…ROLLBACK`（未写生产）复现：修复后首跑 `UPDATE 2/2/1`、二次 `0/0/0`、`id`/`embedding` 逐行不变；V16 唯一新迁移、V1–V15 未改。**check 修复 P0**：第 3 条 UPDATE 原缺 `refId` 关联致交叉连接写错权威档，已补 `(v.metadata->>'refId')=d.id::text`。
- [x] **AC-M4 零回归**：不启用分档、无自建信源时 `fact_sheet` 行为与 F 收口时逐位等价；`mvn test` 全绿 + `npm run build` 通过。→ `mvn test` 1076/0/0/0（基线 1062+14）、`npm run build` ✓ 40.35s；默认 `DEEP_SOURCE_AUTHORITY_ENABLED=false`、旧构造器零改动。
- [x] **AC-M5 降级**：URL/档为空的旧数据走 `FactSheetService` 既有兜底（不去重、保守档 0.7），不报错。→ `absoluteUrl`/`absoluteBydUrl` 无基址返回原值、`sourceMeta`/BYD 空值不写键；`normalizeUrl` 对相对链返回 null → 跳过去重，不抛异常。

## Out of Scope

- 改 `domain` 名 / 全量重嵌（仍 `domain=NEWS`，V16 只补 metadata）。
- 采集器抓取 URL 的真实性校验（属 B/采集侧）。
- F 的融合算法本身（去重/优先/分档规则已实现，本任务只供数据）。
- 视频/图片相关 metadata。

## Notes

- `FactSheetService` 消费侧在 F 已就绪（读 `url`/`authorityTier`/`crossCounted`），本任务**只补生产侧**（写 metadata + 透传），风险低。
- 迁移编号：V16（本任务）；既有最大 V15。
- `url` 建议存原文绝对 URL；若仅有相对路径（`NewsEntity.url` 为官方相对路径），写库时按 `detail_base_url` 补全为绝对，供跨源 URL 去重（`normalizeUrl`）正确比对。
