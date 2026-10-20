# 修复小程序文章媒体推送

## Goal

修复比亚迪王朝小程序文章推送中的图片、视频链路，严格按渠道区分媒体处理：小程序正文媒体不进入七牛，由 Server 暂存、本机 Agent 上传比亚迪 CDN 后写入草稿；公众号既有七牛 → wenyan-server 发布链路保持不变。

## 背景与根因

- 项目创建时已通过 `channel` 区分 `gzh`（微信公众号）与 `byd_miniapp`（比亚迪王朝小程序），两套工作台和发布目标不能互相复用。
- 当前 `BydEditorStep` 复用公众号 `MarkdownEditor`，点击“去推送”时调用 `usePendingImageFlush` → `/api/images/upload`，把小程序图片错误地转存到七牛。
- 服务端 `BydDynastyPushService` 仍按 payload v1 编译：只支持 `text` / `img`，把封面强制作为第一条图片，且 `requireProject` 仍允许部分 `gzh` 项目进入 BYD 链路。
- Local Agent 只支持图片上传和 `text` / `img` 消息，没有视频下载、校验、上传和草稿消息编译。
- 结果是带图片的小程序文章可能因图片不是比亚迪 CDN attachment 而无法写入/打开，视频能力完全缺失。

## Requirements

### R1：渠道隔离

- 所有用户侧 BYD 推送接口（`state`、`createJob`、`getJob`）以及 BYD 媒体接口必须校验项目 `channel == byd_miniapp`；`gzh` 项目调用返回中文 400。
- 不得通过 `gzh` 项目状态就绪来放行 BYD 推送。
- 公众号项目继续使用原四步工作台、七牛图片上传和 wenyan-server 发布流程，行为零回归。

### R2：小程序媒体暂存

- 小程序新粘贴/选择的图片和视频先上传到 Sparkora 的 BYD 专用暂存区，不调用七牛上传接口。
- 暂存媒体必须带项目归属、媒体类型、文件元数据、过期时间和创建人；Agent 只能通过鉴权的任务范围接口下载。
- 暂存文件落 `data/byd-staging/`，由现有 `./data:/app/data` 卷持久化；上传采用流式写入并限制大小，避免 300MB 视频整体进入 JVM 内存。
- 支持从现有图库图片注册为 BYD 媒体；最终仍须由 Agent 上传比亚迪 CDN，不能把公众号公网 URL 直接当作小程序图片消息。
- 暂存媒体应有过期清理；创建任务时必须校验存在、归属、类型、未过期，并把媒体生命周期延长到任务可完成的时间窗口。

### R3：图片协议

- BYD 图片消息必须使用比亚迪上传返回的 `attachment` 作为 `msg_content`。
- 图片消息后必须插入 sibling 空文本占位，保留正文消息顺序；发布前才由比亚迪小程序过滤空文本。
- 不允许用七牛 URL、`sparkora-img:` 公众号暂存 token 或封面图冒充当 BYD 图片 attachment。

### R4：视频协议

- 支持本地 MP4 视频选择、元数据读取、校验、暂存和插入正文。
- 单个视频必须为 MP4，时长 2–120 秒，大小不超过 300MB；正文最多 3 个视频。
- Agent 上传视频时使用 `mediaType=video`，同时提交宽高；最终消息使用 `attachment|mediapreview|duration`，并包含 `src`、`cover`、`loadingImg`、`uploadError` 字段。
- 视频消息同样需要 sibling 空文本占位，保证小程序编辑器的消息顺序和草稿兼容性。
- 不自动转码、不把视频伪装成普通图片消息。

### R5：草稿与消息顺序

- 草稿只写入文章正文 `message` 流，不写 `materials`、不写 `surfacePlot`，不把封面作为第一条消息。
- 文本、图片、视频必须按 Markdown 中出现顺序生成消息。
- 草稿字段保持与王朝小程序文章保存协议一致：`publishType: "article"`、`message`、`atMember`、`titleText` 等；`timestamp`、`draftId` 由 Agent 生成。
- 写入前备份、写入后重新解析校验、失败恢复机制继续复用；任一媒体下载/校验/上传失败时不得写 MMKV。

### R6：错误与可观测性

- 未解析 token、缺失/过期/跨项目媒体、非法视频数量、非法视频格式或超限必须在推送前以中文错误阻断。
- 推送页展示文本、图片、视频数量及媒体准备状态；存在未解析 token 时禁止创建任务。
- 错误信息不得泄露 Agent Token、比亚迪签名密钥或本地文件系统敏感路径。

## Non-goals

- 不改变公众号七牛配置、wenyan 渲染/发布链路或公众号内容语义。
- 不实现小程序社区发帖、话题、活动模板、视频转码或视频裁剪。
- 不把已有公众号文章自动改写成小程序文章；渠道仍在项目创建时绑定。
- 不新增通用媒体 CDN 抽象；本任务只建立 BYD 专用暂存与 Agent 上传边界。

## Acceptance Criteria

- [ ] `byd_miniapp` 项目新插入的图片只进入 BYD 暂存区，不产生七牛上传记录；Agent 以 `mediaType=image` 上传比亚迪 CDN。
- [ ] `byd_miniapp` 项目选择的 MP4 进入 BYD 暂存区；Agent 以 `mediaType=video`、带宽高上传比亚迪 CDN。
- [ ] 草稿图片消息包含 `msg_type=img`、`msg_content=<attachment>`，紧跟 `msg_style.sibling` 空文本。
- [ ] 草稿视频消息包含 `msg_type=video`、`msg_content=<attachment>|<mediapreview>|<duration>`、`src`、`cover`、`loadingImg=false`、`uploadError=false`，并紧跟 sibling 空文本。
- [ ] 文本、图片、视频按正文顺序进入 `message`；正文没有 `materials`、没有 `surfacePlot`、没有封面注入。
- [ ] 正文最多 3 个视频；小于 2 秒、大于 120 秒、非 MP4 或超过 300MB 的视频在推送前被中文错误阻断。
- [ ] 未解析/过期/跨项目媒体 token 在创建任务时被中文 400 阻断，不生成 job。
- [ ] `gzh` 项目调用 `state`、`createJob`、`getJob` 或 BYD 媒体接口均返回中文 400，不得进入 BYD 链路。
- [ ] 任一媒体下载、校验或上传失败时 Agent 不写入 MMKV，并回传明确失败原因。
- [ ] 公众号图片仍走七牛，公众号原发布链路测试不回归。
- [ ] 后端 `mvn test`、Agent `python -m pytest`、前端 `npm run build` 通过。
- [ ] 在 Windows 真机打开王朝小程序草稿箱，人工确认带图片/视频草稿可显示并可继续编辑或发布。

## Constraints

- 渠道在项目创建时确定，前端不得在编辑/推送过程中切换渠道。
- 小程序正文中的媒体引用必须使用 BYD 专用 token，不得复用公众号 `sparkora-img:` pending 会话存储。
- 300MB 视频的浏览器上传、Nginx 反代、Spring 流式接收和 Agent 下载必须有配套上限配置。
- 任何实现都必须先保留 `gzh` 行为，再增加 `byd_miniapp` 分支。
