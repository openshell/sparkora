# 执行计划：图片语义向量检索（子B）

## 前置

- [ ] 确认子A（`09-15-img-classify`）已归档：`sparkora_image_asset.source_ref` 列存在、byd-news 157 张已有主题/年份标签（嵌入文本依赖）。

## 实施顺序

### M1 数据层

- [ ] 1. `schema.sql` 追加 09-15 img-semantic-search 段（幂等）：
  - `sparkora_image_embedding`（`image_id`/`embedding VECTOR(1024)`/`source_text`/`created_at`）
  - `uk_image_emb_image`（UNIQUE(image_id)）+ `idx_image_emb_vec_hnsw`（HNSW cosine）
- [ ] 2. 手工执行该段 SQL 两次验证幂等（NOTICE skipping，无 `DO $$`）

### M2 嵌入文本构造器（可单测）

- [ ] 3. 新建 `ImageEmbeddingTextBuilder`（纯静态）：
  - `build(ImageAssetEntity img, List<String> tags, String newsTitle)` → String
  - 四来源分派规则（byd-news=标题+标签优先；ai-*=promptText+标签；upload/byd=fileBaseName+标签）
  - 边界：全空兜底 `(图片 <id>)`；2000 字符截断；null 安全
- [ ] 4. 单测 `ImageEmbeddingTextBuilderTest`：
  - 四来源各 1 例断言文本构成
  - 标签拼接、新闻标题缺失退化、全空兜底、超长截断（≥5 例）

### M3 Mapper + 服务

- [ ] 5. 新建 `ImageEmbeddingMapper`（注解 SQL）：`insert`/`deleteByImageId`/`findImageIdsWithoutEmbedding`/`searchTopK`（`<script>` 支持 ids 白名单）
- [ ] 6. 新建 `ImageEmbeddingService`：
  - `embedOne(img)`（先清后插）、`embedQuietly(imageId)`（吞异常 warn）
  - `rebuildAll()` / `rebuildMissing()` → `EmbedStats`（对标 `KbDocService`）
  - `deleteByImageId(id)`
  - `searchImages(query, topK, minScore, tags)`：入参校验 → 标签 AND 预过滤（空集早返回）→ embed query → `searchTopK` → 分数门槛 → 批查回填 url/thumbUrl/tags/sourceRef
  - `EmbedStats(int total, int success, int failed)` record
- [ ] 7. `AiProperties` 加 `imageMinScore = 0.3`；`.env.example` 加 `AI_IMAGE_MIN_SCORE` 段

### M4 接入点

- [ ] 8. `ImageService.persistOrReuse`：新图 `applyPresetTags` 后 + 去重命中分支，调 `embeddingService.embedQuietly(id)`
- [ ] 9. `ImageService.delete`：加 `embeddingService.deleteByImageId(id)`
- [ ] 10. `ImageService.list` 的 `resolveTagIds` 复用给检索（若需改可见性，改为 `public static`；或在 `ImageEmbeddingService` 内直接调 `tagService.imageIdsByTag` 自建交集——**优先后者，少改 ImageService**）

### M5 DTO + 接口

- [ ] 11. 新建 `ImageSearchHit` DTO（`imageId`/`score`/`sourceText`/`fileName`/`source`/`sourceRef`/`url`/`thumbUrl`/`tags`）
- [ ] 12. 新建 `ImageEmbedDTO`（`@Valid`：`query` @NotBlank；`topK`/`minScore`/`tags` 可选）
- [ ] 13. `ImageController`：
  - `POST /search`（三角色）→ 调 `searchImages`，空 query 由 DTO 校验返回 400
  - `POST /embeddings/rebuild`（ADMIN/EDITOR）→ `R<EmbedStats>`

### M6 存量补齐

- [ ] 14. 新建 `ImageEmbeddingBackfillRunner`（`ApplicationRunner`）：
  - 调 `rebuildMissing()`；失败仅 warn 不阻断启动；日志 `图片向量补齐完成:total=X success=Y failed=Z`
  - 幂等：重跑 `findImageIdsWithoutEmbedding` 为空即跳过
