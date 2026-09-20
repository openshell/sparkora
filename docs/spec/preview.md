# 排版预览（wenyan 同核渲染）

> 回链：[系统说明总览](../README.md)

职责：把当前版本正文渲染为公众号排版 HTML，并维护项目级预览样式（主题/高亮/Mac/脚注）。

> 2026-08-30 定稿，方案 A：预览与发布同核算子和图片通道，**preview HTML = 发布排版真值**。
> 深潜文档：[wenyan.md](../wenyan.md)（主题目录、`--custom-theme` 用法与限制、新增主题步骤）。

---

## 1. 融合架构（wenyan 双通道）

| 通道 | 实现 | 用途 |
|---|---|---|
| 预览 | 本机 `wenyan CLI`（`@wenyan-md/cli`，`WENYAN_CLI_PATH`）`render` 命令 | 纯排版输出 HTML，不碰微信 |
| 发布（S5） | 远程 `wenyan-server`（`WENYAN_MCP_SERVER_URL`，微信凭据配在 server 端） | `POST /upload`(+`x-api-key`)→fileId；`POST /publish`(fileId+.json)→`{media_id}` |

- 图片正文/封面**全部为图床公网 URL**（不再走 `asset://fileId` 通道，fileId 10 分钟 TTL 复杂度归零）。
- **S6 图库完全依赖图床，本地不留**：图片入库即直接转存图床（`ImageStorage.upload`），`storage_key` 非空；预览/发布组装时直接取 `storage_key` 拼公网 URL，**不再懒转存**。图床供应商抽象层 `ImageStorage`（当前实现七牛 `QiniuService`），切换供应商只需新增实现类 + 改配置（见 [img.md](../img.md)）。
- **配图组装规则（2026-09-01 定稿；2026-09-11 修订 R3，预览到发布衔接）**：`buildMarkdown` 后端发布链路统一组装为 frontmatter（`title` + 有封面时 `cover: <图URL>` + 手填 `author`/`source_url`）+ 正文；**预览页/复制排版只渲染纯正文**（`renderMarkdownHtml(contentMd)`），不再把 frontmatter 拼进前端 markdown（`@wenyan-md/core` 不解析/剥离 frontmatter，会导致 `<hr>`+`<h2>title:…</h2>` 残留）；frontmatter 组装职责完全移到后端发布链路。**插图落点完全由正文 markdown 引用决定**——正文中引用了哪张图（图床公网 URL）、出现在哪里，就是最终文章的落点；未被正文引用的选定插图**不自动追加文末**（所见即所得）。`cover` 仅进公众号草稿封面元信息，不在正文渲染——正文里看不到封面图属预期。
- **插图落点（2026-09-01 交互定稿）**：预览页工具栏「插图」面板按选定顺序列出已选插图，点击即以 markdown 图片语法插入编辑器光标处（左栏 md 可见可编辑，正文已引用的在面板内标绿 ✓）；正文里没引用的插图不会出现在文章中（不自动追加文末），口径在面板内明示。
- **配图建议的落点（2026-09-16，09-15 article-auto-illustrate）**：预览页配图抽屉「智能建议」tab 的候选，用户点「插入到此段」/「全部采用」时（用户批准后才执行）：① 经 `MarkdownEditor.insertMdAtAnchor(headingPath, md)` 把 `![](原图URL)` 插到**锚点标题行之后**（按标题首次出现定位；标题找不到退回光标处）；② 调 `POST /api/projects/{id}/images/{imageId}/body?action=add` 登记 `body_image_ids`。**插图落点仍完全由正文 markdown 决定**；系统绝不自动写入。详见 [image.md](image.md)「配图建议」。
- 删除图：`ImageService.delete` 落库删除 + 图床对象（非阻塞，失败仅 warn）。
- 降级链：wenyan CLI 不可达/超时/失败 → 简化保底渲染（`degraded=true` + 中文原因）；主题名按后端权威目录校验防 CLI 参数注入；CLI 超时 `WENYAN_RENDER_TIMEOUT_MS`（默认 30s）。
- **主题目录（09-11-wenyan-themes）**：权威清单由 `WenyanThemeCatalog` 固定，共 **15 个** = 8 个 wenyan 内置（`default/orangeheart/rainbow/lapis/pie/maize/purple/phycat`）+ 7 个 mdnice 社区主题（`custom:chazi 姹紫 / custom:mohei 墨黑 / custom:nenqin 嫩青 / custom:hongfei 红绯 / custom:lanqing 兰青 / custom:shanchui 山吹 / custom:quanzhanlan 全栈蓝`）。`.env WENYAN_THEME_NAMES` 已废弃，不再参与校验/下发。详见 [wenyan.md](../wenyan.md)。

