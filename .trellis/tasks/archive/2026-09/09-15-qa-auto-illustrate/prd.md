# 知识问答自动配图（子D）

## Goal

在知识问答（`QaChat`）中让答案配图：① 答案引用了新闻知识时，展示对应新闻的图片；② 支持「给我看销量海报」这类直接要图的问法（语义检索出图）。

## Dependencies

- **便宜路径（R1-R3）**：仅依赖 `09-15-img-classify`（子A）——用 `source_ref` 从命中的新闻 chunk 关联出该新闻的图库封面。
- **语义路径（R4）**：依赖 `09-15-img-semantic-search`（子B）——查图片向量表。
- 两条路径**独立可分阶段交付**：便宜路径可在子B 之前先做，语义路径待子B 完成后补。
- 若子A 未完成，便宜路径无 `source_ref` 可用，**不得提前开始**。

## Background（09-15 勘察 + 09-17 复核）

- `QaMessageEntity`：`role`/`content`/`citations`（JSON `Citation[]`）/`ragStatus`，**无图片字段**。
- 问答检索跨三域（CAR/KB/NEWS），引用条目 `Citation` 含 `source`（CAR/KB/NEWS）、`modelName`、`chunkType`、`score`、`chunkText`。
- **待查项结论（09-17 复核）**：`Citation` **不携带**新闻 id，但链路**可直接打通且成本极低**：
  - `searchTopKUnified` 的 SQL **已 SELECT `docId`**（NEWS 段 = `sparkora_news_doc.id`），但 `retrieveUnified` 读行时丢掉了它（`CarRagService.java:366-373`）。
  - 只需给 `UnifiedHit`/`Citation` 各加一个可空 `docId`，即可定位：`news_doc.id → news_id(内部) → sparkora_news.id → cover_image_id → 图库资产`。
  - 真机验证链路可用：`detail588 → news_doc.id=1688 → news.id=48 → cover_image_id=84`。
- 子A 已完成：新闻图带 `source_ref`，`sparkora_news.cover_image_id` 已回填（157 条有封面）。
- 子B 已完成：`ImageEmbeddingService.searchImages(query, topK, minScore, tags)` 可用；图库支持标签 AND 筛选与语义检索。


## Requirements

### R1 新闻 chunk → 配图（便宜路径，核心）

- 问答答案的引用中若有 NEWS 来源，经该 chunk 的 `docId`（`sparkora_news_doc.id`）→ 内部 `news_id` → `sparkora_news.cover_image_id` → 图库资产，关联出图片。
- 答案消息随带图片列表：`[{imageId, url, thumbUrl, title, newsId, source}]`，前端在答案下方展示缩略图，点击可看大图。
- 无 NEWS 引用或无封面 → 不出图，不报错。
- 数量上限（≤3 张），避免刷屏。

### R2 图片字段落库

- `sparkora_qa_message` 增 `image_refs TEXT`（JSON 数组，同上结构，可空）；仅 assistant 消息非空。
- 生成答案时（`QaService`）在组装引用后计算配图并写入；失败仅告警不阻断答案（答案可用性优先）。

### R3 既有问答零回归

- 不改变答案文本、引用、`ragStatus` 语义；图片是**附加展示**。
- 老消息（`image_refs` 为 NULL）前端不展示图片区，行为不变。

### R3b 展示模型：直接随答案展示（用户 09-17 决策）

- 问答配图**直接随答案展示**，**无需用户批准**、无批准流程、无候选态。
- 理由：配图只是只读附加展示，不写入任何用户内容；与子C「写入正文必须用户批准」的风险模型不同（子C 改的是用户内容）。
- 本任务**不提供任何写入用户内容的路径**（不插入文章、不改答案文本）。


### R4 语义检索配图（语义路径）

- 支持「给我看某主题的图片」类问法：检测到图片意图（关键词如「看图」「海报」「照片」「图片」）时，调子B `POST /api/images/search` 检索图片并随答案展示。
- 与 R1 的新闻关联图**合并去重**后展示。
- 图片意图判定：轻量关键词 + 可选 LLM 判定；仅命中时触发，避免每条问答都检索图片。

### R5 前端展示

- `QaChat.vue` 答案气泡下方新增图片缩略图行（横向滚动），点击预览大图；标注来源（新闻标题/主题标签）。
- 移动端触控目标 ≥44px。

## Non-goals

- 不做问答的自动配图插入到文章。
- 不做图片问答（以图提问）。
- 不做答案文本中内嵌图片（保持文本 + 独立图片区）。
- 不引入新模型/向量库。

## Acceptance Criteria

- [ ] 问答答案引用了新闻知识时，答案随带该新闻的图库配图（含缩略图 URL、新闻标题）；点击可预览大图。
- [ ] 无新闻引用/无封面/图库图已删时不显示该图，不报错。
- [ ] `sparkora_qa_message.image_refs` 落库（仅 assistant 消息）；历史 NULL 消息前端行为不变。
- [ ] 配图解析失败不阻断答案生成（有 warn 日志，`image_refs` 落 null）。
- [ ] 单条答案图片数受限（≤3），多引用按 imageId 去重。
- [ ] （语义路径）问「给我看销量海报」能返回图片，与新闻关联图合并去重展示。
- [ ] 非图片意图问法不触发语义检索（不调 `searchImages`，无 embedding 浪费）。
- [ ] `Citation`/`UnifiedHit` 加 `docId` 后，既有 5 参 `Citation` 调用方（简报/深度检索/测试）编译与行为不变。
- [ ] 配图**直接随答案展示**，无批准流程、不写入任何用户内容。
- [ ] 权限：问答读取三角色；无新接口、无写接口越权。
- [ ] 零回归：答案文本/`citations`/`ragStatus` 不变；既有问答与三域检索行为不变。
- [ ] `mvn -q -DskipTests compile` 与 `npm run build` 通过；`docs/s0-spec.md` 问答章节同步 `image_refs` 与 `docId`。

## Notes

- 复杂任务：需要 `design.md`（docId 传递/配图解析服务/意图判定/合并去重/与子B 衔接）+ `implement.md`（均已产出）。
- **首要待查项已闭环**（见 Background）：`Citation` 无 news id，但 SQL 已查 `docId` 未读取，补一个可空字段即可。
- 两个依赖（子A/子B）均已归档，两条路径可一并交付，无需分阶段。
- **已定决策（用户 09-17）**：配图直接随答案展示，无需用户批准。
