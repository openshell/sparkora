# 执行计划：文章创作配图建议（子C）

## 前置

- [ ] 确认子B（`09-15-img-semantic-search`）已归档：`ImageEmbeddingService.searchImages(query, topK, minScore, tags)` 可用、`AI_IMAGE_MIN_SCORE` 已配。
- [ ] 确认勘察结论（design.md「勘察发现」）：实际渲染只认 `contentMd` 里的 markdown 图片引用；采用时须同时写 markdown 与登记 `body_image_ids`。
- [ ] 用户已确认：配图=建议，必须用户批准；**不提供任何自动插入能力**。

## 实施顺序

### M1 数据层

- [ ] 1. `schema.sql` 追加 09-15 article-auto-illustrate 段（幂等）：
  - `sparkora_illustration_dismiss`（project_id/version_id/anchor_key/created_by/created_at）
  - `UNIQUE (version_id, anchor_key)` + `idx_illustration_dismiss_version`
- [ ] 2. 手工执行该段 SQL 两次验证幂等（NOTICE skipping，无 `DO $$`）
- [ ] 3. 新建实体 `IllustrationDismissEntity` + `IllustrationDismissMapper`

### M2 锚点切分（可单测，无 Spring 依赖）

- [ ] 4. 新建 `AnchorExtractor`（`com.sparkora.article.illustrate`）：
  - `record Anchor(int anchorIndex, String headingPath, String text, String key)`
  - `extract(String contentMd, int maxAnchors)` → `List<Anchor>`
  - `fingerprint(String headingPath, String text)` → 12 位短哈希
  - 规则：按 `##`/`###` 分段（前言也算一段）；跳过纯列表/引用块/代码块/图片行/过短(<30 字)；剔除 markdown 标记；上限截断
- [ ] 5. 单测 `AnchorExtractorTest`（≥8 例）：
  - 多标题分段 + 前言段、headingPath 拼接
  - 纯列表跳过、引用块跳过、代码块跳过、图片行跳过、过短跳过
  - 无标题退化按段落、上限截断
  - 指纹稳定性（同文本同 key；编辑后 key 变化；不同锚点 key 不同）
  - 边界：null/空/纯空白 → 空列表

### M3 建议服务

- [ ] 6. 新建 `IllustrationSuggestionService`：
  - `record AnchorSuggestion(String anchorKey, int anchorIndex, String headingPath, String anchorText, List<ImageSearchHit> candidates)`
  - `suggest(projectId, tags, minScore)`：取当前版本 → extract → 过滤已忽略（按 version_id 查 dismiss）→ 逐锚点 `searchImages(text, topN, minScore, tags)` → 组装；单锚点失败跳过 + warn
  - `dismiss(projectId, anchorKey, operator)`：幂等写入
  - 依赖：`ArticleProjectMapper` / `ArticleVersionMapper` / `ImageEmbeddingService` / `IllustrationDismissMapper` / `AiProperties`
- [ ] 7. `AiProperties` 加 `illustrationMaxAnchors = 5`、`illustrationTopN = 3`；`application.yml` 加两键；`.env.example` 加段
- [ ] 8. 单测 `IllustrationSuggestionServiceTest`（可 Mock）：
  - 无版本 → 400 异常、正文空 → 400
  - 已忽略锚点被过滤
  - 单锚点检索抛异常 → 该锚点跳过、其余正常
  - minScore 非法 → 400
  - **零副作用断言**：suggest 后不调用任何写 `content_md`/`body_image_ids` 的方法

### M4 接口

- [ ] 9. `ArticleProjectController`：
  - `POST /{id}/illustration-suggestions`（三角色）body `{tags?, minScore?}` → `R<List<AnchorSuggestion>>`
  - `POST /{id}/illustration-suggestions/dismiss`（ADMIN/EDITOR）body `{anchorKey}` → `R<Void>`
  - 错误映射：IllegalArgumentException→400、其余→500（沿用既有 try/catch 写法）

### M5 前端 — 编辑器能力

- [ ] 10. `MarkdownEditor.vue` 新增 `insertMdAtAnchor(headingPath, text)`：
  - 按标题文本定位（首次出现），插入到该标题行之后
  - 找不到标题 → 退回 `insertMd`（光标处），保证不丢内容
  - 导出到 `defineExpose`（**不改** `insertMd`）
- [ ] 11. `frontend/src/api/index.js`：`projectApi` 加 `illustrationSuggestions(id, opts)`、`dismissIllustration(id, anchorKey)`

