# Design — C1 车型库数据基座加固

> 父任务：`09-11-knowledge-base-data-foundation`。遵循父任务 `design.md` §3.2/§3.3/§3.4 共享契约。

## 1. 边界

本子任务**只改车型域 + 一处 KB 索引**，不新增知识域、不做问答。交付：语义一致性修复、删除兜底清理、索引统一、定时同步、七牛转存验证。

## 2. R1 — `intro_images` 语义统一

### 现状
- 存储语义：`car_model.intro_images` = **图库 `image_asset.id` 列表 JSON**（`CarModelService.persistIntroImages` `:318-321` 覆盖写入）。
- 前端 `CarLibrary.vue:161-166` 把它当 URL 数组取 `arr[0]` 直接 `<img src>` → **id 当 URL，图片裂图**。
- `CarDetail.vue` 未展示 introImages（无影响）。

### 设计
- **保持存储为 id 列表**（不改成 URL——URL 由图床域名派生，改域名需全量重写，且与 `image_asset` 单一事实源冲突）。
- 在 `CarModelEntity` 增加**非持久化**字段 `introImageUrls`（`List<String>`，`@TableField(exist=false)`），与 `ImageAssetEntity.url` 派生字段同范式。
- `CarModelService` 新增 `fillIntroImageUrls(CarModelEntity)`：解析 `introImages` JSON → 对每个元素：
  - 纯数字 → `imageService.publicUrl(id)`（复用 `:304-310`，内含 storageKey 校验）。
  - 以 `http` 开头（**存量旧数据为 URL 数组**）→ 原样保留。
  - 解析失败/图片不存在 → 跳过该元素（不阻断列表）。
- 在 `list()` 与 `detail()` 返回前调用填充。`detail()` 的 `CarModelDetailDto.model` 同法填充。
- 前端 `CarLibrary.vue` 的 `thumbUrl` 改用 `row.introImageUrls?.[0]`；`CarDetail.vue` 可选择性展示（本子任务至少修列表缩略图）。

### 兼容
- 存量 URL 数组与新 id 数组**双读兼容**，无需数据迁移脚本。
- 未来若全部车型重新同步，自然收敛为 id 数组。

## 3. R2 — 删除车型兜底清理

### 现状修正（探查结论需修正）
`CarModelService.delete()` `:187-196` 已调用 `docService.deleteByModel(id)`，而后者 `:121-129` **已物理删除每个 doc 的 embedding**（`embMapper.deleteByDocId`）。所以正常路径**无残留**。
**真实边界缺口**：`deleteByModel` 用 `docMapper.selectList(eq model_id)`（`@TableLogic` 自动过滤 `deleted=0`），若存在**已逻辑删除但 embedding 未清**的历史 doc，则漏清。

### 设计
- `CarDocEmbeddingMapper` 新增：
  ```java
  @Delete("DELETE FROM sparkora_car_doc_embedding WHERE model_id = #{modelId}")
  int deleteByModelId(@Param("modelId") Long modelId);
  ```
  （`sparkora_car_doc_embedding` 有 `model_id` 列，可一条 SQL 全清，无需 JOIN。）
- `CarModelService.delete()` 在 `docService.deleteByModel(id)` 后追加 `embStatsMapper.deleteByModelId(id)` 兜底。
- **图库引用**：`intro_images` 指向的是**全局图库**（`project_id=null`、`source=byd`、内容哈希去重共享）资产，**不随车型删除而删**（可能被其他车型/文章复用）；车型行删除即解除引用。设计上明确：**不删图库图**，避免误删共享资产。

## 4. R3 — 向量索引统一（HNSW）

### 现状
- 车型域 `idx_car_doc_emb_vec` = HNSW（`schema.sql:302-303`）。
- KB 域 `idx_kb_chunk_emb_vec` = IVFFLAT lists=100（`:368-369`）→ 不一致。

### 设计（幂等且不每次启动重建）
`schema.sql` 改为：
```sql
DROP INDEX IF EXISTS idx_kb_chunk_emb_vec;
CREATE INDEX IF NOT EXISTS idx_kb_chunk_emb_vec_hnsw ON sparkora_kb_chunk_embedding
    USING hnsw (embedding vector_cosine_ops);
```
- 首次启动：DROP 掉旧 IVFFLAT，建 HNSW。
- 后续启动：DROP 旧名是 no-op（已不存在），`CREATE ... IF NOT EXISTS` 新名已存在则跳过。**不会每次重建索引**。
- 新索引名 `_hnsw` 后缀用于区分，避免与旧名冲突。
- 注释同步更新说明。