---

## 2. 数据模型增量（幂等 ALTER）

| 表.列 | 类型 | 说明 |
|---|---|---|
| `sparkora_image_asset.storage_key` | VARCHAR(300) | 图床 key（**入库即转存，非空**；原 `qiniu_key` 语义通用化） |
| `sparkora_article_project.publish_media_id` | VARCHAR(128) | S5 公众号草稿箱 media_id（见 [publish.md](publish.md)） |
| `sparkora_article_project.publish_theme` | VARCHAR(64) | 发布所用主题 |
| `sparkora_article_project.published_at` | TIMESTAMP | 发布时间 |
| `sparkora_article_project.last_publish_error` | VARCHAR(1000) | 最近一次发布失败原因 |
| `sparkora_article_project.author` | VARCHAR(100) | S5+ 发布 frontmatter `author`（手填，可空） |
| `sparkora_article_project.source_url` | VARCHAR(500) | S5+ 发布 frontmatter `source_url`（手填，可空） |
| `sparkora_article_project.preview_theme` | VARCHAR(64) | S5+ 预览页当前主题（跨会话保持） |
| `sparkora_article_project.preview_highlight` | VARCHAR(64) | S5+ 预览页当前高亮主题 |
| `sparkora_article_project.preview_mac_style` | BOOLEAN | S5+ 预览页 Mac 代码块开关 |
| `sparkora_article_project.preview_footnote` | BOOLEAN | S5+ 预览页链接转脚注开关 |

---

## 3. 接口契约

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| GET | `/api/images/preview-options` | 三角色 | — | `{themes[], highlights[], defaultTheme, highlight, macStyle, footnote}`；`themes` 为**对象数组**（09-11-wenyan-themes）：`[{id, name, group, color, bright}]`，`group` 为 `builtin`/`community`，前端下拉按组呈现（内置主题 / 社区主题） |
| POST | `/api/projects/{id}/preview` | 三角色 | `?theme=&highlight=&macStyle=&footnote=`（query） | `{html, theme, highlight, macStyle, footnote, degraded, degradedReason?}`；业务失败 HTTP 200 + `R.fail`，未知主题 `R.fail(400)`（按权威目录校验防 CLI 参数注入）；前置未就绪 `R.fail(400)` |
| PUT | `/api/projects/{id}/preview-style` | ADMIN/EDITOR | `{theme?, highlight?, macStyle?, footnote?}`（只更新非 null 字段） | `R<Void>`；项目不存在 `R.fail(404)`；主题/高亮非目录内 `R.fail(400)`（校验复用 `PreviewService`/`WenyanThemeCatalog`） |
| PUT | `/api/projects/{id}/publish-meta` | ADMIN/EDITOR | `{author?, sourceUrl?}`（只更新请求中出现的字段，空串清空） | `R<Void>`；项目不存在 `R.fail(404)`；`author>100`/`sourceUrl>500` `R.fail(400)` |

- **依赖顺序**：项目状态 VERSIONS_READY 才可预览（`POST /preview` 校验，否则 `R.fail(400)`）；
- 七牛配置开关 `QINIU_ENABLED`（AK/SK 兼容旧裸名 `${AK}` `${SK}` 回退）：关闭时 `preview` 直接 `R.fail("图床未配置…")`；已配置但上传失败 `R.fail(500,"图床上传失败: …")`。
- 状态推进：预览不改变项目状态。
- 发布（S5）：与预览同参同源渲染，preview HTML = 发布真值；字段级契约见 [publish.md](publish.md)。
- `PUT /preview-style` 用 `UpdateWrapper` 显式 `set` 仅目标列（避免 `updateById` 全字段覆盖把并发写入回写旧值）。

---