### M6 前端 — 建议面板

- [ ] 12. `StepPreview.vue` 配图抽屉新增「智能建议」tab（`imgTab='suggest'`）：
  - 生成按钮 + 标签预过滤（多选 AND）+ 提示文案「系统只给建议，点采用才写入正文」
  - 按锚点分组卡片：标题/摘要 + 候选网格（缩略图/相关度/标签）
  - 单张「插入到此段」→ `insertMdAtAnchor` + `imageApi.addBodyImage` + 刷新快照
  - 每组「全部采用」/「忽略此段」→ 忽略调 dismiss 接口并从列表移除该组
  - 空态三态：未生成 / 无候选（提示调低门槛或换标签）/ 全被忽略
  - 移动端触控目标 ≥44px；防抖 timer 清理（如有）
- [ ] 13. `npm run build`

### M7 验证

- [ ] 14. `mvn -q -DskipTests compile`、`mvn test`
- [ ] 15. 真机验证（清单见下）
- [ ] 16. 零回归核对：既有手动插图、设封面、预览渲染、发布链路无变化

### M8 规格同步

- [ ] 17. `docs/s0-spec.md`：§1 接口清单登记两接口；§10/§11 新增「配图建议」段（锚点切分规则、建议不落库、忽略表、采用时 markdown+body_image_ids 双写、无自动插入约束）；记录 `body_image_ids` 与渲染脱节的已知债务
- [ ] 18. `.trellis/spec/` 沉淀：若发现跨层约定（如「建议类派生数据不落库」「采用写入需同时满足渲染与登记」）写入对应 spec

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test
./dev.sh restart backend
cd frontend && npm run build
```

### 真机验证清单

```bash
# 1) 建议生成（需项目处于 VERSIONS_READY 且有正文）
curl -s -X POST localhost:5661/api/projects/$PID/illustration-suggestions \
  -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{}'
# 期望:按锚点分组,每组 candidates 含 imageId/score/url/thumbUrl/tags;无候选锚点不出现

# 2) 零副作用(关键) —— 生成前后对比
#    生成前记 body_image_ids / content_md → 生成 → 再查 → 必须完全一致

# 3) 标签预过滤
#    {"tags":["主题/销量"]} → 候选仅来自该标签图

# 4) 门槛
#    {"minScore":0.99} → 无候选;{"minScore":0.0} → 更宽

# 5) 忽略
#    dismiss {anchorKey} → 再次生成 → 该锚点不出现;重复 dismiss → 幂等不报错

# 6) 权限
#    VIEWER 生成 → 200;VIEWER dismiss → 403;未登录 → 401

# 7) 错误
#    无版本 → 400;正文空 → 400;minScore=2 → 400;空 anchorKey → 400

# 8) 采用写入(前端交互,浏览器或接口模拟)
#    采用后:contentMd 含 ![](url) 且 body_image_ids 含该 id;预览正常渲染该图;
#    发布页「插图 N 张」计数增加;尝试删除该图 → 被引用保护拒绝
```

### DB 交叉核对

```sql
SELECT count(*) FROM sparkora_illustration_dismiss WHERE version_id = <vid>;   -- 忽略记录
-- 生成建议前后
SELECT body_image_ids, length(content_md) FROM sparkora_article_version WHERE id = <vid>;  -- 必须不变
```

## 风险点与回滚

- **绝不可自动写入**：任何实现分支都不得调用 `modifyBodyImage` / 写 `content_md`。M7 第 2 项是硬验证项；代码审查须确认 `IllustrationSuggestionService` 无写方法注入。
- **`insertMdAtAnchor` 定位失败**：必须退回光标插入而非静默丢弃（丢内容比插错位置更糟）。
- **同名标题**：按首次出现定位；若锚点 `headingPath` 实际重复，接受「插到第一个同名标题后」的近似行为（记录为已知限制）。
- **建议无候选属常态**：图库规模小（~170 张），空态必须给出可操作指引（调低门槛 / 加标签 / 去图库补图）。
- **忽略记录误伤**：`anchor_key` 含文本短哈希，正文编辑后自动失效（不会误伤其他锚点）。
- **`body_image_ids` 遗留债务**：本任务只保证「采用时登记」，不修复其与渲染脱节的根因（记为已知债务，写进 spec）。
- 回滚见 design.md。

## Review gate

- task.py start 前需用户确认本计划与 prd/design。
