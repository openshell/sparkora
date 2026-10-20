# 小程序文章媒体推送技术设计

> 任务：`10-20-miniapp-media-push`。本文只描述目标设计与边界，不代表已实施。

## 1. 目标与不变量

目标：把 `byd_miniapp` 的图片、视频从“公众号图床 + payload v1”切换为“Sparkora BYD 暂存 → 本机 Agent → 比亚迪 CDN → 王朝小程序草稿”。

必须保持的不变量：

1. `gzh` 项目的七牛上传、wenyan-server 发布、四步工作台完全不变。
2. BYD 草稿只由本机 Agent 写入，Server 不直接操作 MMKV。
3. 最终草稿只保存比亚迪 CDN 的 `attachment` / `mediapreview`，不保存七牛 URL。
4. 任一媒体失败时，Agent 不写 MMKV；已有备份/恢复/精确校验机制继续生效。
5. 正文媒体顺序必须与 Markdown 中出现顺序一致。

## 2. 当前链路与缺陷定位

### 2.1 当前链路

```text
BydEditorStep.vue
  -> MarkdownEditor.vue（复用公众号编辑器）
  -> usePendingImageFlush.js
  -> imageApi.upload() / POST /api/images/upload
  -> 七牛
  -> Markdown 中的七牛 URL
  -> BydDynastyPushService payload v1（text/img + 封面注入）
  -> Agent 再下载 URL、上传比亚迪 CDN
  -> MMKV
```

### 2.2 目标链路

```text
BydEditorStep.vue
  -> MarkdownEditor(mediaMode='byd')
  -> 图片/视频暂存到 POST /api/projects/{id}/byd-dynasty/media
  -> Markdown 写入 sparkora-byd-media:<id> / sparkora-byd-video:<id>
  -> BydDynastyPushService payload v2（按顺序的 text/img/video + media 清单）
  -> Agent 通过带 X-Agent-Token 的任务范围接口下载暂存文件
  -> Agent 上传比亚迪 CDN（图片 mediaType=image；视频 mediaType=video + 宽高）
  -> Agent 生成 sibling 空文本和视频 attachment|mediapreview|duration 消息
  -> MMKV 草稿 + 备份 + 精确校验
```

### 2.3 关键缺陷

| 位置 | 缺陷 | 目标 |
| --- | --- | --- |
| `BydEditorStep.vue` | 推送前统一 `usePendingImageFlush`，图片进入七牛 | BYD 模式直接上传 BYD 暂存，不调用 imageApi.upload |
| `MarkdownEditor.vue` | 只有公众号粘贴图行为，无视频入口 | 增加显式 `mediaMode` / 媒体上传回调，默认 gzh 行为不变 |
| `BydDynastyPushService` | payload v1、封面注入、只支持 text/img | payload v2、媒体清单、text/img/video、无封面注入 |
| `BydDynastyPlainTextRenderer` | 只区分文本和图片 URL | 识别 BYD 图片/视频 token，产出有序 MediaBlock |
| `requireProject` | `gzh` 仍可走部分 BYD 路径 | 所有 BYD 入口先校验 channel |
| Agent | 只支持图片和 text/img，无视频 | 通用媒体下载/校验/上传、视频消息编译 |
| 前端推送页 | 只识别公众号 token/图片数 | 识别 BYD token，展示图片/视频数量并阻断未解析媒体 |

## 3. 数据模型

新增 Flyway `V25__byd_dynasty_media.sql`（版本号在实施时以仓库当前最大版本为准）：

```sql
CREATE TABLE IF NOT EXISTS sparkora_byd_dynasty_media (
    id                BIGSERIAL PRIMARY KEY,
    media_id          VARCHAR(64)  NOT NULL,
    project_id        BIGINT       NOT NULL,
    media_type        VARCHAR(10)  NOT NULL, -- IMAGE / VIDEO
    source_kind       VARCHAR(10)  NOT NULL, -- STAGED / URL
    storage_state     VARCHAR(20)  NOT NULL DEFAULT 'READY', -- PENDING / READY / FAILED
    storage_path      VARCHAR(500),
    source_url        TEXT,
    file_name         VARCHAR(255) NOT NULL,
    mime_type         VARCHAR(100) NOT NULL,
    byte_size         BIGINT       NOT NULL,
    width             INTEGER,
    height            INTEGER,
    duration_seconds  NUMERIC(8,3),
    created_by        BIGINT       NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at        TIMESTAMP    NOT NULL,
    deleted           SMALLINT     NOT NULL DEFAULT 0
);
```