### 风险
- pgvector HNSW 建索引在空表/小表上开销可忽略（KB 规模小）。无需停机。

## 5. R4 — 手动 + 定时增量同步

### 现状
- 手动：`CarSyncJobService.createJob(goodsIds, "SELECTED")` + `@Async runJob`，完整可用。
- `application.yml:94-95` 有 `sync-enabled`/`sync-cron`，但 `CarProperties` **未绑定这两个字段**，且**无 `@Scheduled` 消费者**（死配置）。

### 设计
- `CarProperties` 增字段：`private boolean syncEnabled = false;`、`private String syncCron = "0 0 3 * * ?";`（与 yml 对齐）。
- 新增 `CarSyncScheduler`（`com.sparkora.car.service`）：
  ```java
  @Scheduled(cron = "${sparkora.car.sync-cron:0 0 3 * * ?}")
  public void scheduledSync() {
      if (!props.isSyncEnabled()) return;           // 默认关闭
      if (jobService.hasRunning()) { log...; return; } // 防重叠
      List<String> goodsIds = catalog 全量 goodsId;
      if (goodsIds.isEmpty()) return;
      jobService.createJob(goodsIds, "SCHEDULED");   // createdBy 自动 "system"
  }
  ```
- `CarSyncJobService` 增 `hasRunning()`（`count(status=RUNNING) > 0`）；`job_type` 增加取值 `SCHEDULED`（schema 注释更新）。
- **增量语义**：以官网目录全量为准，逐车型幂等 upsert（既有能力），达到"新增车型自动入库 + 存量刷新"。默认低频（每天 3 点）且默认关闭，个人项目不空跑。
- `SparkoraApplication` 已有 `@EnableScheduling`，无需改。

## 6. R5 — 七牛转存验证（非代码）

- 运行一次单车型同步，检查：
  1. `sparkora_image_asset` 出现 `source='byd'` 记录；
  2. `storage_key` 非空；
  3. `ImageService.publicUrl(id)` 返回可公网访问 URL（curl 200）。
- 若七牛未配置（`QINIU_ENABLED=false` 或密钥缺失），`persistIntroImages` 单图失败仅告警不阻断（既有行为），验证记录为"未配置，跳过"。
- 该步骤在 check 阶段用联调环境执行，产出证据写回任务 notes。

## 7. 数据流（改动后）

```
官网 goodsInfo.introduce (URL 列表)
   └─ persistIntroImages ─▶ ImageService.persistOrReuse ─▶ 七牛 + image_asset(source=byd)
                          └─ car_model.intro_images = [assetId,...]
GET /car/models ─▶ CarModelService.list()
                          └─ fillIntroImageUrls: [assetId] ─▶ imageService.publicUrl ─▶ introImageUrls:[url]
前端 CarLibrary.vue ◀─ row.introImageUrls[0] 直接展示
```

## 8. 兼容与回滚

- `intro_images` 存储格式不变，仅新增派生字段 → 旧前端/旧接口调用不受影响。
- 索引：可回滚为 IVFFLAT（`DROP INDEX IF EXISTS idx_kb_chunk_emb_vec_hnsw; CREATE INDEX ... ivfflat ...`）。
- 定时同步默认关闭 → 无行为变化；回滚仅需移除 scheduler bean。
- 删除兜底清理是纯增量 SQL，无破坏性。

## 9. 影响文件

| 文件 | 改动 |
|---|---|
| `domain/entity/CarModelEntity.java` | +`introImageUrls` 非持久化字段 |
| `car/service/CarModelService.java` | +`fillIntroImageUrls`；`list()`/`detail()` 填充；`delete()` 兜底清 emb |
| `mapper/CarDocEmbeddingMapper.java` | +`deleteByModelId` |
| `config/CarProperties.java` | +`syncEnabled`/`syncCron` |
| `car/service/CarSyncScheduler.java` | 新增 |
| `car/service/CarSyncJobService.java` | +`hasRunning()` |
| `resources/db/schema.sql` | KB 索引 IVFFLAT→HNSW；sync_job 注释补 SCHEDULED |
| `frontend/src/views/CarLibrary.vue` | `thumbUrl` 改用 `introImageUrls` |
| `frontend/src/views/CarDetail.vue` | 可选展示车型图 |
| `.env.example` | 确认 `CAR_SYNC_ENABLED`/`CAR_SYNC_CRON` 已列（补注释） |
| `docs/s0-spec.md` | 车型库字段/同步说明同步 |
