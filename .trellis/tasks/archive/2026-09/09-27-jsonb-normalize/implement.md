# P1-⑦ body_image_ids 规范化 实施计划

> 顺序：先迁移+实体/Mapper → 再 ImageService/PreviewService → 最后测试与文档。每步可独立编译验证。

## 前置检查

- [ ] `git status` 干净基线
- [ ] `mvn -q -DskipTests compile` 通过（改动前基线）
- [ ] 确认全库 `body_image_ids` 现有引用点（grep）与设计表一致

## Step 1: 迁移 + 实体 + Mapper

- [ ] 写 `src/main/resources/db/migration/V2__article_version_image.sql`（见 design §1）
- [ ] **预检（真实库只读）**：`SELECT count(*) FROM sparkora_article_version WHERE body_image_ids IS NOT NULL AND body_image_ids <> '' AND EXISTS (SELECT 1 FROM regexp_split_to_table(body_image_ids, ',') t WHERE trim(t) !~ '^\d+$')` → 必须为 0（否则先报告，不直接强转）
- [ ] 新增 `ArticleVersionImageEntity` + `ArticleVersionImageMapper`
- [ ] `ArticleVersionEntity` 移除 `bodyImageIds` 字段
- 验证：`mvn -q -DskipTests compile`（预期此时 ImageService/PreviewService 编译失败 → 进入 Step 2）

## Step 2: 服务层改造

- [ ] `ImageService`：新增 `bodyImageIds(versionId)`；`modifyBodyImage` 改 insert/delete；`delete` 引用检查改精确 SQL；`projectImages` 用新方法；删 `bodyIdListOf`
- [ ] `PreviewService`：注入 mapper，删本地 `bodyIdListOf`，改查关联表
- 验证：`mvn -q -DskipTests compile`；`grep -rn "body_image_ids\|getBodyImageIds\|setBodyImageIds" src/main/java` 零残留

## Step 3: 测试同步

- [ ] 排查既有测试对 `getBodyImageIds`/`bodyIdListOf` 的 mock（`IllustrationSuggestionServiceTest` 仅注释引用，确认无需改）
- [ ] 新增 `ArticleVersionImageMapperTest`（顺序/幂等/冲突）
- [ ] 更新/新增 `ImageService` 引用检查与快照测试
- 验证：`mvn test`

## Step 4: 迁移端到端（隔离容器）

- [ ] 空库路径：pgvector 临时容器 → 跑 V1 → 跑 V2 → 表存在、无 `body_image_ids` 列
- [ ] 既有库路径：先跑 V1 并插入含逗号 `body_image_ids` 的行 → 跑 V2 → 关联表行数/顺序与原串一致 → 列已删
- [ ] 重启幂等：再启动一次 → up to date
- （沿用 ⑥ 的隔离容器做法；真实库不碰）

## Step 5: 文档 + spec

- [ ] `docs/spec/image.md` / `docs/spec/preview.md` 字段级表格：`body_image_ids` → `sparkora_article_version_image`
- [ ] `.trellis/spec/backend/database-guidelines.md`：1NF 段记录 body_image_ids 已规范化；JSONB 段记录「⑦ 复核：JSON 字段 TEXT 为有意约定，不转 JSONB」裁定
- [ ] `docs/README.md`/`AGENTS.md` 如提及 `body_image_ids` 则同步

## Step 6: 全量验证

- [ ] `mvn -q -DskipTests compile` + `mvn test` + `cd frontend && npm run build`
- [ ] `git diff` 核对未误改其他 JSON 列

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| Step 1 迁移 | `::bigint` 强转遇非法 token | 预检 SQL 先跑；异常则改 `NULLIF/正则过滤` 或中止报告 |
| Step 1 DROP 列 | 不可逆 | 数据已搬入关联表；回滚=重加列 + `string_agg ORDER BY sort_order` 聚合 |
| Step 2 | 遗漏 `bodyIdListOf` 调用点 | 编译器兜底（删字段后必须全部改完） |
| Step 4 | 真实库误操作 | 仅用隔离容器；真实库不碰 |

## 提交约定

- `feat(db): body_image_ids 规范化为 version-image 关联表（V2 迁移）`
- `docs(spec): 图像版本关联表字段同步 + JSONB 有意约定裁定`
- commit 中文，scope 按仓库惯例。

## start 前检查

- [ ] implement.jsonl / check.jsonl 已填真实条目
- [ ] 用户已确认范围 A（或改为其他范围后重写本文件）