- [ ] 15. 重启后端，验证日志补齐条数 ≈ 存量图片数；重跑幂等（0 条）

### M7 验证

- [ ] 16. 编译 + 测试：`mvn -q -DskipTests compile`、`mvn test`
- [ ] 17. 真机接口验证（详见下方清单）
- [ ] 18. 单测补充：`ImageEmbeddingServiceTest`（可 Mock mapper/client）——query 空校验、标签空集早返回、topK 收敛、minScore 默认

### M8 规格同步

- [ ] 19. `docs/s0-spec.md`：新增/扩展检索章节——图片向量域（表结构/维度/HNSW）、嵌入文本规则、`POST /api/images/search` 契约、`POST /api/images/embeddings/rebuild` 契约、`AI_IMAGE_MIN_SCORE` 配置；§1 接口清单登记两接口；§10 图库章节提及语义检索能力

## 验证命令

```bash
mvn -q -DskipTests compile
mvn test                                  # 现有 111 + 新增
./dev.sh restart backend
./dev.sh logs backend | grep 图片向量补齐
cd frontend && npm run build              # 本任务无前端改动,仅确认未被波及
```

### 真机接口清单

```bash
# 1) 语义检索命中
curl -s -X POST localhost:5661/api/images/search -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"query":"销量海报"}'
# 期望:命中销售类新闻图(source=byd-news),score 降序,含 tags/sourceRef/url

# 2) 标签预过滤
#    {"query":"海报","tags":["主题/销量","年份/2026"]} → 仅 2026 销量图
#    {"query":"海报","tags":["主题/销量","年份/2023"]} → 该年销量图(或 data:[])
#    {"query":"海报","tags":["主题/销量","不存在标签"]} → data:[] (空集早返回)

# 3) 校验/权限
#    query 空 → 400;topK=999 → 收敛;minScore=0.99 → data:[] (门槛过滤)
#    VIEWER 读 → 200;rebuild 用 VIEWER → 403;未登录 → 401

# 4) 重建幂等
#    POST /embeddings/rebuild 两次 → 两次 {total,success,failed} 一致;DB 行数不变

# 5) 删图联动
#    上传临时图 → 查 DB 有向量 → DELETE /images/{id} → 向量行 0

# 6) 增量嵌入
#    上传一张新图 → 查 DB 该图 vector 行存在;source_text 合理
#    上传带标签图 → source_text 含该标签
```

### DB 交叉核对

```sql
SELECT count(*) FROM sparkora_image_embedding;                       -- ≈ 图库图数
SELECT count(*) FROM sparkora_image_asset a LEFT JOIN sparkora_image_embedding e ON e.image_id=a.id WHERE e.id IS NULL;  -- 0(补齐后)
SELECT image_id, left(source_text,80) FROM sparkora_image_embedding WHERE image_id=<新闻图id>;  -- 标题+主题/年份标签
```

## 风险点与回滚

- **启动期补齐耗时**：157 图逐张 embedding。若实测超 30s，改为独立线程异步补齐（日志输出进度），不阻断应用就绪。**不可**因补齐失败导致启动失败（runner 必须 try/catch）。
- **`embedQuietly` 吞异常**：确保只在嵌入处吞，不掩盖入库本身的异常（钩子放在 `persistOrReuse` 成功 insert 之后）。
- **唯一索引冲突**：并发嵌入同一图 → 第二插入报唯一冲突，`embedQuietly` 吞掉并 warn（先清后插已大幅降低概率）。
- **DTO 校验 400 文案**：`@NotBlank` 默认消息是英文/泛化，需与项目惯例一致的中文提示（参照 `ImageGenDTO` 等的写法）。
- **标签变更导致向量漂移**：本期不做实时重嵌，记入 spec 已知限制 + 用 rebuild 修正。
- 回滚见 design.md。

## Review gate

- task.py start 前需用户确认本计划与 prd/design。
