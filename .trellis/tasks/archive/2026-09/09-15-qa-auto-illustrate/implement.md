# 执行计划：知识问答自动配图（子D）

## 前置

- [ ] 确认子A（`09-15-img-classify`）已归档：`sparkora_news.cover_image_id` 已回填（157 条新闻有图库封面）、`sparkora_image_asset.source_ref` 可用。
- [ ] 确认子B（`09-15-img-semantic-search`）已归档：`ImageEmbeddingService.searchImages(query, topK, minScore, tags)` 可用。
- [ ] 已确认决策：**配图直接随答案展示，无需用户批准**（只读附加展示，不写用户内容）。

## 实施顺序

### M1 数据层

- [ ] 1. `schema.sql` 追加幂等段：`ALTER TABLE sparkora_qa_message ADD COLUMN IF NOT EXISTS image_refs TEXT;`
- [ ] 2. 手工执行该段 SQL 两次验证幂等（NOTICE skipping，无 `DO $$`）
- [ ] 3. `QaMessageEntity` 加 `private String imageRefs;`

### M2 docId 传递（最小改动，向后兼容）

- [ ] 4. `CarRagService`：
  - `UnifiedHit` 加末位可空 `Long docId`
  - `Citation` 加末位可空 `Long docId` + **保留 5 参兼容构造器**（委托 6 参传 null）
  - `retrieveUnified`（约 :373）：读 `row.get("docId")` 传入 `UnifiedHit`
  - boost 重排（约 :251）：**透传 `h.docId()`**（易漏点）
  - `Citation` 构造（约 :347）：透传 `h.docId()`
- [ ] 5. 单测补 `CarRagService` docId 断言：
  - boost 后 docId 保留（NEWS 块经锚点加权分支）
  - `Citation` 5 参构造器仍可用（兼容）
- [ ] 6. 确认既有调用方编译通过：`QaServiceTest`（5 参 Citation）、`KnowledgeSearchTool`、`BriefService.citationsJson`

### M3 配图解析服务

- [ ] 7. 新建 `QaImageRef` DTO（record）：`(Long imageId, String url, String thumbUrl, String title, String newsId, String source)`
- [ ] 8. 新建 `QaImageIntent`（纯静态）：`isImageIntent(String question)`；关键词表（看图/看图片/图片/海报/照片/配图/给我看/我想看/看一下）
- [ ] 9. 新建 `QaImageRefService`：
  - `byNewsCitations(citations, limit)`：批量 `newsDocMapper.selectBatchIds` → `newsMapper.selectBatchIds` → `imageService.loadDerived`；跳过 null/无 url；保 citations 顺序去重
  - `bySemanticQuery(question, limit)`：剥离意图短语 → `imageEmbeddingService.searchImages` → 映射 DTO
  - `forAnswer(citations, question, limit)`：两路合并 → 按 imageId 去重（新闻图优先）→ 截断；包 try/catch 返回空 + warn
  - 依赖：`NewsDocMapper`/`NewsMapper`/`ImageService`/`ImageEmbeddingService`/`AiProperties`
- [ ] 10. `ImageService` 新增**只读**方法 `loadDerived(List<Long> imageIds)`：`selectBatchIds` + `fillDerived` + 过滤不存在项；**不得**改动任何写路径
- [ ] 11. 单测 `QaImageIntentTest`（意图判定：正例/负例/null/空）
- [ ] 12. 单测 `QaImageRefServiceTest`（Mock）：
  - NEWS 引用 → 关联出图；无 cover_image_id → 跳过；图库 url 空 → 跳过
  - 非图片意图 → **不调** `searchImages`（`never()`）
  - 图片意图 → 调 `searchImages`
  - 合并去重（同 imageId 两路命中 → 1 条）；上限截断
  - 任一依赖抛异常 → 返回空列表**不抛出**（答案优先）

### M4 接入 QaService

- [ ] 13. `QaService` 注入 `QaImageRefService`；`ask` 第 4 步后解析配图：
  - `IMAGE_REF_MAX = 3` 常量
  - try/catch：异常仅 warn，`imageRefs` 落 null
  - 非空才 `toJson`，空存 null
- [ ] 14. 单测 `QaServiceTest` 补：配图解析抛异常 → 答案仍正常落库（`imageRefs` null）；配图非空 → `imageRefs` 含 JSON

### M5 前端展示