索引：

```sql
CREATE UNIQUE INDEX IF NOT EXISTS uq_byd_media_media_id
    ON sparkora_byd_dynasty_media(media_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_byd_media_project
    ON sparkora_byd_dynasty_media(project_id, media_type, deleted);
CREATE INDEX IF NOT EXISTS idx_byd_media_expiry
    ON sparkora_byd_dynasty_media(expires_at, storage_state, deleted);
```

约束规则：

- `STAGED` 媒体必须有 `storage_path`，不能有 `source_url`。
- `URL` 媒体必须有 `source_url`，不能有 `storage_path`。
- 图片必须填写宽高；视频必须填写宽高和时长。
- 文件大小、格式校验在服务层和 Agent 双重执行，数据库字段不是唯一防线。

### 3.1 存储布局

- 根目录：`${BYD_MEDIA_STORAGE_DIR:./data/byd-staging}`。
- 路径：`{projectId}/{mediaId}.{ext}`，扩展名只由服务端根据 MIME/魔数确定，不使用原始文件名做路径拼接。
- 写入：先写 `{mediaId}.part`，校验大小和格式后 `Files.move(..., ATOMIC_MOVE)`；失败删除临时文件。
- 读取：只允许通过任务范围的受控下载接口；不要把目录暴露为静态资源。
- 生命周期：默认 `BYD_MEDIA_TTL_HOURS=168`（7 天）；创建 job 时把任务引用媒体的 `expires_at` 延长到至少 `now + 72h`。定时清理过期且不在任务保护窗口内的记录/文件。

## 4. 接口契约

### 4.1 用户侧 BYD 媒体接口

