# 技术设计:重复配图防御(img-suggest-dedup)

## 0. 现状与决策点

`suggest()`(IllustrationSuggestionService.java:88)链路:开关校验 → 取当前版本正文 → `AnchorExtractor.extract(contentMd, 5)` → 过滤已忽略锚点 → 逐锚点 `searchImages(锚点全文, topN=3, threshold, tags)` → 组装。缺陷在于:**extract 只看「剥标记后是否还有正文」**(`extractText` 按「段落」丢弃图片-only 段落),标题行正下方的插图段落被丢弃后,该节点仍有其余正文 → 照常产建议。图片语法在哪儿(`extractText` 的段落清洗阶段)已经丢失了位置信息,需要在更早的层级把「节内有没有图」带上。

三个决策点:D1 锚点图判定放在 extractor;D2 已登记图排除的依赖注入;D3 跨锚点去重的会话集合。

## D1 AnchorExtractor:锚点带 hasImage 标志

- `Anchor` record 增加布尔字段 `hasImage`(参数顺序:在 `key` 之后追加,避免破坏既有单测构造位置)。
- 判定时机:在 `splitSections` 产出的 `RawSection` 级别,`extract` 循环中对**原始行**逐行做 `IMAGE.matcher(line).find()`:
  - 围栏内行(`FENCE` 翻转状态)不判定(与 extractText 丢弃围栏内容一致);
  - 标题行自身在 RawSection.lines 之外,不参与;正文行含 `![]()` 均算(整段落是图或多图段落都是同一结论:已配图);
  - 剥标记后的判定不可靠(IMAGE 在 stripMarkdown 里被整段替换,与 LINK 的保留文字不同),所以必须对原始行判定。
- **指纹不变**:`fingerprint(headingPath, text)` 的入参与归一化路径不动 → 已忽略记录与正文纯格式编辑后的偏移行为完全不受影响。
- H1(文章标题)行不建段亦不参与;无标题退化路径(`splitByBlankLines`)同样逐原始行判定。

## D2 已登记关联图排除:只读依赖注入

- 现状:本类**刻意不注入 ImageService**(防止 modifyBodyImage/setCover 写路径可达,类注释写明)。
- 方案:注入 `ArticleVersionImageMapper`(`mapper/ArticleVersionImageMapper.java`,P1-⑦ 规范化关联表),`suggest()` 内按 `version_id` 查询本版本已登记图 id 集合。**只读 mapper**,不突破「无写路径可达」约束;类注释补一句说明新增依赖是只读关联表。
- 用法:`searchImages` 返回的 `ImageSearchHit.imageId` 对集合过滤。查询每调用一次(非每锚点),开销可忽略。

## D3 跨锚点去重:单一 seen 集合双语义

- `suggest()` 循环外声明 `Set<Long> seen`,**初始化为 D2 的已登记集合**(注释写明双语义:登记图=全局占用,会话已出现=本次占用)。
- 每锚点:`hits` 过滤 `!seen.contains(imageId)` 后,若命中且非空,把入选 imageId 加入 `seen`。
- 副产品:「全部采用」在多组采用同一图的行为被结构性消除(组内候选已互斥),`usePreviewSuggestions.sugAdopted` 的已采用标记保留原语义。

## 兼容性与回滚

- API 契约:字段、状态码、权限均不变,仅候选内容收窄;前端零改动。
- docs/spec/image.md §7.2:过滤规则清单新增两条(节内含图跳过/已登记与先命中占用),接口行为表格里「零副作用」描述不动。
- 回滚:纯后端单逻辑提交,无迁移、无数据回填,revert 即回滚。

## 权衡记录

- **跳过 vs 标灰**:PRD 定为静默跳过——「已配图」多数用户意图是"别打扰我";需要再插图的用户走图库 tab 显式操作。若未来要恢复,extractor 的 hasImage 字段已可直接上送,前端加角标成本低。
- **R3 按「登记行」而非「markdown 位置」排除**:登记行是发布计数与防误删的真源(双写自洽设计),markdown 位置信息(R1)与登记(R3)各管一层,互为兜底——用户手动删掉 markdown 图时登记行仍在,不再把该图推荐到其他段落,行为更保守更可预期。
- **围栏内图片语法不判定**:与 extractText 丢弃围栏内容保持同一世界观,避免"建议区看得见围栏"。