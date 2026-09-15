# 技术设计：图片主题分类与来源追溯地基（子A）

## 架构与边界

```
NewsService.upsertOne ──┬──> ImageService.persistOrReuse（tags 钩子，09-13 已有）
                        │        ↑ preset: tags=[主题/x..., 年份/y], sourceRef=newsId
                        └──> NewsImageClassifier（标题 → 主题集合；publish_date → 年份）
                        
ImageTagBackfillRunner ──> 存量新闻图：文件名解析 detail<id> → 反查 news → 分类 + source_ref
                        
ImageController ──> ImageService ──> ImageTagService（多标签 AND / source 查询）
                                  └──> NewsMapper（source_ref → 新闻元信息）
ImageLibrary.vue ──> 多选标签筛选 / 来源展示
NewsKnowledgePanel.vue ──> coverImageUrl 优先 / 主题标签
```

新增组件：
- `com.sparkora.knowledge.NewsImageClassifier`（或 `com.sparkora.news.classify.NewsImageClassifier`）——纯函数式分类器，无依赖、可单测。
- `ImageService.getSource(imageId)`——来源追溯查询。

## 数据模型（schema.sql，幂等）

```sql
-- 09-15 img-classify:图片来源追溯 + 新闻封面关联图库
ALTER TABLE sparkora_image_asset ADD COLUMN IF NOT EXISTS source_ref VARCHAR(200);
CREATE INDEX IF NOT EXISTS idx_image_asset_source_ref ON sparkora_image_asset(source_ref);
ALTER TABLE sparkora_news ADD COLUMN IF NOT EXISTS cover_image_id BIGINT;
```

- `source_ref`：通用来源引用串。新闻图=`news_id`；其他来源可留空（未来可扩展车型 goods_id 等）。不加外键（沿用图库表应用层维护惯例）。
- `cover_image_id`：指向 `sparkora_image_asset.id`，可空；不建外键。

## 分类器设计（核心）

**受控词表**（代码常量 `LinkedHashMap<String, Pattern>`，保序即展示序）：

| 顺序 | 主题 | 正则 |
|---|---|---|
| 1 | 销量 | `销售` |
| 2 | 出海 | `海外\|出海\|出口\|全球化\|欧洲\|拉美\|东南亚\|巴西\|泰国\|印尼\|印度\|日本\|澳洲\|澳大\|乌兹\|匈牙利\|墨西哥\|文莱\|哥伦比亚\|香港\|德国\|慕尼黑\|东京\|曼谷` |
| 3 | 合作签约 | `合作\|签约\|携手\|战略` |
| 4 | 技术发布 | `发布\|技术\|刀片\|云辇\|智驾\|平台\|闪充\|芯片\|系统` |
| 5 | 里程碑 | `下线\|里程碑\|万辆\|纪录` |
| 6 | 荣誉 | `荣\|获\|奖\|榜\|500强\|冠军` |
| 7 | 财报ESG | `财报\|业绩\|ESG` |
| 8 | 车展上市 | `上市\|首发\|车展\|亮相` |
| 9 | 社会责任 | `捐赠\|慈善\|公益\|驰援\|救灾\|基金` |

```java
/** 纯函数：标题 → 命中主题（保序，0~n）。无命中返回空列表。 */
public static List<String> classifyThemes(String title)
/** 标签名：主题/<名>、年份/<年>。 */
public static List<String> toTags(String title, LocalDate publishDate)
```

**已知误命中与缓解**（实施时用真实 167 条回归）：
- `获` 单字过宽（「获得」「荣获」均含）——可接受（荣誉语义），但需确认不误伤（如「获客」）；若误命中多，收窄为 `荣获|获奖|获得.*(奖|榜|冠军|认)`。
- `发布` 可能命中「发布会」类非技术内容——语义上仍算「技术发布」邻近，暂接受；回归时统计。
- `榜` 命中「榜单」类——荣誉语义，接受。
- **最终词表以回归结果为准，若与上表有出入，以 `implement.md` 记录的实测调整为准**。

## 查询与接口

### 多标签 AND（R6）

`ImageService.list` 的 tag 参数从 `String` 改为 `List<String>`：
```java
// 每个标签各自查 id 集（走 idx_image_tag_name），取交集
Set<Long> ids = null;
for (String tag : tags) {
    Set<Long> hit = new HashSet<>(tagService.imageIdsByTag(tag));
    ids = (ids == null) ? hit : retainAll(ids, hit);   // AND
    if (ids.isEmpty()) return 空页;
}
if (ids != null) qw.in("id", ids);
```
- Controller：`@RequestParam(required=false) List<String> tag`（Spring 自动支持重复参数）；每项再按逗号拆分。
- 交集为空直接返回空 `PageResult`（不查库）。
- 兼容单值：传 1 个标签即原有单标签语义。

### 来源追溯（R4）