均要求 JWT、ADMIN/EDITOR（读取元数据可用三角色），并校验 `project.channel == byd_miniapp`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/projects/{projectId}/byd-dynasty/media` | 预留媒体元数据，返回 `mediaId`、`uploadPath` |
| `PUT` | `/api/projects/{projectId}/byd-dynasty/media/{mediaId}/content` | 原始字节流写入暂存文件；`Content-Length` 必须存在或由受限流计数校验 |
| `POST` | `/api/projects/{projectId}/byd-dynasty/media/from-image` | 将现有图库图片注册为 `source_kind=URL` 的 BYD 媒体 |
| `GET` | `/api/projects/{projectId}/byd-dynasty/media/{mediaId}` | 查询媒体准备状态 |
| `DELETE` | `/api/projects/{projectId}/byd-dynasty/media/{mediaId}` | 用户删除占位后的尽力清理；失败不影响正文保存 |

`POST /media` 请求示例：

```json
{
  "mediaType": "VIDEO",
  "fileName": "test.mp4",
  "mimeType": "video/mp4",
  "byteSize": 12345678,
  "width": 1080,
  "height": 1920,
  "durationSeconds": 12.3
}
```

返回：

```json
{
  "code": 0,
  "data": {
    "mediaId": "7d0c...",
    "mediaType": "VIDEO",
    "uploadPath": "/api/projects/42/byd-dynasty/media/7d0c.../content"
  }
}
```

### 4.2 Agent 下载接口

```text
GET /api/agent/byd-dynasty/jobs/{jobId}/media/{mediaId}
X-Agent-Token: <BYD agent token>
```

校验顺序：

1. Agent Token 有效。
2. job 存在、属于该 Agent、状态为 `CLAIMED` / `RUNNING`。
3. job payload 的 media 清单确实引用该 `mediaId`。
4. 媒体属于 job.project_id、未过期、状态 `READY`。
5. 流式返回文件并设置 `Content-Length`、`Content-Type`、安全文件名头。

任务范围路径用于避免持有全局 Agent Token 的进程任意下载其他项目媒体。Agent 不直接信任 payload 中的绝对外部 URL；`STAGED` 媒体只允许通过此路径下载，`URL` 媒体只用于兼容已注册的历史图库图片。

### 4.3 上传限制

| 媒体 | 限制 | 配置 |
| --- | --- | --- |
| 图片 | ≤20MB；JPEG/PNG/WebP 等光栅格式；Agent 可转 JPEG | `BYD_MEDIA_MAX_IMAGE_MB=20` |
| 视频 | ≤300MB；仅 MP4；2–120 秒 | `BYD_MEDIA_MAX_VIDEO_MB=300` |
| 视频数量 | 每篇文章最多 3 个 | `BYD_MEDIA_MAX_VIDEOS=3` |
| 请求体 | 视频原始流上限 310MB | Nginx/Spring 路由级配置 |

Nginx 只对 BYD 原始流上传 location 放宽 `client_max_body_size` 并关闭 `proxy_request_buffering`；不得把全局 `/api/` 上限无脑提升到 310MB。Spring 不使用 `MultipartFile` 承载大视频，Controller 直接把请求 InputStream 交给服务层受限流式写入。

## 5. Markdown 媒体引用

新增前端工具 `frontend/src/utils/bydMediaRefs.js`，作为 BYD token 的唯一解析口径：

| 媒体 | Markdown |
| --- | --- |
| 图片 | `![图片](sparkora-byd-media:<mediaId>)` |
| 视频 | `![视频](sparkora-byd-video:<mediaId>)` |

解析结果：

```js
{
  imageIds: ['...'],
  videoIds: ['...'],
  unresolvedImageTokens: [],
  unresolvedVideoTokens: [],
  hasAny: true
}
```

规则：

- 只识别目标前缀严格匹配的 token；不要用公众号 `sparkora-img:`。
- 同一媒体多次出现按首次出现顺序计数；推送消息仍按每次出现生成。
- `sparkora-img:` 出现在 BYD 正文时直接视为错误占位，提示重新插入或删除。
- 视频 token 数量超过 3 时前端先阻断，服务端仍必须二次校验。
- 推送页和编辑器统计共用同一解析器，避免“界面说有图但 payload 没有”的口径漂移。

预览处理：

- MarkdownEditor 在 BYD 模式不渲染富文本预览，只显示 token 占位文本。
- 如需缩略图，使用任务媒体元数据接口获取受控预览 URL；不得把七牛 URL 写回 token。

## 6. Renderer 与 payload v2

### 6.1 Renderer 输出

`BydDynastyPlainTextRenderer` 保持现有 CommonMark 清洗规则，新增媒体块：

```java
sealed interface Block permits TextBlock, ImageBlock, VideoBlock
record TextBlock(String text)
record ImageBlock(String target)
record VideoBlock(String target)
```

`compile(markdown)` 对图片 destination 分类：

| destination | 结果 |
| --- | --- |
| `sparkora-byd-media:<id>` | `ImageBlock(id)` |
| `sparkora-byd-video:<id>` | `VideoBlock(id)` |
| `http://` / `https://` | legacy `ImageBlock(url)` |
| `sparkora-img:` | 抛中文异常，禁止公众号暂存 token 混入 BYD |
| 其他相对路径/空目标 | 抛中文异常 |

文本节点仍按现有规则清洗；图片/视频替代文字不进入文本；HTML script/style 等继续整体删除。

### 6.2 payload v2

```json
{
  "payloadVersion": 2,
  "title": "清洗后的标题",
  "messages": [
    {"type": "text", "content": "第一段"},
    {"type": "img", "mediaRef": "MEDIA-1"},
    {"type": "text", "content": "第二段"},
    {"type": "video", "mediaRef": "MEDIA-2"}
  ],
  "media": [
    {
      "id": "MEDIA-1",
      "mediaId": "8c...",
      "type": "image",
      "sourceType": "STAGED",
      "downloadPath": "/api/agent/byd-dynasty/jobs/<jobId>/media/8c...",
      "fileName": "photo.jpg",
      "mimeType": "image/jpeg",
      "byteSize": 345678,
      "width": 1200,
      "height": 800
    },
    {
      "id": "MEDIA-2",
      "mediaId": "9d...",
      "type": "video",
      "sourceType": "STAGED",
      "downloadPath": "/api/agent/byd-dynasty/jobs/<jobId>/media/9d...",
      "fileName": "clip.mp4",
      "mimeType": "video/mp4",
      "byteSize": 12345678,
      "width": 1080,
      "height": 1920,
      "durationSeconds": 12.3
    }
  ]
}
```

约定：

