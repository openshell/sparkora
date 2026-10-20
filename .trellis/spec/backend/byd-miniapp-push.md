# BYD 王朝小程序推送契约（payload v2）

> Scope: `BydDynastyPushService`、BYD 媒体暂存、`BydDynastyPlainTextRenderer`、Windows Local Agent 消费 payload。
> 触发条件：修改小程序渠道判定、正文媒体、payload、Agent 下载/上传或 MMKV 草稿消息时，必须先读本文件。

## 1. 渠道不变量

- `gzh`：微信公众号，图片继续走七牛，发布继续走 wenyan-server。
- `byd_miniapp`：比亚迪王朝小程序，图片/视频先进入 Sparkora BYD 暂存，再由本机 Agent 上传比亚迪 CDN。
- 所有用户侧 BYD 接口（`state`、`createJob`、`getJob`、媒体接口）必须校验 `project.channel == byd_miniapp`；`gzh` 返回中文 400。
- 不得用 `gzh` 的 `VERSIONS_READY` / `PUBLISHED_DRAFT` 状态作为 BYD 放行条件。

## 2. 正文媒体 token

```text
图片：![图片](sparkora-byd-media:<mediaId>)
视频：![视频](sparkora-byd-video:<mediaId>)
```

- 公众号 `sparkora-img:` token 在 BYD 正文中一律拒绝。
- `http(s)` 图片 URL 仅作为历史正文兼容，最终仍由 Agent 下载并上传比亚迪 CDN。
- token 必须映射到当前项目、未过期、类型正确的暂存媒体。

## 3. Renderer 签名

```java
sealed interface Block permits TextBlock, ImageBlock, VideoBlock
record TextBlock(String text)
record ImageBlock(String target)   // BYD mediaId 或 legacy http(s) URL
record VideoBlock(String target)   // BYD video mediaId

static List<Block> BydDynastyPlainTextRenderer.compile(String markdown)
static String BydDynastyPlainTextRenderer.renderInline(String markdown)
```

规则：

- `text` 只允许纯文本、换行和 emoji；不得出现 Markdown/HTML 标记、链接目标或裸 URL。
- `img` / `video` 按正文出现顺序插入。
- 视频最多 3 个。
- 封面不参与编译，不得作为第一条消息注入。
- 原始 `ArticleVersionEntity.contentMd` / `title` 只读，清洗不得修改实体。

## 4. 媒体暂存

表：`sparkora_byd_dynasty_media`

关键字段：`media_id`、`project_id`、`media_type(IMAGE/VIDEO)`、`source_kind(STAGED/URL)`、`storage_state(PENDING/READY/FAILED)`、`storage_path`、`source_url`、`file_name`、`mime_type`、`byte_size`、`width`、`height`、`duration_seconds`、`created_by`、`expires_at`。

- 文件根目录：`BYD_MEDIA_STORAGE_DIR`，默认 `./data/byd-staging`。
- 图片 ≤20MB；视频 ≤300MB、仅 MP4、2–120 秒；每篇最多 3 个视频。
- 用户上传使用原始流 PUT，不把 300MB 视频整体读入 JVM 内存。
- 文件先写 `.part`，校验后原子 rename；路径不得由原始文件名拼接。
- 创建 job 时延长引用媒体有效期；过期媒体不得进入 payload。
- 清理任务只删除过期且不在任务保护窗口内的记录/文件。

