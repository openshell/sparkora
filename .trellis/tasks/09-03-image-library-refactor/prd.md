# 图库系统性重构：检索组织 / 性能成本 / AI 生成体验

## Goal

图库（`sparkora_image_asset` + `ImageLibrary.vue` + 配图抽屉）当前只有「上传/生成/浏览/删除」基本能力，按三个方向系统性增强，让创作时选图更快、图床流量更省、AI 生成更可控：

1. **检索与组织**：后端分页 + 结构化筛选（来源/项目/关键字），替代「全量拉取 + 前端过滤」；
2. **性能与成本**：七牛 imageView2 缩略图与 webp 交付转换、内容哈希去重；
3. **AI 生成体验**：生成参数留档、一键重生成、同 prompt 批量生成 N 张候选。

## Confirmed Facts（代码现状，已核实）

- 表 `sparkora_image_asset`（schema.sql:103）：`project_id`(可空=全局)、`file_name`、`source`(upload/ai-text2img/ai-img2img/byd)、`prompt_text`(截断2000)、`ref_image_id`、`width/height`、`storage_key`；**无哈希列、无生成模型/尺寸列**。
- 后端 `ImageService.list()`(ImageService.java:165)：仅 `projectId` 过滤，`orderByDesc(id)`，无分页；`projectImages()`(ImageService.java:217) 快照 images 为**全量图库**。
- 快照消费方两处：`StepPreview.vue:286`（抽屉选图网格 + 封面/插图回显）、`StepPublish.vue:209`（用 `images.find` 解析封面 URL + bodyImageIds）。
- 接口（ImageController.java）：`GET /api/images` 仅 `?projectId=`；`generate-text/from-image` 用裸 `Map<String,Object>` 解析（无 @Valid DTO）。
- 前端 `ImageLibrary.vue`：`load()` 一次拉全量 + `matchFilter()` 前端过滤；网格直载原图 `img.url`。
- `QiniuService`：key=`sparkora/{uuid}.{ext}`，`publicUrl(key)` 实时拼域名（QiniuProperties.publicUrl:36）；**未用 imageView2 任何派生参数**；签名直传不引 SDK。
- `AiImageClient`：多模型按序轮询，文生图/图生图 `n` 硬编码 1；`parseFirstUrl` 只取 `data[0]`；**成功后不返回实际命中的模型名**。
- 分页基建现成：`PageResult<T>(rows,total,page,size)`（domain/dto/PageResult.java）+ MyBatis-Plus `Page`（ArticleProjectController.java:70 有先例）。
- BYD 来源入库点：`CarModelService.persistIntroImages`（下载 introduce URL 列表后 `ImageService` 入库，单图失败不阻断）。
- spec §10（docs/s0-spec.md:325-334）为字段级契约权威；改动需 schema.sql + entity + spec 三处同步。
- 权限模型：图库读=三角色，写=ADMIN/EDITOR。

## Requirements

- R1 检索与组织：`GET /api/images` 支持 `page/size/source/keyword/projectId` 组合查询（keyword 命中 file_name/prompt_text），响应改 `PageResult`（含 total）；`ImageLibrary.vue` 全部筛选走服务端。配图抽屉选择网格同步分页化（同接口）。
- R2 快照语义重构：`GET /api/projects/{id}/images` 的 `images` 从「全量图库」收缩为「当前版本引用的图」（封面+插图），并新增服务端解析的 `coverImage`/`bodyImages` 字段；抽屉选图改走分页接口。StepPublish 消费方兼容。
- R3 性能与成本：缩略图统一走七牛派生 URL（imageView2 尺寸参数 + format/webp），原图仅大图预览加载；入库管线（upload/文生图/图生图/BYD）计算 SHA-256 内容哈希，命中已有记录时**不重复上传图床**、返回已有记录并提示「复用」。
- R4 AI 生成体验：入库记录留档实际命中的模型（gen_model）与尺寸（gen_size）；`POST /api/images/{id}/regenerate` 用同 prompt/size 重生成新图；`generate-text/from-image` 支持 `n`(1~4) 批量生成候选，逐张入库返回列表。
- R5 契约同步：schema.sql（幂等 ALTER）+ entity + spec §10 三处同步；接口对旧字段向后兼容。

## Acceptance Criteria

- [ ] AC1：`GET /api/images` 支持分页与 source/keyword/projectId 组合筛选，响应 `{rows,total,page,size}`；ImageLibrary 前端不再做客户端过滤。
- [ ] AC2：图库网格与配图抽屉缩略图 URL 带 imageView2 参数（缩放+webp），大图预览仍用原图 URL。
- [ ] AC3：重复内容入库时不产生新图床对象，响应复用已有记录且前端有明确提示。
- [ ] AC4：AI 生成图可查到 gen_model/gen_size，且能一键同参数重生成（regenerate 接口）。
- [ ] AC5：同 prompt 可一次生成 n 张（n≤4），逐张入库并全部返回。
- [ ] AC6：快照接口 images 收缩为引用图后，StepPublish 封面/插图摘要与 StepPreview 已选状态展示不回归。
- [ ] AC7：spec §10 与实现一致（sparkora-spec-check 通过）；`mvn -q -DskipTests compile` 与 `npm run build` 通过。

## Key Decisions（用户已确认）

- **去重策略**：命中相同内容哈希 → 不上传图床、返回已有记录、前端提示复用（dec-b79cd602a33fb794）。
- **评估项**：webp 压缩与批量生成**两项都纳入实现**（dec-99db418b9030d261）。
- **抽屉口径**：配图抽屉跟随分页化，与图库页同源检索（dec-99db418b9030d261）。
- **任务形态**：单个复杂任务、三个里程碑（M1 检索 → M2 性能成本 → M3 AI 体验），不拆父子任务（共享一次 schema 迁移与一次契约改写，拆分会带来三倍流程开销）。

## Technical Notes

- webp 落地在**交付层**（imageView2/2/w/{宽}/format/webp），不入库转码——Java 无原生 webp 编码器，引原生库或走七牛 pfop 异步转码成本都高于收益；交付层转换即可吃到 CDN 流量收益。入库转码列为非目标。
- 批量生成 `n` 走 OpenAI images 标准参数；`AiImageClient` 需返回「URL 列表 + 实际模型名」，provider 少返时按实际数量入库（优雅降级）。
- 快照 `images` 收缩为引用图后，`StepPublish` 的 `images.find(cover)` 逻辑天然兼容（封面必在引用图内）；`StepPreview` 抽屉选图网格改调分页接口。

## Out of Scope

- 标签/分类自由管理体系（建议清单第 4 条）。
- 图床用量监控告警（建议清单第 1 条）。
- 存量图片哈希回填批量任务（仅新增入库查重）。
- 入库时 webp 转码（Java 编码器缺失，收益不抵成本；webp 收益由交付层达成）。
- 历史本地图迁移（S6 已定不迁移）。

## Open Questions

- 无（已全部收敛）。