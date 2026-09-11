# Implement — C1 车型库数据基座加固

> 依赖：无。开工前已备 `prd.md` + `design.md`。

## 有序清单

1. **R3 索引统一（最独立，先做）**
   - [ ] `schema.sql`：`idx_kb_chunk_emb_vec` IVFFLAT → 新名 `idx_kb_chunk_emb_vec_hnsw` HNSW（`DROP INDEX IF EXISTS` + `CREATE INDEX IF NOT EXISTS`）。
   - [ ] 更新注释。

2. **R2 删除兜底清理**
   - [ ] `CarDocEmbeddingMapper` +`deleteByModelId(modelId)`（`@Delete`）。
   - [ ] `CarModelService.delete()` 追加 `embStatsMapper.deleteByModelId(id)`。
   - [ ] 注释说明「不删共享图库图」。

3. **R1 intro_images 语义统一**
   - [ ] `CarModelEntity` +`@TableField(exist=false) List<String> introImageUrls`。
   - [ ] `CarModelService.fillIntroImageUrls(model)`：数字→`imageService.publicUrl`，http→原样，异常跳过。
   - [ ] `list()` / `detail()` 返回前填充（detail 的 model 也要填）。
   - [ ] `CarLibrary.vue` `thumbUrl` 改用 `row.introImageUrls?.[0]`。
   - [ ] （可选）`CarDetail.vue` 展示车型图。

4. **R4 定时增量同步**
   - [ ] `CarProperties` +`syncEnabled`/`syncCron`。
   - [ ] `CarSyncJobService` +`hasRunning()`。
   - [ ] 新增 `CarSyncScheduler`（`@Scheduled`，默认关闭，防重叠，jobType=SCHEDULED）。
   - [ ] `schema.sql` sync_job 注释补 `SCHEDULED`。

5. **R5 七牛转存验证**
   - [ ] 联调环境跑单车型同步；查 `image_asset(source=byd)` + `publicUrl` 可访问。

6. **文档**
   - [ ] `.env.example` 确认/补 `CAR_SYNC_ENABLED`/`CAR_SYNC_CRON` 注释。
   - [ ] `docs/s0-spec.md` 车型库字段与同步说明。

## 验证命令

```bash
mvn -q -DskipTests compile           # 后端编译（只读 maven 仓库时加 -Dmaven.repo.local=/tmp/m2repo）
npm run build                        # 前端（frontend/）
./dev.sh restart backend             # 联调重启
./dev.sh logs backend -f             # 看日志
```

## 回滚点

- 索引：`DROP INDEX IF EXISTS idx_kb_chunk_emb_vec_hnsw; CREATE INDEX idx_kb_chunk_emb_vec USING ivfflat (embedding vector_cosine_ops) WITH (lists=100);`
- 定时：默认关闭；移除 `CarSyncScheduler` 即回滚。
- R1/R2：纯增量（新字段/新 SQL），无破坏性，回滚删除对应代码即可。

## 开工前检查

- [x] `prd.md` / `design.md` 已写。
- [ ] `implement.jsonl` / `check.jsonl` curate（spec/research）。
- [ ] `task.py start` 后进入实现。