## 5. payload v2

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
      "mediaId": "<uuid>",
      "type": "image",
      "sourceType": "STAGED",
      "downloadPath": "/api/agent/byd-dynasty/jobs/<jobId>/media/<mediaId>",
      "fileName": "photo.jpg",
      "mimeType": "image/jpeg",
      "byteSize": 345678,
      "width": 1200,
      "height": 800
    },
    {
      "id": "MEDIA-2",
      "mediaId": "<uuid>",
      "type": "video",
      "sourceType": "STAGED",
      "downloadPath": "/api/agent/byd-dynasty/jobs/<jobId>/media/<mediaId>",
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

- `STAGED` 媒体只通过任务范围下载接口交给 Agent；`URL` 媒体仅兼容历史图库图片。
- payload 不携带 Agent Token、七牛密钥或比亚迪签名密钥。
- 不包含 `cover` / `COVER-*`。
- `messages` 顺序即最终草稿顺序。

## 6. 草稿消息

图片：

```json
{"msg_type":"img","msg_content":"<attachment>","id":"<nonce>"}
{"msg_type":"text","msg_style":{"sibling":"<img-id>"},"msg_content":"","id":"<nonce>"}
```

视频：

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
{"msg_type":"text","msg_style":{"sibling":"<video-id>"},"msg_content":"","id":"<nonce>"}
```

- 图片和视频都必须保留 sibling 空文本到草稿保存。
- 视频消息不保存 `height` / `width`。
- 文章草稿不写 `materials`、不写 `surfacePlot`。
- 任一媒体下载/校验/上传失败时不得写 MMKV；写入后必须精确重新解析校验。

## 7. 比亚迪上传

```text
POST https://common-upload.bydauto.com/dynasty/App/ForUpload/upload
multipart field: buffer
```

- 图片：`module=xxl_platform`、`mediaType=image`、`session_id`；Agent 压缩 JPEG。
- 视频：`module=xxl_platform`、`mediaType=video`、`height`、`width`、`session_id`；保持 MP4，不转码。
- 图片响应至少需要 `attachment`；视频还需要 `mediapreview`，缺一即失败。

## 8. Validation / Error Matrix

| 场景 | 行为 |
| --- | --- |
| `channel != byd_miniapp` | 中文 400，不进入 BYD 链路 |
| 无当前版本/正文为空 | 中文 400，不落 job |
| `sparkora-img:` | 中文 400，提示删除或重新插入 |
| BYD token 未知/过期/跨项目 | 中文 400，不落 job |
| 媒体类型与 token 不匹配 | 中文 400 |
| 视频 >3 | 中文 400「小程序文章最多支持 3 个视频」 |
| 视频非 MP4 / <2s / >120s / >300MB | 中文 400 或 Agent 明确失败 |
| 仅文本/仅图片/仅视频 | 允许创建 payload |
| 版本有封面 | 忽略，不注入消息 |

## 8.1 Scenario: 固定圈子可选携带与回写

### 1. Scope / Trigger

修改 BYD 推送页圈子开关、`push-jobs` 请求、项目 `byd_dynasty_club_*` 审计字段或 Agent 草稿圈子字段时触发。用户可见业务对象是“圈子”；`clubId` / `clubTitle` 只是 BYD 草稿协议字段，不是两个独立业务选项。

### 2. Signatures

```text
GET  /api/projects/{id}/byd-dynasty/state
POST /api/projects/{id}/byd-dynasty/push-jobs
```

- `GET state` 权威字段：
  ```json
  {"circleConfigured": true, "circleId": "220", "circleTitle": "四川迪友会", "circleDisabledReason": null}
  ```
- 配置不完整时 `circleConfigured=false`、`circleId/circleTitle=null`，`circleDisabledReason` 明确提示服务端未配置完整圈子。
- 旧页面兼容字段：同一个固定圈的 `clubId` / `clubTitle`，以及 0 或 1 个 `clubTagOptions`。项目历史圈子不是第二个选项。
- 新 `POST push-jobs` 请求：
  ```json
  {"useCircle": true}
  ```
- payload v2 顶层继续保存 BYD 协议字段：
  ```json
  {"clubId": "220", "clubTitle": "四川迪友会"}
  ```
- 不携带圈子时两个 payload 字段都存在且为 `null`，用于与旧任务缺少字段的语义区分。

### 3. Contracts

- 固定圈子唯一来源是 `BydDynastyProperties.defaultClubId/defaultClubTitle`；二者都非空才算完整配置。
- `useCircle=true`：服务端读取固定圈子；前端提交的旧版 pair 若存在必须与固定圈子匹配。
- `useCircle=false`：创建显式空 pair；不得同时携带非空旧版 pair。
- `useCircle` 缺省：保留旧客户端兼容。`useClubTag=true` 的完整 pair、或仅提交的完整 pair，都必须匹配当前固定圈子；`useClubTag=false` 不得带非空 pair。没有选择字段时使用完整固定圈子，否则使用空 pair。
- 项目历史 `byd_dynasty_club_*` 只作最近一次成功任务审计，不参与 state 选项或创建请求解析。
- 成功 v2 任务将 payload 中实际圈子对回写项目；失败任务不修改；v1 成功任务缺少两个 key 时也不修改。
- Agent 必须区分“两个 key 都不存在”和“两个 key 都存在但为空”：
  - 都不存在：旧 v1 兼容，允许使用完整本地配置。
  - 都存在且为空：显式不携带圈子，禁止回退本地配置。
  - 只存在一个或只有一个非空：拒绝写 MMKV。

### 4. Validation & Error Matrix

| 条件 | 行为 |
| --- | --- |
| `useCircle=true` 且固定配置完整 | 使用服务端固定 pair |
| `useCircle=true` 且固定配置不完整 | 中文 400，不创建 job |
| `useCircle=false` | payload 的 `clubId` / `clubTitle` 均显式为 null |
| `useCircle=false` 且固定配置不完整 | 仍可创建不携带圈子的任务 |
| `useCircle=false` 但携带非空旧版 pair | 中文 400，不创建 job |
| `useCircle=true` 与旧版 pair 冲突 | 中文 400，不创建 job |
| 旧版 `useClubTag=true` 但 pair 不完整 | 中文 400，不创建 job |
| 旧版 pair 与固定圈子不一致 | 中文 400，不创建 job |
| 请求只提供 `clubId` 或只提供 `clubTitle` | 中文 400，不创建 job |
| v2 payload 的 pair key 存在但为空 | Agent 写入空圈子字段，不回退本地配置 |
| Agent 收到半套 payload 或半套本地配置 | 明确中文错误，不写 MMKV |

### 5. Good/Base/Bad Cases

- Good：配置为 `220 / 四川迪友会` 时，页面显示“圈子：四川迪友会”和默认开启的“携带圈子”开关。
- Base：关闭开关后 payload 仍包含两个空 key，Agent 写空圈子字段；成功回写会清空项目审计 pair。
- Bad：把项目历史圈子加入 state 选项，或逐字段从请求、项目、默认配置回退，产生过期或半套 pair。

### 6. Tests Required

- Java：固定圈子 state 字段、忽略项目历史、`useCircle` true/false、配置缺失、旧版 pair 匹配/冲突、半套 pair、无副作用失败、成功 v2 回写、v1 不回写。
- Agent pytest：完整 payload pair、完整本地回退、显式空 pair 覆盖本地配置、缺失 key 的 v1 兼容、半套 payload/config 拒绝且不写 MMKV。
- 前端构建：单开关、默认开启、配置缺失禁用、轮询保留/失效关闭，以及只提交 `useCircle`。

### 7. Wrong vs Correct

#### Wrong

```java
String clubId = firstNonBlank(req.getClubId(), project.getClubId(), props.getDefaultClubId());
String clubTitle = firstNonBlank(req.getClubTitle(), project.getClubTitle(), props.getDefaultClubTitle());
```

#### Correct

```java
ClubTag pair = resolveClubTag(req); // fixed config + legacy compatibility
payload.put("clubId", pair == null ? null : pair.clubId());
payload.put("clubTitle", pair == null ? null : pair.clubTitle());
```
## 9. Tests Required

- `BydDynastyPlainTextRendererTest`：text/image/video 顺序、BYD token、legacy URL、`sparkora-img:` 拒绝、HTML/表格/链接清洗。
- `BydDynastyPushServiceTest`：payload v2、媒体映射、无封面、视频上限、跨项目/过期/类型错误、`gzh` 400。
- `BydDynastyMediaServiceTest`：流式上传、原子 rename、大小/类型限制、图库注册、清理、任务范围下载鉴权。
- Agent `pytest`：图片/视频上传字段、sibling、视频三段消息、失败不写 MMKV、写入校验回滚、v1 旧任务兼容。
- 前端 `npm run build`：`mediaMode` 默认 gzh 不回归，BYD token 解析正确。

## 10. Wrong vs Correct

Wrong：

```java
// 公众号逻辑混入 BYD
String url = imageService.publicUrl(coverImageId);
payload.messages.add(Map.of("type", "img", "imageRef", "COVER-" + coverImageId));
```

Correct：

```java
ArticleProjectEntity project = requireBydProject(projectId, true);
Body body = compileBody(version.getContentMd());
// 只登记正文中实际出现的 BYD media，按 messages 顺序生成 text/img/video
// 不接受封面参数，不调用七牛上传
```
