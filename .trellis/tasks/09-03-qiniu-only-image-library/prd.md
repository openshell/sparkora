# 图库完全依赖七牛：上传/AI生成/比亚迪同步图片统一转存七牛

## Goal

图库完全依赖第三方图床（当前为七牛免费服务），本地不再作为图片主存储。三来源图片统一转存图床：
1. 比亚迪同步车型图片（`getGoodsInfoById` 的 `introduce` URL 列表）下载后转存图床；
2. 用户上传图片直接转存图床；
3. AI 生成图片（文生图/图生图）直接转存图床。

同时做**图床供应商通用化设计**，便于未来快速切换供应商（如阿里 OSS / 腾讯 COS / 自建 MinIO）。

## Confirmed Facts（代码现状）

- 图库表 `sparkora_image_asset`：三来源（upload / ai-text2img / ai-img2img）统一入库，`storage_path` 存本地相对路径，`qiniu_key` 存七牛 key（懒转存，空=未上床）。
- `QiniuService`：懒转存模式——图片先落本地 `{IMAGE_STORAGE_DIR}/yyyy/MM/uuid.ext`，预览/发布时才 `ensureUploaded()` 转存七牛；`publicUrl(key)` 实时拼 URL；删除时同步删七牛对象。
- `ImageService`：`store()` 落本地；`readLocalBytes()` 读本地；`fetchBytes()` 下载 AI 返回 URL/data URL；`/images/**` 由 `WebConfig` 静态映射到本地目录。
- 比亚迪同步 `CarModelService.syncOne`：`getGoodsInfoById` 的 `introduce`（图片 URL 列表）**只存 URL** 到 `car_model.intro_images`（JSON 数组），未下载、未转存图床。
- 配置：`.env` 的 `QINIU_*`（AK/SK/bucket/uploadHost/publicDomain/tokenTtl），`IMAGE_STORAGE_DIR`、`IMAGE_MAX_UPLOAD_MB`。

## Requirements

- R1：图床供应商抽象层（接口 + 配置），当前实现七牛，未来可切换其他供应商。
- R2：上传图片直接转存图床，本地不留。
- R3：AI 生成图片（文生图/图生图）直接转存图床，本地不留。
- R4：比亚迪同步车型图片下载后转存图床，作为独立来源（source='byd'）进图库表。
- R5：彻底移除本地存储机制（storage_path 字段、/images/** 静态映射、readLocalBytes）；历史已存本地的图片**不迁移**（历史数据作废或仅保留七牛 key 的图可用）。
- R6：删除图片时同步删除图床对象。
- R7：评估并给出其他功能建议（纳入本任务交付物）。

## Acceptance Criteria

- [ ] AC1：图床供应商抽象层存在，七牛为当前实现；切换供应商只需改配置 + 新增一个实现类，不改业务代码。
- [ ] AC2：上传图片后 `sparkora_image_asset` 记录含图床 key，本地无文件残留，前端用图床 URL 展示。
- [ ] AC3：AI 生成图片（文生图/图生图）入库即含图床 key，本地无文件残留。
- [ ] AC4：比亚迪同步车型时，`introduce` 图片下载并转存图床，写入 `sparkora_image_asset`（source='byd'），`car_model.intro_images` 关联到图库记录。
- [ ] AC5：本地存储机制（storage_path 字段、/images/** 静态映射、readLocalBytes）已移除；历史本地图不迁移。
- [ ] AC6：删除图片同步删除图床对象。
- [ ] AC7：功能建议清单已产出并评审。

## Out of Scope

- 历史已存本地图片的迁移（用户明确不迁移）。
- 多图床供应商的完整实现（仅抽象层 + 七牛实现；其他供应商留待未来）。
- 图床免费额度监控/告警（作为功能建议提出，不在本任务实现）。

## Key Decisions（用户已确认）

- 存储策略：**只存图床，本地不留**。
- 比亚迪图片：**进图库表，作为独立来源**（source='byd'）。
- 本地机制：**彻底移除**（storage_path 字段、/images/** 静态映射、readLocalBytes），历史图不迁移。
- 图床：**通用化设计**，便于切换供应商。

## 功能建议（R7 交付物，评估后产出）

基于代码现状（图库三来源统一入库、七牛懒转存、比亚迪图片仅存 URL）评估，建议以下功能（按优先级）：

1. **图床免费额度监控/告警**：七牛免费额度有限（存储/流量），建议接入七牛用量查询 API，在接近阈值时告警，避免超量计费。当前无任何监控。
2. **图片压缩/格式转换（webp 化）**：上传/AI 生成/比亚迪图片统一转 webp 或按需压缩，显著降低七牛存储与 CDN 流量成本。当前原图直传。
3. **图片去重**：相同内容（哈希比对）不重复上传，节省存储与流量。当前每次上传都新建记录。
4. **图库分类/标签管理**：按来源（upload/ai/byd）、项目、车型等维度打标签，前端可筛选。当前仅按 projectId 过滤。
5. **缩略图/懒加载**：图库网格用七牛 imageView2 生成缩略图，减少首屏流量。当前前端直接加载原图。
6. **图库搜索**：按文件名/prompt/来源关键字搜索。当前无搜索。
7. **比亚迪图片与车型关联展示**：车型详情页展示该车型的图库图（source='byd'），便于创作时选用。当前车型图片仅存 URL 未入库。
8. **图床切换的配置化**：`STORAGE_PROVIDER` 支持多供应商配置模板，切换时仅改配置 + 新增实现类（本任务已含抽象层，此条为后续落地其他供应商）。

> 注：1-6 为独立可交付功能，建议拆分为后续子任务；本任务仅产出建议清单，不实现。

## Open Questions

- 无（已收敛）。
