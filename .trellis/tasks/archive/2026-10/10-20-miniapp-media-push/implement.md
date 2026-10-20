# 小程序文章媒体推送实施计划

> 前置：仅在用户明确批准本规划并执行 `task.py start` 后开始。当前文档不是实施授权。

## 0. 实施前检查

- [ ] 确认分支仍为 `feature/byd-dynasty-miniapp-push`，工作区改动范围可解释。
- [ ] 阅读 `prd.md`、`design.md`、`research/protocol-facts.md`。
- [ ] 阅读 `.trellis/spec/backend/byd-miniapp-push.md`、`.trellis/spec/backend/error-handling.md`、`.trellis/spec/backend/database-guidelines.md`、`.trellis/spec/backend/container-deployment.md`、`.trellis/spec/frontend/index.md`。
- [ ] 先记录公众号回归基线：图片粘贴 → 七牛 → 公众号发布现有测试结果。
- [ ] 确认 Windows Agent 可停止，避免升级窗口领取 v1/v2 混合任务。

## 1. 数据模型与存储基座

- [ ] 新增 Flyway 迁移（以仓库当前最大版本为准，设计稿暂名 `V25__byd_dynasty_media.sql`）：
  - `sparkora_byd_dynasty_media` 表、唯一索引、项目/过期索引。
  - 字段按 `design.md`：`media_id`、`project_id`、`media_type`、`source_kind`、`storage_state`、路径/URL、元数据、归属、时间。
- [ ] 新增实体与 Mapper：`BydDynastyMediaEntity`、`BydDynastyMediaMapper`。
- [ ] 在 `BydDynastyProperties` / `application.yml` 增加：
  - `BYD_MEDIA_STORAGE_DIR`
  - `BYD_MEDIA_MAX_IMAGE_MB`
  - `BYD_MEDIA_MAX_VIDEO_MB`
  - `BYD_MEDIA_MAX_VIDEOS`
  - `BYD_MEDIA_TTL_HOURS`
  - `BYD_MEDIA_JOB_RETENTION_HOURS`
- [ ] 更新 `.env.example`、`docker-compose.yml`，确保 `./data:/app/data` 覆盖 `data/byd-staging`。
- [ ] 实现 `BydDynastyMediaService` 的预留、流式落盘、原子 rename、读取、过期清理、从图库注册、删除。
- [ ] 实现定时清理；清理逻辑先校验目标路径位于 `BYD_MEDIA_STORAGE_DIR` 下，避免路径穿越。
- [ ] 单元测试：预留/完成、大小超限、非法 MIME/魔数、原子写入失败、URL 注册、过期清理、任务窗口延长。

验证：

```powershell
mvn -q -DskipTests compile
mvn -q -Dtest=BydDynastyMediaServiceTest test
```

## 2. 用户侧与 Agent 媒体接口

- [ ] 新增 `ProjectBydDynastyMediaController`：
  - `POST /api/projects/{projectId}/byd-dynasty/media`
  - `PUT /api/projects/{projectId}/byd-dynasty/media/{mediaId}/content`
  - `POST /api/projects/{projectId}/byd-dynasty/media/from-image`
  - `GET /api/projects/{projectId}/byd-dynasty/media/{mediaId}`
  - `DELETE /api/projects/{projectId}/byd-dynasty/media/{mediaId}`
- [ ] 所有接口先校验项目 `channel == byd_miniapp`，写接口要求 ADMIN/EDITOR。
- [ ] `PUT` 使用原始 `InputStream`，不使用 `MultipartFile`；校验 `Content-Length`/受限流计数，先写 `.part` 再原子落盘。
- [ ] 在 `BydDynastyAgentController` 增加：
  - `GET /api/agent/byd-dynasty/jobs/{jobId}/media/{mediaId}`
  - 校验 Agent Token、job 属于当前 Agent、状态允许、media 被 payload 引用。
- [ ] 更新 `SecurityConfig` 仅放行新增 Agent 下载路径；用户侧接口仍走 JWT。
- [ ] 更新 `frontend/nginx.conf.template`：仅 BYD 原始流上传 location 设置 `client_max_body_size 310m`、`proxy_request_buffering off`；全局 `/api/` 上限保持原值。
- [ ] 测试：`gzh` 项目 400、跨项目媒体 404/400、过期媒体 400、未引用媒体 403/400、`gzh` 图片不经过新接口。