## 4. 前端

- `StepPreview.vue`（`/projects/:id/preview` 子路由，步骤三）：`preview-options` 下拉/开关控件读后端配置；iframe `srcdoc` 顶部标注「排版引擎:文颜(与发布同源)」；`degraded=true` 顶部黄条；移动端适配。
- 四步流程 `maxReachableStepOf` 扩到 `index=3`（发布），发布步对 VERSIONS_READY/PUBLISHED_DRAFT 解锁。
- 配图并入预览：工具栏「配图」面板提供图库插入 + AI 生图（文生图/图生图，产物进图库后插入正文）两种来源；封面走 frontmatter `cover` 元信息（见 [image.md](image.md)）。
- 预览到发布衔接（09-11-preview-publish-bridge）：预览页/复制排版只渲染**纯正文**（无 frontmatter 残留）；「去发布」时若正文 dirty 自动先保存再跳转（失败停留并提示）；预览主题/高亮/Mac/脚注变更防抖落库项目级（`PUT /preview-style`），刷新/跨会话保持；工具栏宽度档位 `phone/tablet/full` 仅作用于预览容器视觉；`ctrl-bar` 粘性 + 间距打磨。
- **主题选择范围（09-11-wenyan-themes）**：预览页主题下拉按「内置主题 / 社区主题」两组列出全部 15 个主题，社区主题显示中文名与色点；社区主题 `custom:*` 通过本机 CLI `--custom-theme <本地CSS绝对路径>` 渲染（CSS 随后端包内置），与内置主题一样可选、可预览、可发布。
- **主题渲染分支（09-11-wenyan-themes）**：内置主题传 `--theme <id>`；社区主题传 `--custom-theme <abs css>` 且**不传 `--theme`**（同传时 `--theme` 覆盖 `--custom-theme`）；`--custom-theme` 不支持网络 URL。CSS 由 `WenyanThemeCatalog` 启动时从 classpath 物化到数据盘 `{IMAGE_STORAGE_DIR}/../tmp/wenyan-themes/`（jar 安全），物化失败该主题走降级链。

---

## 5. 已知限制与风险（登记）

- `pic.caiqz.cn` 仅有 http（https 证书未配）：预览从 localhost 拉不成问题；公众号内显示的是微信端上传后的 URL，不受影响。后续可加 https。
- wenyan-server 2.0.11 鉴权中间件对错误 key 挂起（不返回 401）：客户端超时不宜过长，且建议 server 升级。
- theme 清单由后端 `WenyanThemeCatalog` 权威固定（15 个），不依赖 server 端注册：主题只在本机 CLI 渲染阶段应用，wenyan-server 只收渲染后的 HTML，不感知主题。`.env WENYAN_THEME_NAMES` 已废弃。
- **社区主题 CSS 禁止外链图片（09-11-quanzhanlan-broken-image）**：发布时 wenyan-server 会下载渲染 HTML 中引用的所有图片，任一外链失效即整次发布失败（报错「下载图片失败 URL」）。新增/维护社区主题必须自检 `grep -nE "url\(https?://" src/main/resources/wenyan-themes/*.css frontend/src/assets/wenyan-themes/*.css` 为空；历史事故：全栈蓝 `quanzhanlan.css` 曾引用失效图壳图标（`imgkr.cn-bj.ufileos.com`，HTTP 400）致发布失败，已移除。详见 [wenyan.md](../wenyan.md)。

---

## 6. 关键实现路径

- 后端：`com.sparkora.service.PreviewService`（渲染/降级/主题校验）、`com.sparkora.service.WenyanThemeCatalog`（15 主题权威目录 + CSS 物化）、`com.sparkora.wenyan.*`（CLI 调用）、`config.WenyanProperties`；控制器端点：`ArticleProjectController#preview` / `#savePreviewStyle` / `#savePublishMeta`、`ImageController#previewOptions`。
- 前端：`views/project/StepPreview.vue`、`components/MarkdownEditor.vue`（`insertMd` / `insertMdAtAnchor`）、`utils/wenyanThemes.js`（浏览器预览 CSS 兜底）。
- 表：`sparkora_article_project`（preview_* / author / source_url）、`sparkora_image_asset.storage_key`。