- `messages` 中 `mediaRef` 引用 `media[].id`；`media[].mediaId` 是暂存记录主键，Agent 用它下载。
- `sourceType=URL` 的条目不包含 `downloadPath`，包含经过服务端校验的 `url`；仅用于历史图库图片兼容。
- 不包含 `cover` / `COVER-*`；版本封面字段不参与 BYD payload。
- `title` 继续使用现有 renderer 清洗结果。
- payload 中不携带 Agent Token、七牛密钥或比亚迪签名密钥。

### 6.3 创建任务校验矩阵

| 场景 | 结果 |
| --- | --- |
| 项目不是 `byd_miniapp` | 400「仅比亚迪王朝小程序项目可执行该操作」 |
| 无当前版本/正文为空 | 现有中文错误，不落 job |
| `sparkora-img:` token | 400「正文含公众号未上传图片占位，请删除或重新插入」 |
| BYD token 不存在 | 400「正文引用的媒体不存在或已过期」 |
| 媒体属于其他项目 | 400「正文媒体不属于当前项目」 |
| 媒体类型与 token 不一致 | 400「正文媒体类型不匹配」 |
| 视频数 > 3 | 400「小程序文章最多支持 3 个视频」 |
| 视频格式/时长/大小不合法 | 400，不落 job |
| 正文没有任何可发送内容 | 现有「正文内容为空」错误 |
| 仅图片/仅视频 | 允许创建任务 |
| 版本存在封面 | 忽略，不注入消息 |

## 7. Agent 设计

### 7.1 payload 与编译

- 新增 `PAYLOAD_VERSION = 2`；新建 job 只发送 v2。
- Agent 保留一版 v1 读取路径，用于升级期间排空旧 PENDING/CLAIMED 任务；v1 不允许视频。
- `compile_messages(payload, config)` 按顺序遍历 `messages`：
  - `text`：生成 `{msg_type:"text", msg_content:<content>, id:<nonce>}`。
  - `img`：下载媒体 → 校验 → 上传 BYD → `msg_content=attachment` → 生成图片消息。
  - `video`：下载媒体 → 校验 MP4/时长/大小 → 上传 BYD（`mediaType=video` + 宽高）→ 生成视频消息。
- 每个图片/视频消息后追加 sibling 空文本：

```json
{"msg_type":"text","msg_style":{"sibling":"<media-message-id>"},"msg_content":"","id":"<nonce>"}
```

图片消息：

```json
{"msg_type":"img","msg_content":"<attachment>","id":"<nonce>"}
```

视频消息：

```json
{
  "msg_type":"video",
  "msg_content":"<attachment>|<mediapreview>|<duration>",
  "src":"<attachment>",
  "cover":"<mediapreview>",
  "id":"<nonce>",
  "loadingImg":false,
  "uploadError":false
}
```

### 7.2 上传

复用比亚迪接口：

```text
POST https://common-upload.bydauto.com/dynasty/App/ForUpload/upload
multipart field: buffer
```

图片字段：`module=xxl_platform`、`mediaType=image`、`session_id`；Agent 先压缩为 JPEG（最大边 1920、质量 88）。

视频字段：`module=xxl_platform`、`mediaType=video`、`height`、`width`、`session_id`；保持 MP4 原文件，不转码。

响应必须包含：

- 图片：至少 `attachment`。
- 视频：`attachment` 和 `mediapreview`；缺一即失败。

上传失败/返回非法字段/视频元数据无法确认时，抛 `AgentError`，中止整个草稿写入。

### 7.3 视频校验

Agent 不只信任前端元数据：

- 文件魔数必须为 MP4（`ftyp` box）。
- 文件名扩展名必须为 `.mp4`。
- 文件大小 ≤300MB。
- 使用轻量 MP4 box 解析读取 `mvhd` 时长和 `tkhd` 宽高；解析失败直接拒绝。
- 时长必须在 2–120 秒；宽高必须为正整数。
- 不自动裁切/转码；超出限制提示用户先处理视频。

### 7.4 原子性

沿用现有 `write_platform_drafts` / `_verify_bytes`：

1. 下载并上传全部媒体，构造完整 `message`。
2. 备份 MMKV 主文件和 crc。
3. 写入新平台草稿。
4. 重新解析并精确比较 `message`（含 sibling、视频三段 `msg_content`、字段顺序等价性）。
5. 校验失败恢复备份，不报告成功。

任一媒体失败发生在第 1 步，禁止进入写入步骤。

## 8. 前端设计

### 8.1 MarkdownEditor 兼容扩展

给共享 `MarkdownEditor.vue` 增加显式模式/回调：