---

## 执行结果（2026-09-27 implement）

### 实际改动文件

**新增**
- `src/main/resources/db/migration/V2__article_version_image.sql`（建表 + 保序回填 + DROP 列，单迁移）
- `src/main/java/com/sparkora/domain/entity/ArticleVersionImageEntity.java`
- `src/main/java/com/sparkora/mapper/ArticleVersionImageMapper.java`（`findImageIdsByVersion`/`findVersionIdsByImage`/`maxSortOrder`/`deleteByVersionAndImage`/`deleteByVersion`）
- `src/test/java/com/sparkora/service/ImageServiceBodyImageTest.java`（10 例）
- `src/test/java/com/sparkora/service/PreviewServiceBodyImageTest.java`（1 例）

**修改**
- `ArticleVersionEntity`（移除 `bodyImageIds` 字段）、`ImageService`（构造注入 mapper；delete 引用检查精确 SQL；projectImages 查关联表；modifyBodyImage insert/delete；删 `bodyIdListOf`）、`PreviewService`（注入 mapper；删本地 `bodyIdListOf`）
- 3 个既有 ImageService 测试构造器补 `versionImageMapper` mock
- `db/migration/README.md`、`docs/README.md`、`docs/spec/image.md`、`docs/spec/preview.md`、`docs/spec/version-generation.md`
- `.trellis/spec/backend/database-guidelines.md`（JSONB 有意约定裁定 + 有序小集合不落逗号列先例 + Warning 更新）、`ai-rag-guidelines.md`、`quality-guidelines.md`、`frontend/index.md`

### 与 design.md 的偏差

- design §3 拟新增 `ImageService.bodyImageIds(versionId)` 读方法；实现改为**直接注入 `ArticleVersionImageMapper` 并在服务内调用**（`projectImages`/`delete`），未额外包一层服务方法——读语义与有序查询已在 mapper 固化，避免多一层无收益包装。行为/契约与 design 等价。
- design §5 提示 `setCover` 仍走 `versionMapper.updateById(v)`（不同列，保留）——实现保持原样。
- 其余（迁移 SQL、实体/Mapper、modifyBodyImage 语义、对外契约不变）逐项按 design 落地。

### 验证证据

- `mvn -q -DskipTests compile`：EXIT 0
- `mvn test`：**382 全绿**（370 基线 + 12 新增；check 阶段补 `add_并发UNIQUE冲突_静默视为成功` 一例），Failures 0 / Errors 0
- `cd frontend && npm run build`：✓ built
- `grep -rn "body_image_ids" src/main/java`：零命中（仅 V2 迁移脚本保留，用于回填 + DROP）
- `grep -rn "bodyIdListOf\|getBodyImageIds\|setBodyImageIds" src/main src/test`：零命中
- `git status --short src/main/resources/db/migration/V1__baseline.sql`：空（V1 未改）

**迁移端到端（隔离 `pgvector/pgvector:pg17` 容器 @127.0.0.1:55499，真实库未碰）**
- 真实库只读预检：非法 token `count=0`；存量 4 行非空（`22,19` / `22` / `22,29` / `22`），版本表共 38 行
- 路径 B（空库）：V1 → V2 → `body_image_ids` 列消失、关联表存在；Flyway 记录 `1:SQL,2:SQL`；重启 `applied=0 / up to date`
- 路径 A（既有库，V1 baselined + 逗号存量）：Flyway `BSLN@1` 跳过 V1 → 应用 `2 - article version image`；重启幂等
- 回填顺序：`22,19` → `[22,19]`；`7,3,9` → `[7,3,9]`；`" 5 , 51 "` → `[5,51]`（trim 生效）；8 行 = 非空元素总数；`NULL`/`''` 零行
- 引用精确性：`image_id=22` 反查 2 个版本；`image_id=5` 与 `51` **各自精确命中**，互不误匹配（原 LIKE hack 消除）
- 测试容器与临时库已删除（`docker rm` + `DROP DATABASE`）

### 未决 / 残留风险

- 迁移 `::bigint` 强转依赖存量数据合法（预检已证 0 异常）；若有外部写入非法 token 会致迁移失败（fail-fast，可发现）。
- DROP 列不可逆（数据已搬关联表）；回滚为 manual：重加列 + `string_agg ORDER BY sort_order` 聚合。
- 关联表无版本级联删除路径（与改造前一致，代码库当前无版本删除入口）；`deleteByVersion` 已备工具方法。

