# 执行计划：图库完全依赖图床（七牛），本地不留

## 前置

- 任务状态：planning → 需用户批准最终规划总结后 `task.py start`。
- 分支：当前 `s4-preview-publish`。

## 实施清单（有序）

1. **图床抽象层**
   - 新增 `com.sparkora.storage.ImageStorage` 接口（configured/upload/publicUrl/delete）。
   - 新增 `com.sparkora.storage.QiniuImageStorage`，把 `QiniuService` 的签名/上传/删除逻辑迁入（或 `QiniuService` 实现该接口）。
   - 新增 `ImageStorageProperties`（通用 `sparkora.storage` 前缀，含 provider 选择），`@ConditionalOnProperty` 装配。
   - 验证：`mvn -q -DskipTests compile`。

2. **数据模型迁移**
   - `schema.sql`：`DROP COLUMN storage_path`、`RENAME qiniu_key → storage_key`。
   - `ImageAssetEntity`：删 `storagePath`，改 `qiniuKey` → `storageKey`。
   - `ImageAssetMapper` 同步。
   - 验证：`mvn -q -DskipTests compile`。

3. **ImageService 改造**
   - `upload`：校验后直接 `imageStorage.upload`，不再 `store()` 落本地。
   - `saveGenerated`：`fetchBytes` 后直接 `imageStorage.upload`，不再 `store()`。
   - `delete`：删记录 + `imageStorage.delete(key)`，移除本地文件删除逻辑。
   - 移除 `readLocalBytes`、`store`、`storageRoot` 依赖。
   - 验证：`mvn -q -DskipTests compile`。

4. **比亚迪同步图片转存**
   - `CarModelService.syncOne`：对 `item.getIntroduce()` 每个 URL 下载 → `imageStorage.upload` → 写 `sparkora_image_asset`（source='byd'）→ `car_model.intro_images` 存图库记录 id 列表。
   - 单图失败不阻断整车型同步（仅告警）。
   - 验证：`mvn -q -DskipTests compile`。

5. **预览/发布/前端 URL 改造**
   - `PreviewService`、`PublishService`：图片 URL 由本地 `/images/**` 改为图床公网 URL。
   - `ImageLibrary.vue`、`StepPreview.vue`、`StepPublish.vue`、`MarkdownEditor.vue`：`imgUrl` 用 `storageKey` 拼图床 URL。
   - 验证：`npm run build`。

6. **spec 文档同步**
   - `docs/s0-spec.md`：更新图库字段级表格（storage_path→storage_key、source 增 byd）、接口契约、七牛章节改为图床抽象层。
   - `.env.example`：更新 `QINIU_*` → `STORAGE_*`（或保留兼容）。

7. **功能建议清单**
   - 产出其他功能建议（见 prd R7），评审后纳入交付物。

## 验证命令

- 后端：`mvn -q -DskipTests compile`
- 前端：`npm run build`（在 frontend/ 下）
- 联调：`./dev.sh restart backend` + 手动验证上传/AI生成/比亚迪同步/预览/发布。

## 风险与回滚点

- **历史图作废**：`storage_path` 删除后历史本地图不可访问（用户已接受）。
- **schema rename**：`qiniu_key → storage_key` 用幂等 ALTER，可回退。
- **比亚迪图片下载**：依赖官网 URL 可达性，单图失败仅告警不阻断。
- 回滚：保留 `QiniuService` 现有逻辑作为 `QiniuImageStorage` 内部实现，不破坏现有能力。

## 完成前检查

- [ ] 后端编译通过。
- [ ] 前端构建通过。
- [ ] 上传/AI生成/比亚迪同步三来源均直接转存图床，本地无残留。
- [ ] 删除同步删图床对象。
- [ ] spec 文档与 .env.example 已同步。
- [ ] 功能建议清单已产出。
