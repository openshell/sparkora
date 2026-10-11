# 执行计划:重复配图防御(img-suggest-dedup)

> 开工参照顺序:implement.jsonl 清单 → prd.md → design.md → 本清单。
> 环境前提(parent 收口事项):bug 修复已独立提交;后端容器已重建。

## Checklist(按序执行)

### 第 1 步 AnchorExtractor 扩展(纯静态,先行)

- [x] `src/main/java/com/sparkora/article/illustrate/AnchorExtractor.java`
  - `Anchor` record 追加 `boolean hasImage` 字段(位于 `key` 之后)
  - `extract()` 中按节对**原始行**判定图片语法(围栏内行跳过;退化路径 `splitByBlankLines` 同样判定)
  - 指纹逻辑与入参不得有任何变化
- [x] 单测 `AnchorExtractorTest`(沿用既有纯静态直测风格):
  - H2 标题下紧跟图片段落 → hasImage=true
  - 节中间正文行插图 → true
  - ``` 围栏内 `![]()` → false
  - 节内无图 → false
  - 既有用例全数保持通过(record 构造签名变更要同步修复)

### 第 2 步 suggest 过滤链

- [x] `IllustrationSuggestionService.java`
  - 注入 `ArticleVersionImageMapper`(只读;构造器参数追加,类注释补只读说明)
  - `suggest()`:已登记 id 集合查询(每调用一次)→ hasImage 锚点跳过(R1)→ hits 按已登记+本会话 seen 过滤(R2/R3,合并 seen 集合)
  - 类注释更新:硬约束段落补「只读关联表不计入写路径」
- [x] 单测 `IllustrationSuggestionServiceTest`(沿用既有假件风格,不 Mockito 代理 mapper):
  - 版本已登记关联图 → 不出现在候选
  - 锚点节内含图 → 整组不出现
  - 同图命中两锚点 → 仅最先一组出现
  - 既有用例(开关关闭 400/空正文 400/已忽略过滤/单锚点失败跳过)保持通过

### 第 3 步 文档同步

- [x] `docs/spec/image.md` §7.2:过滤规则清单新增「节内已含 markdown 图 → 该锚点跳过」「已登记关联图与先命中图不重复推荐(合并 seen 集合)」;接口行为行核对零副作用描述不动
- [x] 若 `.trellis/spec/backend/quality-guidelines.md` 的只读约束纪律有值得沉淀的新经验(只读 mapper 扩依赖先例),用 trellis-update-spec 收口(可选)

### 第 4 步 验证与验收

- [x] `mvn -q -DskipTests compile`
- [x] `mvn test`(全量 510+,绿)
- [x] 后端容器重建:`docker compose up -d --build backend`
- [x] 真机验收(容器环境,token 走 admin/admin123):
  - 项目 65 正文某段标题后插图(采纳一条建议即可制造状态)
  - `POST /api/projects/65/illustration-suggestions` → 该锚点不再出现在响应
  - 已插图图片 id 不再出现在任何后续锚点候选
  - 「插入到此段/全部采用」流程回归一遍,行为正常

## Review gate

- 实现完成后 dispatch `trellis-check`(check.jsonl 已配),全绿后回报用户等 review。

## 回滚点

- 全部改动一个 commit(`fix(image): ...`),revert 即回滚;无迁移、无数据变更。