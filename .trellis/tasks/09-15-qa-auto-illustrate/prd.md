# 知识问答自动配图（子D）

## Goal

在知识问答（`QaChat`）中让答案配图：① 答案引用了新闻知识时，展示对应新闻的图片；② 支持「给我看销量海报」这类直接要图的问法（语义检索出图）。

## Dependencies

- **便宜路径（R1-R3）**：仅依赖 `09-15-img-classify`（子A）——用 `source_ref` 从命中的新闻 chunk 关联出该新闻的图库封面。
- **语义路径（R4）**：依赖 `09-15-img-semantic-search`（子B）——查图片向量表。
- 两条路径**独立可分阶段交付**：便宜路径可在子B 之前先做，语义路径待子B 完成后补。
- 若子A 未完成，便宜路径无 `source_ref` 可用，**不得提前开始**。

## Background（09-15 勘察）

- `QaMessageEntity`：`role`/`content`/`citations`（JSON `Citation[]`）/`ragStatus`，**无图片字段**。
- 问答检索跨三域（CAR/KB/NEWS），引用条目 `Citation` 含 `source`（CAR/KB/NEWS）、`modelName`、`chunkType`、`score`、`chunkText`——**NEWS 来源的引用可定位到新闻**（chunk 归属新闻文档）。
- 子A 完成后：新闻图带 `source_ref = news_id` + 主题标签；`Get /images/{id}/source` 可反查。
- 图库已有按标签 AND 筛选与（子B 完成后）语义检索。

## Requirements

### R1 新闻 chunk → 配图（便宜路径，核心）

- 问答答案的引用中若有 NEWS 来源，经该 chunk 归属的新闻 → 该新闻的图库封面（`sparkora_news.cover_image_id`）关联出图片。
- 答案消息随带图片列表：`[{imageId, url, thumbUrl, title, newsId}]`，前端在答案下方展示缩略图，点击可看大图/跳图库。
- 无 NEWS 引用或无封面 → 不出图，不报错。
- 数量上限（如 3 张），避免刷屏。

### R2 图片字段落库

- `sparkora_qa_message` 增 `image_refs TEXT`（JSON 数组，同上结构，可空）；仅 assistant 消息非空。
- 生成答案时（`QaService`）在组装引用后计算配图并写入；失败仅告警不阻断答案（答案可用性优先）。

### R3 既有问答零回归

- 不改变答案文本、引用、`ragStatus` 语义；图片是**附加展示**。
- 老消息（`image_refs` 为 NULL）前端不展示图片区，行为不变。

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

- [ ] 问答答案引用了新闻知识时，答案随带该新闻的图库配图（含缩略图 URL、新闻标题）；点击可预览。
- [ ] 无新闻引用/无封面时不显示图片区，不报错。
- [ ] `sparkora_qa_message.image_refs` 落库（assistant 消息）；历史 NULL 消息前端行为不变。
- [ ] 配图计算失败不阻断答案生成（有日志）。
- [ ] 单条答案图片数受限（≤3），多引用去重。
- [ ] （语义路径，依赖子B）问「给我看销量海报」能返回图片，与新闻关联图合并去重展示。
- [ ] 非图片意图问法不触发额外图片检索（无性能浪费）。
- [ ] 权限：问答读取三角色；无写接口越权。
- [ ] 零回归：答案文本/引用/`ragStatus` 不变；既有问答流程与检索行为不变。
- [ ] `mvn -q -DskipTests compile` 与 `npm run build` 通过；`docs/s0-spec.md` 问答章节同步图片字段与展示。

## Notes

- 复杂任务：需要 `design.md`（chunk→新闻→配图 的定位方式/图片字段结构/意图判定/与子B 接口衔接）+ `implement.md`。
- **建议分两阶段交付**：
  - 阶段一（便宜路径 R1-R3）：只依赖子A，价值高、成本低，可在子B 之前先做。
  - 阶段二（语义路径 R4-R5）：待子B 完成后补。
- 实现前需确认 `Citation`/NEWS chunk 是否携带可定位新闻的 id（否则需补 chunk→news 的关联字段）；这是 `design.md` 的首要待查项。