验证：

```powershell
mvn -q -Dtest=BydDynastyMediaServiceTest,BydDynastyPushServiceTest test
```

## 3. Renderer 与 payload v2

- [ ] 修改 `BydDynastyPlainTextRenderer`：
  - 新增 `ImageBlock` / `VideoBlock` 或等价有序媒体块。
  - 识别 `sparkora-byd-media:`、`sparkora-byd-video:`、legacy `http(s)` URL。
  - 拒绝 `sparkora-img:` 和其他非法目标。
  - 保持现有纯文本清洗、HTML 删除和顺序。
- [ ] 修改 `BydDynastyPushService`：
  - `PAYLOAD_VERSION = 2`。
  - `state`、`createJob`、`getJob` 统一 `requireBydProject`，删除“gzh 状态就绪可进 BYD”的分支。
  - `buildPayload` 去掉 cover 参数和封面首条消息。
  - 批量解析媒体、校验项目/类型/过期/视频数，生成 `messages` + `media`。
  - 创建 job 前延长引用媒体有效期；任一错误不插入 job。
  - 保留版本标题/正文只读，不改实体。
- [ ] `BydDynastyPlainTextRendererTest` 增加 text/image/video 顺序、BYD token、公众号 token 拒绝、legacy URL、空内容。
- [ ] `BydDynastyPushServiceTest` 更新 v2 断言，新增无封面、媒体映射、视频 >3、跨项目/过期/类型错误、`gzh` 400。
- [ ] 确保响应 payload 可 JSON 序列化，错误文案中文且 ≤1000 字符。

验证：

```powershell
mvn -q -Dtest=BydDynastyPlainTextRendererTest,BydDynastyPushServiceTest test
```

## 4. Local Agent 媒体与消息编译

- [ ] 在 `byd_local_agent.py`：
  - `PAYLOAD_VERSION = 2`；保留 v1 读取路径用于排空旧任务。
  - 新增通用 `upload_media`（图片/视频），复用比亚迪 `commonUpload` 接口和 `buffer` 字段。
  - 图片压缩 JPEG，`mediaType=image`；视频保持 MP4，`mediaType=video` + `height`/`width`。
  - 新增任务范围媒体下载 `download_media(config, job_id, media)`，使用 `X-Agent-Token`，流式写临时文件。
  - 新增 MP4 魔数、扩展名、大小、时长、宽高校验；实现轻量 MP4 box 解析或等价纯 Python 解析。
  - 新增视频消息构造和图片/视频 sibling 空文本。
  - 视频 `msg_content` 使用 `attachment|mediapreview|duration`，消息含 `src`、`cover`、`loadingImg=false`、`uploadError=false`。
  - 草稿保持 `publishType=article`、只写 `message`，不写 `materials` / `surfacePlot`。
  - `_verify_bytes` 扩展为精确比较完整 message（含 sibling 和视频字段）。
- [ ] 更新 Agent 预检：报告 `payloadVersion=2`、上传配置、媒体临时目录可用。
- [ ] 更新 `config.example.env` / README，说明暂存下载来自 `SPARKORA_BASE_URL`，不得配置任意绝对下载 URL。
- [ ] 扩展 `tests/test_byd_local_agent.py`：
  - 图片上传返回 attachment、视频上传带宽高。
  - 图片/视频消息形状和 sibling。
  - 视频超限/非 MP4/解析失败。
  - 媒体下载失败/上传失败不写 MMKV。
  - 写入后校验失败恢复备份。
  - v1 旧任务兼容。

验证：

```powershell
Set-Location agent/byd-dynasty
python -m pytest -q
Set-Location ../..
```

## 5. 前端 BYD 媒体编辑

- [ ] 新增 `frontend/src/utils/bydMediaRefs.js`：token 前缀、解析、去重、数量、失效判断。
- [ ] 新增 `frontend/src/utils/bydMediaUpload.js`：
  - 预留媒体 → 原始 `fetch`/XHR PUT 上传 → 返回 mediaId。
  - 视频读取 `duration`、`videoWidth`、`videoHeight`。
  - 前端限制 MP4、≤300MB、2–120 秒、最多 3 个视频。
