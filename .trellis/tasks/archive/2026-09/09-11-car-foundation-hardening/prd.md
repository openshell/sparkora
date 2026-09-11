# C1 车型库数据基座加固（缺陷修复 + 图片/同步）

> 父任务：`09-11-knowledge-base-data-foundation`。依赖：无（最先执行）。

## Goal

保留现有车型采集/清洗/入库/同步能力，修复探查发现的既有缺陷，验证七牛图片转存链路，落地定时增量同步，为新闻域复用同步范式打好基础。

## Requirements

- **R1** `intro_images` 语义统一：明确为图库 `image_asset.id` 列表 JSON；前端展示经解析为 URL，不再直接当 URL 用。修 `CarLibrary.vue:161-166`。
- **R2** `CarModelService.delete` 级联清理：物理删除该车型 `sparkora_car_doc_embedding` 行（按 `model_id`）+ 处理图库引用，避免残留。
- **R3** 向量索引统一：`sparkora_kb_chunk_embedding` 由 IVFFLAT 改为 HNSW `vector_cosine_ops`（幂等 DROP+CREATE），与车型域一致。
- **R4** 同步触发：`sync-enabled`/`sync-cron` 落地为手动任务 + `@Scheduled` 定时增量（默认低频/关闭，避免空跑）。
- **R5** 七牛转存验证：跑一次车型同步，确认 `image_asset(source=byd)` 记录存在且 `publicUrl` 可公网访问。

## Acceptance Criteria

- [ ] AC1：车型同步可跑通（幂等、失败重试），支持手动与定时增量。
- [ ] AC2：`intro_images` 前后端语义一致，无 URL/id 混用。
- [ ] AC3：删除车型后无 `car_doc_embedding`/图库残留。
- [ ] AC4：KB 向量索引为 HNSW，与车型域一致。
- [ ] AC5：车型图片成功转存七牛云且公网 URL 可访问。
- [ ] AC6：`mvn -q -DskipTests compile` 与 `npm run build` 通过。

## Out of Scope

- 新闻域（C2）、问答（C4）。
- 更换图床实现（继续七牛）。

## Notes

- 复杂子任务：`task.py start` 前补 `design.md` + `implement.md`。
- 与父任务 `design.md` §3.3/§3.4 契约衔接。