`GET /api/images/{id}/source`（三角色）：
```json
{ "sourceRef": "/page/byd-cn/news-2026/detail632",
  "news": { "id": 3, "newsId": "...", "title": "...", "publishDate": "2026-07-02", "url": "..." },
  "imageUrl": "https://..." }
```
- `source` 前缀非 `news_id` 形态或查无新闻 → `news:null`，HTTP 200 不报错。

### 增量打标（R5）

`NewsService.upsertOne` 封面转存处（09-13 已加）：
```java
List<String> tags = NewsImageClassifier.toTags(title, publishDate);  // [主题/x, 年份/y]
preset.setTags(tags);
preset.setSourceRef(newsId);
imageService.saveExternalImage(null, absImageUrl, fileName, "byd-news", tags, "system", newsId);
```
- `saveExternalImage` 需加 `sourceRef` 形参（当前签名无）；同步改 `persistOrReuse` 落 `preset.getSourceRef()`。
- 去重命中的 `newsId` 不一致时（同图被不同新闻引用）：`source_ref` 保留首次值（不覆盖），标签做 merge 合并。

### 存量回溯（R5）

`ImageTagBackfillRunner` 增新闻分支（现有车型分支不动）：
```java
// 遍历 source='byd-news' 且 (source_ref IS NULL) 的图
// 文件名正则 detail(\d+) → 匹配 news_id LIKE '%detail<数字>'（精确后缀匹配，非 LIKE 子串）
// 反查到新闻 → classify → mergeTags + 回填 source_ref
```
- 幂等：只处理 `source_ref IS NULL` 的图；已处理的跳过（也可全量重跑，merge 只插差集）。
- 匹配精度：用 `news_id` 精确后缀匹配，避免 `detail63` 误配 `detail632`（同 09-13 删除引用检查的精确匹配教训）。

## 前端设计

### 图库页（`ImageLibrary.vue`）
- 标签筛选控件 `el-select` 改 `multiple`；`tagFilter` 由 `''` 改 `[]`；`load()` 传 `tag` 数组（axios 会序列化为重复参数）。
- chip 条：每个选中标签一个 chip，单独清除；`hasFilter`/`clearAllFilters`/`activeChips` 相应改。
- 标签下拉**分组展示**：名称含 `/` 的按前缀分组（`主题`/`年份` 分组，自由标签归「其他」）——用 `el-option-group`。
- 卡片 hover 层来源行：调 `GET /images/{id}/source`（懒加载或列表随带）显示「来源：<新闻标题> · <日期>」，点击跳原文；主题标签可点即筛。

### 新闻知识页（`NewsKnowledgePanel.vue`）
- 封面 URL 取 `coverImageUrl || resolveUrl(imageUrl)`。
- 卡片/详情展示主题标签（来自关联图库资产？或新闻侧算一份）——**决策**：新闻列表接口直接返回分类主题（后端在 NewsService 用同一分类器算，展示无需查图库），点击跳图库 `?tag=主题/<主题>`。

## 兼容与迁移

- 纯增量：两个可空列 + 新分类器 + 既有接口参数从单值扩为多值（单值向后兼容）。
- `GET /api/images/tags` 响应结构不变（名称含新前缀，前端分组展示）。
- `saveExternalImage` / `persistOrReuse` 加 `sourceRef` 参数用重载或可选参数，避免波及车型图调用方。
- 存量回溯只处理 byd-news，不影响其他来源。
- 新闻页 `imageUrl` 字段保留，回退链路完整。

## 权衡记录

- **词表在代码而非 DB**：主题集合是**低频变更的产品定义**，放代码可版本化、可单测、避免运行时配置漂移；改词表=发版，可接受。
- **标签命名空间前缀 `主题/`**：与自由标签隔离，筛选下拉可分组；代价是标签名不再「干净」（用户看到「主题/销量」）。替代方案是后端返回带 `group` 字段，但 09-13 标签模型是纯名称，加分组字段改动更大——前缀是低成本方案。
- **多主题重叠（不互斥）**：一条「海外销量创新高」新闻确实同时属于销量与出海，强制单主题会丢信息；AND 筛选天然处理多标签。
- **新闻列表侧重算分类 vs 查图库标签**：新闻侧重算（同一分类器）避免列表 N+1 查图库；代价是同一逻辑两处调用（同一静态方法，无双源风险）。
- **`source_ref` 用通用串而非强 FK**：一个字段承载所有来源；新闻侧用 `news_id` 字符串（业务唯一键，非自增 id，更稳定）。
- **年份标签**：只到年份粒度，够回答「今年销售数据」；月粒度信息在新闻 `publish_date` 里，需要时再扩。

## 回滚

- 前端回滚：还原 `ImageLibrary.vue` / `NewsKnowledgePanel.vue`。
- 后端回滚：还原 Java 文件；两列留存无害。
- 数据回滚：`DELETE FROM sparkora_image_tag WHERE tag_name LIKE '主题/%' OR tag_name LIKE '年份/%';` + `UPDATE sparkora_image_asset SET source_ref=NULL;` + `UPDATE sparkora_news SET cover_image_id=NULL;`（幂等）。