- [ ] 修改 `MarkdownEditor.vue`：
  - 增加 `mediaMode`（默认 `gzh`）和外部 `mediaUploadHandler`。
  - `gzh` 分支保持现有 `sparkora-img:`/pending store 行为不变。
  - `byd` 分支粘贴图片调用外部 BYD 上传，成功后插入 `sparkora-byd-media:<id>`。
- [ ] 修改 `BydEditorStep.vue`：
  - 不再调用 `usePendingImageFlush` / `imageApi.upload` 处理 BYD 媒体。
  - 增加视频选择、元数据校验、上传进度、token 插入。
  - BYD 模式隐藏“设为封面”或将其禁用，不传 cover。
  - 更新文案为“媒体由本机 Agent 上传比亚迪 CDN”。
- [ ] 修改 `PreviewImageDrawer.vue`：
  - 图库插入改为 `from-image` 注册 BYD 媒体后插入 token。
  - 保留公众号模式原行为；BYD 模式不设置封面。
- [ ] 修改 `BydPushStep.vue`：
  - 用 `bydMediaRefs` 统计图片/视频/未解析媒体。
  - 有未解析 token、媒体未 READY 或视频 >3 时禁用推送并显示中文原因。
- [ ] 在 `frontend/src/api/index.js` 增加 BYD 媒体 API 具名导出，不把流式上传塞进 `imageApi`。

验证：

```powershell
Set-Location frontend
npm run build
Set-Location ..
```

## 6. 文档与契约同步

- [ ] 更新 `.trellis/spec/backend/byd-miniapp-push.md`：v2、媒体 token、sibling、视频、无封面、渠道门禁。
- [ ] 更新 `docs/spec/channel.md`：
  - 接口表增加 BYD 媒体 API。
  - 说明 BYD 不经过七牛、公众号仍走七牛。
  - 更新 payload v2 与视频限制。
  - 更新兼容/回滚和验收状态。
- [ ] 更新 `.trellis/spec/frontend/index.md`：`mediaMode` 默认 gzh、BYD token 解析单一来源、封面禁用。
- [ ] 仅保留 `research/protocol-facts.md`，不提交 wxapkg、完整拆包目录或临时脚本。

## 7. 全量验证门禁

按顺序执行：

```powershell
python ./.trellis/scripts/task.py validate .trellis/tasks/10-20-miniapp-media-push
mvn test
Set-Location agent/byd-dynasty
python -m pytest -q
Set-Location ../..
Set-Location frontend
npm run build
Set-Location ..
```

必须确认：

- [ ] `byd_miniapp` 新图片/视频不调用 `/api/images/upload`，不产生七牛记录。
- [ ] 图片/视频按正文顺序进入 `message`。
- [ ] 图片/视频均有 sibling 空文本；视频 `msg_content` 三段正确。
- [ ] 草稿无 `materials`、无 `surfacePlot`、无封面消息。
- [ ] 媒体失败不写 MMKV；写入校验失败可恢复。
- [ ] `gzh` BYD 接口返回中文 400；公众号发布回归通过。
- [ ] 前端 build、后端 mvn test、Agent pytest 全绿。

## 8. 真机验收与交付

- [ ] Windows 本机启动新版 Agent，确认预检 `payloadVersion=2`。
- [ ] 创建 `byd_miniapp` 项目，插入 1 张图 → 推送 → 打开草稿箱确认图片。
- [ ] 插入 1 个 MP4 → 推送 → 确认封面/播放/顺序。
- [ ] 图文视频混合 + 3 个视频 → 确认消息顺序。
- [ ] 4 个视频、非 MP4、超 300MB、1 秒/121 秒视频 → 中文阻断。
- [ ] 过期/跨项目/未知 token → 中文阻断且无 job。
- [ ] 用 `gzh` 项目调用所有 BYD 用户侧接口 → 400。
- [ ] 执行公众号带图发布回归 → 七牛 + wenyan-server 正常。
- [ ] 记录人工验收结果到任务 `check.jsonl` 相关文档或任务备注，提交前等待用户确认。

## 9. 回滚点

- **数据库/存储后**：保留 V25 与 `data/byd-staging`，回滚代码不影响 gzh。
- **Server/前端后**：回滚到上一版本，确认 BYD 新 job 不再生成。
- **Agent 后**：先停止领取任务；v2 pending job 由 Server 标记失败或人工清理，再回滚 Agent。
- 任何回滚都不得删除用户已有 `byd_dynasty_*` 字段、公众号草稿或七牛资源。