```text
mediaMode: 'gzh' | 'byd'  (default: 'gzh')
mediaUploadHandler: optional async (file, kind) => token
```

- 默认 `gzh`：现有粘贴图、`sparkora-img:`、`pendingImageStore` 行为完全不变。
- `byd`：粘贴图片由外部回调上传 BYD 暂存，成功后插入 `sparkora-byd-media:<id>`；不再调用公众号 pending store。
- 不要在 `MarkdownEditor` 内部直接依赖 BYD API，保持组件可复用；由 `BydEditorStep` 注入上传函数。

### 8.2 BYD 编辑步

`BydEditorStep.vue`：

- 移除 `usePendingImageFlush` 在 BYD 路径上的调用。
- 增加“插入视频”按钮/file input（`accept="video/mp4"`）。
- 选择视频后读取 `HTMLVideoElement.duration` / `videoWidth` / `videoHeight`，前端做第一层校验，再走暂存上传。
- 上传中显示进度/禁用推送；插入 token 后立即保存正文。
- 文案改为“图片和视频由本机 Agent 上传比亚迪 CDN”，删除“统一上传图床”。
- 不再展示/处理“设为封面”；`PreviewImageDrawer` 的 cover 相关 UI 在 BYD 模式隐藏或禁用。
- 页面重载后 BYD token 可继续解析（因为媒体元数据持久化）；只有缺失/过期记录显示失效警示。

### 8.3 配图抽屉

- 插入已有图库图片：调用 `from-image` 注册 BYD 媒体，成功后插入 BYD 图片 token，不直接写入七牛 URL。
- AI 生图仍可复用现有生成能力，但生成结果必须先注册/暂存为 BYD 媒体再插入。
- 删除图片占位时 best-effort 调 `DELETE`；即使清理失败，过期任务也会兜底。

### 8.4 推送步

`BydPushStep.vue` / `BydDynastyPanel.vue`：

- 使用 `bydMediaRefs` 统计文本、图片、视频数量。
- 显示“待准备/失效媒体”数量。
- 有未解析 token、视频数 >3 或暂存媒体未 READY 时禁用推送。
- 创建任务后显示服务端返回的媒体数量和失败原因；不要只显示笼统“推送失败”。

## 9. 渠道门禁

在 `BydDynastyPushService` 增加统一方法：

```java
private ArticleProjectEntity requireBydProject(Long projectId, boolean requirePublishable)
```

所有用户侧 BYD 入口调用它，不允许再以项目状态作为兜底放行。`gzh` 项目必须得到 400，错误文案明确指向“仅比亚迪王朝小程序项目”。

Agent API 不需要用户 JWT，但仍需 `X-Agent-Token`；下载接口额外校验 job 归属和 payload 引用。

## 10. 兼容、部署与回滚

### 10.1 兼容

- 新建/编辑/推送 `gzh`：代码路径、七牛上传、公众号 payload 不变。
- 已存在的 `byd_miniapp` 正文若包含七牛 URL：作为 legacy `URL` 媒体继续由 Agent 上传比亚迪 CDN；不要求在编辑时批量迁移。
- 已存在 `sparkora-img:` token：不允许创建 BYD job，要求用户重新插入，避免误上传公众号暂存图。
- Agent 升级期间保留 v1 读取路径，排空旧任务后可删除。

### 10.2 部署顺序

1. 部署 DB migration、Server、Nginx 视频流式上传配置。
2. 部署前端。
3. 升级 Windows Local Agent。
4. 确认 Agent 预检 `payloadVersion=2`、上传配置和 MMKV 路径正常。
5. 真机验证后再启用真实推送。

### 10.3 回滚

- 回滚 Server/前端到上一版本，保留 `V25` 表和 staging 目录（向后兼容，不影响 gzh）。
- Agent 回滚到 v1 前先停止领取新 job，并处理/丢弃 v2 pending job。
- 回滚不删除用户原有 `byd_dynasty_*` 字段和公众号数据。

## 11. 预期改动文件

后端：

