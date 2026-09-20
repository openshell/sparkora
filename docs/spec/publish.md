# 公众号草稿发布（S5）

> 回链：[系统说明总览](../README.md)

职责：把当前版本经 wenyan-server 写入微信公众号草稿箱；发布成功进 `PUBLISHED_DRAFT`（终态，可重发覆盖）。

> 2026-09-01 定稿，方案 A 发布侧：与预览同渲染核，**preview HTML = 发布真值**。
> 2026-09-01 实测勘误（@wenyan-md/cli 2.0.11）：`/verify` 为 **GET** 探针；`/upload` multipart 字段名 `file`（限 md/css/json/图片，≤10MB）；`/publish` 收 **JSON `{fileId, appId?}`**（fileId 须为上传的 `.json`）；当前部署无效 key 即刻 401。上传文件 TTL 10 分钟。
> 相关：[preview.md](preview.md)（渲染与样式）、[wenyan.md](../wenyan.md)（主题机制）、[img.md](../img.md)（图床）。

---

## 1. 发布链路（同步，一次调用完成）

```
PublishService.publish
 → PreviewService.preview(同参同源:状态校验 + 取图床 URL + frontmatter + wenyan render)
 → 非 degraded 校验(降级 HTML 不进公众号)
 → gzhContent JSON { title(≤64,必填), content=渲染HTML, cover=封面图床URL?, author?, source_url? }   ← asset:// 不用,图片全为图床 http URL;cover 与预览 frontmatter 同源,缺失时 server 退化用正文首图当封面;author/source_url 手填项目级字段,非空才发送(对齐 wenyan frontmatter→微信 author/content_source_url)
 → wenyan-server POST /upload (multipart file=.json) → fileId
 → wenyan-server POST /publish (JSON {fileId}) → {media_id}
 → 原子落库 status=PUBLISHED_DRAFT + publish_media_id/publish_theme/published_at,清 last_publish_error
```

- 封面与正文 `<img src="http(s)…">` 由 server 端 fetch 后转传微信（七牛 http URL 可用）；无封面时 `gzhContent` 不带 `cover`，草稿封面由 server 退化取正文首图（可能无封面图，不阻塞发布）。
- 发布元信息（09-11-preview-publish-bridge）：`author`/`source_url` 由发布页手填、项目级落库（`preview-style`/`publish-meta` 端点），非空才进 `gzhContent`；留空不发送，行为与旧版一致。**风险登记**：远程 wenyan-server 2.0.11 是否透传 `author`/`source_url` 未实测（未知 JSON 键通常被忽略）；如实测被拒，降级为不发送这两键（保留落库与前端展示）。
- 失败语义：任何一步失败 → `last_publish_error` 落库、状态原样保留、`R.fail(400|500, 中文原因)`；可重试整链。
- 重发：再次 `POST /publish` 重新渲染并覆盖草稿，刷新 `publish_media_id`/`published_at`/`publish_theme`（`PUBLISHED_DRAFT` 为终态，不回退）。

---

## 2. 接口契约（全部 `R<T>` 包装；HTTP 200）

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/projects/{id}/publish-options` | 三角色 | — | `{themes[], highlights[], defaultTheme, highlight, macStyle, footnote, previewTheme?, previewHighlight?, previewMacStyle?, previewFootnote?, author?, sourceUrl?, publishEnabled, publishConfigOk, publishDisabledReason?, wenyanServer, publishMediaId?, publishTheme?, publishedAt?, lastPublishError?}`（探针失败不阻塞页面；`preview*` 为项目级预览样式，发布页优先据此初始化；`themes` 为对象数组 `[{id, name, group, color, bright}]`，与 [preview.md](preview.md) `preview-options` 同源） |
| POST | `/api/projects/{id}/publish` | ADMIN/EDITOR | `?theme=&highlight=&macStyle=&footnote=`（query，与 preview 同形） | 成功 `{mediaId, theme, publishedAt}`；前置不满足/渲染参数非法/通道未配置 `R.fail(400)`；链路失败 `R.fail(500)`；失败均回写 `last_publish_error` |

- 项目不存在时 `publish-options` 返回 `R.fail(404, "项目不存在")`。
- 通道就绪度（懒探测，失败不阻塞页面）：`publishConfigOk = WENYAN_MCP_SERVER_URL + API KEY 齐备`；`publishEnabled = configOk && serverVerify()`（`/verify` GET 探针）；未配置时 `publishDisabledReason="发布通道未配置(WENYAN_MCP_SERVER_URL / WENYAN_MCP_SERVER_API_KEY)"`，不可达/key 无效时 `"发布通道不可用(API Key 无效或 server 不可达)"`；`wenyanServer` 为 server 健康信息。
- 配置：`WENYAN_MCP_SERVER_URL`（带 scheme）/`WENYAN_MCP_SERVER_API_KEY`/`WENYAN_MCP_PUBLISH_TIMEOUT_MS`（默认 30s）；未配置时 `publishEnabled=false` + 中文原因，`publish` 返回 `R.fail(400)`。
- 前端：`StepPublish.vue`（子路由 `/projects/:id/publish`）：摘要（标题/封面缩略/插图数）+ **排版参数只读回显**（主题/高亮/Mac/脚注，值来自预览页落库的 `preview*`，不在此编辑；发布时原样传给 wenyan-server）+ 作者/原文地址手填（项目级落库）+ 发布确认弹层 + 成功态（mediaId/时间/重发）+ 失败黄条；viewer 只读；`maxReachableStepOf` 放开到 index=3，`StepPreview` 状态判断含 PUBLISHED_DRAFT 并加「去发布」衔接。

---

## 3. 数据模型（项目表发布相关列）

| 表.列 | 类型 | 说明 |
|---|---|---|
| `sparkora_article_project.publish_media_id` | VARCHAR(128) | 公众号草稿箱 media_id |
| `sparkora_article_project.publish_theme` | VARCHAR(64) | 发布所用主题 |
| `sparkora_article_project.published_at` | TIMESTAMP | 发布时间 |
| `sparkora_article_project.last_publish_error` | VARCHAR(1000) | 最近一次发布失败原因（成功后清空） |
| `sparkora_article_project.author` | VARCHAR(100) | 发布 frontmatter `author`（手填，可空） |
| `sparkora_article_project.source_url` | VARCHAR(500) | 发布 frontmatter `source_url`（手填，可空） |

---

## 4. 验收状态

- [x] `GET /verify`（GET）真 key 200 / 假 key 401；`POST /upload` 真实 JSON 探针 → fileId（2026-09-01 实测）
- [x] 三角色冒烟：viewer publish 403；DRAFT 项目 publish `R.fail(400)`；`publish-options` 探活 `publishEnabled=true`
- [x] `mvn test`（空测试集）/ `npm run build` 通过
- [ ] 真实发布进公众号草稿箱（publish 全链）→ **留用户真机验收**

---

## 5. 关键实现路径

- 后端：`com.sparkora.service.PublishService`（链路编排）、`service.WenyanServerService`（客户端，`x-api-key`）、`service.PreviewService`（同源渲染 + `serverVerify`/`serverHealth`）、`config.WenyanProperties`。
- 前端：`views/project/StepPublish.vue`。
- 表：`sparkora_article_project`。

---

## 6. 已知限制与风险

- 远程 wenyan-server 2.0.11 是否透传 `author`/`source_url` 未实测（见上）。
- 发布依赖远程 server 可用性；key 错误时中间件挂起不返回 401，客户端超时不宜过长。
- 降级 HTML 不进公众号（`degraded=true` 直接中止）。
- 真实发布全链留用户真机验收。
