# 执行计划：图库系统性重构

> 三个里程碑各自独立成 commit（回滚点）；每个里程碑完成后跑验证命令再进下一个。
> 前置条件：工作区 S6/RAG 未提交改动已由用户在另一会话先行提交（dec-d4ea4d0f856392b0），本任务在干净基线开工。

## M1 检索与组织（R1 + R2 快照 + R5 契约）

- [ ] 1. schema.sql 追加幂等迁移：`content_hash`/`gen_model`/`gen_size` 三列 + `idx_image_asset_hash`/`idx_image_asset_source` 两索引
- [ ] 2. `ImageAssetEntity` 补 `contentHash/genModel/genSize` 持久化字段 + `dedupeHit/url/thumbUrl` 非持久化字段（@TableField(exist=false) 同 url 先例）
- [ ] 3. `ImageService.list()` 重构：`(projectId, source 白名单校验, keyword ILIKE file_name/prompt_text, page, size)` → MyBatis-Plus `Page` → `PageResult`；列表 fillUrl + fillThumbUrl
- [ ] 4. `ImageController.list` 扩展查询参数；非法 source 400
- [ ] 5. 快照改写：`projectImages()` 移除全量 images，新增服务端解析 `coverImage`/`bodyImages`（按 bodyImageIds 顺序）
- [ ] 6. `StepPublish.vue` 封面 URL 改读 `coverImage.url`
- [ ] 7. `StepPreview.vue` 抽屉：回显改 coverImage/bodyImages；「从图库选择」网格改调分页接口 + 来源筛选 + 触底加载
- [ ] 8. `ImageLibrary.vue`：筛选条（来源下拉/关键字防抖/el-pagination size=24）、移除 matchFilter
- [ ] 9. `api/index.js` imageApi.list 参数扩展
- [ ] 10. 手测清单：图库筛选翻页、抽屉选图/回显、发布步封面摘要、BYD 来源过滤
- 验证：`mvn -q -DskipTests compile` + `npm run build` + 联调手测 10

## M2 性能与成本（R3）

- [ ] 1. `QiniuProperties.thumbUrl(key)`：publicUrl + `?imageView2/2/w/360/format/webp`
- [ ] 2. `ImageService.fillUrl` 同源产出 thumbUrl（`ObjectProvider<QiniuProperties>` 可选注入，空则降级 thumbUrl=url）
- [ ] 3. 入库管线统一去重：upload/saveGenerated/BYD persistIntroImages 均算 sha256 → 查命中 → 命中复用不传图床（dedupeHit=true）
- [ ] 4. `ImageLibrary.vue`/抽屉网格 src 改 thumbUrl，大图预览保留 url
- [ ] 5. 手测清单：同图重传提示复用且七牛对象数不增、网格流量走 imageView2、BYD 重跑不重复入库
- 验证：`mvn -q -DskipTests compile` + `npm run build` + 手测 5

## M3 AI 生成体验（R4）

- [ ] 1. `AiImageClient`：parseFirstUrl → parseAllUrls（data[].url/b64_json 全量），返回结构含实际命中模型名
- [ ] 2. `ImageGenDTO`（@Valid）替代裸 Map：prompt 必填、size 白名单、n 默认 1 上限 4
- [ ] 3. `ImageService.generateText2Image/generateImage2Image` 改批量：循环 n 次单张调用，单张失败跳过，全部失败抛 AiException；入库留档 gen_model/gen_size
- [ ] 4. `ImageController` generate 两接口响应改 `R<List<ImageAssetEntity>>`；新增 `POST /api/images/{id}/regenerate`
- [ ] 5. `StepPreview.vue` 抽屉：n 选择(1/2/4)、候选列表逐张选用、AI 图卡「重新生成」按钮
- [ ] 6. 手测清单：批量 2/4 张、单模型故障降级、重生成产新图不覆盖、gen_model/gen_size 落库可查
- 验证：`mvn -q -DskipTests compile` + `npm run build` + 手测 6

## 收尾（Phase 3）

- [ ] spec §10 + §12 契约表格改写（字段/接口/响应形状）；sparkora-spec-check 全量核对
- [ ] `.env.example` 无新增变量则跳过（thumbUrl 派生参数无配置项，硬编码在 QiniuProperties 内属七牛 API 语义）
- [ ] 提交拆分：`feat(S7): M1 检索与组织` / `feat(S7): M2 性能与成本` / `feat(S7): M3 AI 生成体验`（阶段号以规格为准，若定名 S7）

## 风险与回滚点

| 风险 | 缓解 |
|---|---|
| 快照语义改写破坏发布摘要/抽屉回显 | M1 内最后实施，先改 StepPublish（消费最浅）再改 StepPreview，逐一手测 |
| axonhub 兼容层对 edits/generations 行为差异 | M3 循环单张调用隔离失败；手测覆盖单模型故障场景 |
| 存量库无 content_hash | 新列可空，去重仅对新增生效（存量回填明确 out of scope） |
| 回滚 | 按里程碑 commit revert；schema 迁移幂等，revert 后旧代码兼容新列（可空不干扰） |