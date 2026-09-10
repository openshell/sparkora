# 设计：图库完全依赖图床（七牛），本地不留

## 架构与边界

### 图床供应商抽象层

新增接口 `ImageStorage`（图床存储抽象），当前实现 `QiniuImageStorage`（复用现有 `QiniuService` 的签名/上传/删除逻辑）。

```java
public interface ImageStorage {
    boolean configured();                       // 配置是否齐备
    String upload(byte[] bytes, String ext);    // 上传字节，返回图床 key
    String publicUrl(String key);               // 由 key 拼公网 URL
    void delete(String key);                    // 删除对象（非阻塞）
}
```

- 业务代码（`ImageService`、`CarModelService`、`PreviewService`、`PublishService`）只依赖 `ImageStorage` 接口，不直接依赖七牛。
- 切换供应商：新增一个 `ImageStorage` 实现类 + 改配置，业务代码零改动。
- 配置：`ImageStorageProperties`（或复用现有 `QiniuProperties` 抽象为通用 `sparkora.storage` 前缀），`@ConditionalOnProperty` 按 `STORAGE_PROVIDER=qiniu` 装配对应实现。

### 数据模型变更

`sparkora_image_asset`：
- 移除 `storage_path` 字段（本地路径不再需要）。
- 新增/保留 `storage_key`（图床 key，替代 `qiniu_key`，语义通用化）。
- 新增 `source='byd'` 来源。
- 保留 `project_id`（可空=全局图库）、`file_name`、`prompt_text`、`ref_image_id`、`width`、`height`、`created_by`、`created_at`。

`schema.sql` 幂等迁移：
- `ALTER TABLE sparkora_image_asset DROP COLUMN IF EXISTS storage_path;`
- `ALTER TABLE sparkora_image_asset RENAME COLUMN qiniu_key TO storage_key;`（或新增 `storage_key` 并保留 `qiniu_key` 兼容——建议直接 rename，历史图不迁移，旧 key 作废）

### 数据流

**上传**：`ImageController.upload` → `ImageService.upload` → 校验（魔数/大小/格式）→ `imageStorage.upload(bytes, ext)` 得 key → 入库（含 key）→ 返回。本地不落盘。

**AI 生成**：`ImageService.generateText2Image/generateImage2Image` → `aiImageClient` 返回 URL/data URL → `fetchBytes` 下载字节 → `imageStorage.upload` 得 key → 入库。本地不落盘。

**比亚迪同步**：`CarModelService.syncOne` → 对 `item.getIntroduce()` 每个 URL → `fetchBytes` 下载 → `imageStorage.upload` 得 key → 写入 `sparkora_image_asset`（source='byd'，project_id=null 全局）→ `car_model.intro_images` 存图库记录 id 列表（或存 key 列表）。失败不阻断整车型同步（单图失败仅告警）。

**删除**：`ImageService.delete` → 引用检查 → 删记录 + `imageStorage.delete(key)`（非阻塞）。

### 前端变更

- `ImageLibrary.vue`、`StepPreview.vue`、`StepPublish.vue`、`MarkdownEditor.vue` 中图片 URL 由 `/images/{storagePath}` 改为图床公网 URL（`storage_key` → `publicUrl`）。
- 后端返回的实体含 `storage_key`，前端拼 URL 或后端直接返回 `url` 字段。

### 兼容与迁移

- 历史已存本地的图片**不迁移**：`storage_path` 字段删除后，历史记录无图床 key，前端无法展示（作废）。用户明确接受。
- `qiniu_key` → `storage_key` rename：历史有 `qiniu_key` 的记录保留 key 值（若图床对象仍在则仍可访问），无 key 的作废。

## 权衡

- **彻底移除本地**：最干净，但历史图作废、`/images/**` 静态映射删除后旧 URL 失效。用户已接受。
- **通用化抽象**：增加一层接口，但满足「快速切换供应商」诉求，成本低。
- **比亚迪图片进图库表**：与上传/AI 图统一管理，但图库会混入车型素材；用 source='byd' 区分，前端可过滤。

## 回滚

- 保留 `QiniuService` 现有实现作为 `QiniuImageStorage` 内部逻辑，不破坏现有上传/删除能力。
- schema 变更用幂等 `ALTER TABLE`，可回退（重新加回 storage_path 列）。