- `src/main/resources/db/migration/V25__byd_dynasty_media.sql`
- `src/main/java/com/sparkora/domain/entity/BydDynastyMediaEntity.java`
- `src/main/java/com/sparkora/mapper/BydDynastyMediaMapper.java`
- `src/main/java/com/sparkora/service/BydDynastyMediaService.java`
- `src/main/java/com/sparkora/service/BydDynastyPlainTextRenderer.java`
- `src/main/java/com/sparkora/service/BydDynastyPushService.java`
- `src/main/java/com/sparkora/web/controller/ProjectBydDynastyMediaController.java`
- `src/main/java/com/sparkora/web/controller/BydDynastyAgentController.java`
- `src/main/java/com/sparkora/web/dto/*`（媒体预留/图片注册请求）
- `src/main/resources/application.yml`、`.env.example`、`docker-compose.yml`
- `frontend/nginx.conf.template`
- 相关 Service/Controller/Agent 测试

Agent：

- `agent/byd-dynasty/byd_local_agent.py`
- `agent/byd-dynasty/tests/test_byd_local_agent.py`
- `agent/byd-dynasty/README.md` / `config.example.env`

前端：

- `frontend/src/components/MarkdownEditor.vue`
- `frontend/src/views/project/BydEditorStep.vue`
- `frontend/src/views/project/BydPushStep.vue`
- `frontend/src/components/preview/PreviewImageDrawer.vue`
- `frontend/src/api/index.js`
- `frontend/src/utils/bydMediaRefs.js`（新增）
- `frontend/src/utils/bydMediaUpload.js`（新增，流式上传/进度）
- 相关页面/工具测试

文档：

- `.trellis/spec/backend/byd-miniapp-push.md`
- `docs/spec/channel.md`
- `.trellis/spec/frontend/index.md`

## 12. 测试策略

### 后端

- `BydDynastyPlainTextRendererTest`：text/image/video 顺序、BYD token、公众号 token 拒绝、legacy URL、HTML/表格/链接清洗。
- `BydDynastyPushServiceTest`：payload v2、媒体映射、无封面、视频数上限、跨项目/过期/类型错误、`gzh` 400。
- `BydDynastyMediaServiceTest`：预留、流式写入、原子落盘、大小限制、图片注册、过期清理、任务范围下载鉴权。
- Channel/Security 测试：所有 BYD 用户侧接口拒绝 `gzh`；Agent 下载拒绝未领取 job/非引用媒体。

### Agent

- 图片 `mediaType=image`、视频 `mediaType=video` + 宽高。
- 图片/视频 sibling 消息。
- 视频 `attachment|mediapreview|duration`。
- 媒体失败不写 MMKV、写入校验失败回滚。
- MP4 时长/宽高/大小校验。
- v1 兼容路径（排空旧任务）。

### 前端

- `bydMediaRefs` 解析、去重、顺序、失效 token、视频数量。
- 暂存上传成功后 token 插入；失败不插入。
- `mediaMode` 默认 gzh 行为不变。
- BYD 推送页阻断未解析媒体。
- `npm run build` 必须通过。

### 真机

- 带 1 张图片、带 1 个视频、图文视频混合、3 个视频、4 个视频拒绝、过期 token、`gzh` 项目误调接口。
- 打开王朝小程序草稿箱确认图片可显示、视频封面/播放正常、正文顺序正确。

## 13. 风险与取舍

| 风险 | 处理 |
| --- | --- |
| 300MB 视频导致内存峰值 | 原始流写磁盘、Nginx `proxy_request_buffering off`、Agent 流式下载、不整体读入内存 |
| Nginx 全局上限被放大 | 只对 BYD 上传 location 设置 310MB，其他 `/api/` 保持原限制 |
| Agent Token 被用于任意媒体下载 | 任务范围下载接口 + job 归属 + payload 引用校验 |
| 旧 v1 任务在升级后失败 | Agent 保留 v1 读取路径，部署前排空或兼容处理 |
| 前端 token 过期后无法恢复 | 媒体元数据持久化 + 推送页明确失效提示；不让用户静默丢图 |
| 视频元数据伪造 | Agent 用 MP4 box 解析复核，Server 和前端校验只作第一道防线 |
| 视频格式/编码兼容性差异 | 明确只支持 MP4，不自动转码；真机验收覆盖常见手机视频 |

## 14. 明确不做

- 不把公众号 `pendingImageStore` 扩展成通用媒体存储。
- 不修改公众号 `imageApi.upload` 的语义。
- 不在 Server 内调用比亚迪上传接口（必须由本机 Agent 完成）。
- 不把 `materials`、`surfacePlot` 写入文章草稿。
- 不在本任务中实现视频转码、裁剪、封面生成或社区发帖。
