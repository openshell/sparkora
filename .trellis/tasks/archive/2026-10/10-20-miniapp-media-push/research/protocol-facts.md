# 比亚迪王朝小程序文章媒体协议事实

> 来源：对王朝小程序发布包（`wxa28c31d4ff7ae869`）的只读拆包分析。原始 wxapkg、完整模块和临时脚本不纳入仓库；以下只保留设计所需事实。
>
> 关键原始定位：`operationBar.module.js`（媒体选择/上传/消息占位）、`publishNote.formatted.js`（文章草稿、消息顺序、发布前清理、视频消息）。

## 1. 文章草稿形态

文章草稿（`publishType: "article"`）以 `message` 数组保存正文，典型字段：

```json
{
  "publishType": "article",
  "message": [],
  "atMember": [],
  "titleText": "",
  "tid": "",
  "topicName": "",
  "fid": "",
  "fname": "",
  "clubId": "",
  "clubTitle": "",
  "longitude": "",
  "latitude": "",
  "location": "",
  "selectedPostTemplateId": null,
  "selectedExtConfig": {},
  "carInfo": {},
  "source": "",
  "vin": "",
  "reportId": "",
  "timestamp": 0,
  "draftId": ""
}
```

结论：

- 文章草稿的图片、视频全部在 `message` 中。
- 不写 `materials`。
- 不写 `surfacePlot`；视频封面 `surfacePlot` 只在小程序发布时生成。
- 空文本 sibling 占位保留到草稿保存；真正发布时 `handleMsg()` 才过滤空文本。

## 2. 图片消息

图片上传成功后形成：

```json
{
  "msg_type": "img",
  "msg_content": "<attachment>",
  "id": "<nonce>"
}
```

其后紧跟一条空文本 sibling：

```json
{
  "msg_type": "text",
  "msg_style": { "sibling": "<img-id>" },
  "msg_content": "",
  "id": "<nonce>"
}
```

`msg_content` 必须是比亚迪上传返回的 `attachment`，不能是七牛 URL。

## 3. 视频消息

文章视频上传成功后形成：

```json
{
  "msg_type": "video",
  "msg_content": "<attachment>|<mediapreview>|<duration>",
  "src": "<attachment>",
  "cover": "<mediapreview>",
  "id": "<nonce>",
  "loadingImg": false,
  "uploadError": false
}
```

其后同样紧跟空文本 sibling：

```json
{
  "msg_type": "text",
  "msg_style": { "sibling": "<video-id>" },
  "msg_content": "",
  "id": "<nonce>"
}
```

补充事实：

- 文章视频消息不保存 `height` / `width`（上传请求和素材预览对象才需要宽高）。
- `msg_content` 三段用 `|` 拼接，第三段是视频时长。
- 最终发布时 `handleMsg()` 会删除 `id` / `src` / `cover` / `loadingImg` / `uploadError`，并补 `msg_sort`。
- 视频封面在发布时通过 `setPostCover()` 生成类似 `<mediapreview>|video` 的 `surfacePlot`，不写草稿。

## 4. 视频限制（小程序发布页事实）

- 最多 3 个视频。
- 单个视频：
  - 2–120 秒；
  - ≤300MB（`314572800` bytes）；
  - 仅 MP4；
  - 选择时使用 `sizeType: ["original","compressed"]`，但最终业务校验按上述限制。
- 图片选择页对单图 >20MB 有失败提示，因此 BYD 暂存图片上限采用 20MB。

## 5. 比亚迪上传接口

```text
POST https://common-upload.bydauto.com/dynasty/App/ForUpload/upload
multipart field: buffer
```

图片请求字段：

```js
{
  module: "xxl_platform",
  mediaType: "image",
  path: tempFilePath,
  session_id
}
```

视频请求字段：

```js
{
  height,
  width,
  module: "xxl_platform",
  mediaType: "video",
  path: tempFilePath,
  session_id
}
```

成功响应至少需要：

- `attachment`
- 视频还需要 `mediapreview`
- 视频响应包含 `height` / `width`

## 6. 发布前消息处理

`handleMsg()` 的行为：

1. 深拷贝 `message`。
2. 对 `text` 消息按 @ 成员拆分。
3. 过滤 `msg_content.trim()` 为空的文本（因此空 sibling 只在草稿阶段存在）。
4. 合并 `materials`（社区发帖路径使用；文章路径不应依赖该字段）。
5. 删除消息 UI 临时字段 `id`、`src`、`cover`、`uploading`。
6. 为每条消息补 `msg_sort`。

本任务只构造文章草稿，所以第 4 步的 `materials` 不参与，且不得写入文章草稿。

## 7. 对本项目的直接约束

- `byd_miniapp` 正文媒体必须使用 BYD 专用暂存，不进入七牛。
- 图片和视频都必须由本机 Agent 上传比亚迪 CDN。
- 图片、视频消息都必须保留 sibling 空文本占位。
- 视频必须按 `attachment|mediapreview|duration` 构造，不能伪装成 `img`。
- 文章草稿不得写 `materials`、`surfacePlot` 或封面首条消息。