- [ ] 15. `QaChat.vue`：答案气泡内 `CitationList` 后加图片行
  - `imgRefsOf(m)` 兼容 `imageRefs` 为 JSON 字符串或数组（复用 `CitationList` 的兼容写法，**不得**假设字段名——子C 曾因 record 字段名混用踩 P0）
  - `el-image` `:src="thumbUrl || url"`、`preview-src-list` 用 **url（原图）** + `preview-teleported`
  - 移动端横向滚动、触控目标 ≥44px
  - 历史消息无 `imageRefs` → 不渲染（`v-if`）
- [ ] 16. `npm run build`

### M6 验证

- [ ] 17. `mvn -q -DskipTests compile`、`mvn test`
- [ ] 18. 真机验证（清单见下）
- [ ] 19. 零回归：答案文本/`citations`/`ragStatus` 不变；简报与深度检索的 `citations` 仍正常；历史消息展示不变

### M7 规格同步

- [ ] 20. `docs/s0-spec.md`：问答章节加 `image_refs` 字段、配图来源（新闻关联图/语义图）、展示契约；`Citation`/`UnifiedHit` 的 `docId` 字段说明
- [ ] 21. `.trellis/spec/` 沉淀（若发现跨层约定）：如「record 加字段用兼容构造器保持既有调用方」「只读派生展示不引入批准流程（vs 写入用户内容必须批准）」

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test
./dev.sh restart backend
cd /dockerData/code/sparkora/frontend && npm run build
```

### 真机验证清单

```bash
# 1) 新闻关联图（便宜路径）
#    建会话 → 问一个会命中 NEWS 的问题（如「比亚迪和谁合作建闪充生态」）
curl -s -X POST localhost:5661/api/qa/sessions/$SID/messages -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"question":"比亚迪最近和哪些企业合作建闪充生态？"}'
# 期望:assistantMessage.citations 含 source=NEWS；assistantMessage.imageRefs 含图片(带 title/url/thumbUrl/newsId)
#      DB: sparkora_qa_message.image_refs 非空

# 2) 无 NEWS → 无图不报错
#    问一个纯车型参数问题 → imageRefs 为 null 或空；code=0

# 3) 语义路径（图片意图）
#    问「给我看销量海报」→ imageRefs 含图（来自语义检索）；非图片意图问题不触发额外检索
curl -s -X POST localhost:5661/api/qa/sessions/$SID/messages -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"question":"给我看比亚迪销量海报"}'

# 4) 历史消息读取
curl -s localhost:5661/api/qa/sessions/$SID -H "Authorization: Bearer $T" | grep imageRefs
# 期望:老消息无 imageRefs 键或为 null（前端不展示）；新消息含

# 5) 权限
#    未登录 → 401；VIEWER 提问/读取 → 200（无新接口,沿用既有矩阵）

# 6) 零回归
#    简报 rag_citations 仍含 source/modelName/score/chunkText（多一个 docId 不影响）
#    深度检索 KnowledgeSearchTool 正常
#    CitationList 展示不变（不读 docId）
```

### DB 交叉核对

```sql
-- 新闻关联链路（任取一条有封面的新闻）
SELECT nd.id AS doc_id, nd.news_id, n.cover_image_id
FROM sparkora_news_doc nd JOIN sparkora_news n ON n.id = nd.news_id
WHERE n.cover_image_id IS NOT NULL LIMIT 3;

-- 配图落库
SELECT id, role, rag_status, image_refs FROM sparkora_qa_message ORDER BY id DESC LIMIT 5;

-- 零回归:历史消息未被改写
SELECT count(*) FROM sparkora_qa_message WHERE image_refs IS NULL;
```

## 风险点与回滚

- **`docId` 在 boost 重排处漏传**（最高风险）：`CarRagService.java:251` 重建 `UnifiedHit` 时漏传 → NEWS 配图静默失效。M2 第 5 项单测必须覆盖。
- **`Citation` record 加字段**：必须保留 5 参构造器，否则 `QaServiceTest`/`KnowledgeSearchTool`/`BriefService` 编译失败。M2 第 6 项确认。
- **配图解析绝不能阻断答案**：所有路径包 try/catch；M4 第 14 项单测覆盖。
- **前端字段名**：`imageRefs` 是 JSON 字符串（后端 `toJson`），必须兼容字符串/数组两种形态（子C P0 教训）。
- **预览用原图 URL**：`preview-src-list` 用 `url` 不用 `thumbUrl`（webp 兼容性既有教训）。
- **改动生产数据的禁止**：验证「无 NEWS 引用」等边界用例时，**优先用 JUnit+Mock**；如需端到端，只新建自己的临时会话（可删），**不得** `UPDATE` 既有消息行（09-15 事故教训）。
- 回滚见 design.md。

## Review gate

- task.py start 前需用户确认本计划与 prd/design。
