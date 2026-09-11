# wenyan 主题与发布机制

> 本文沉淀 Sparkora 排版预览与发布的 wenyan 关键机制,便于后续开发理解。权威字段级契约仍以 `docs/s0-spec.md` §11/§12 为准。

## 1. 双通道总览

| 通道 | 实现 | 用途 |
|---|---|---|
| 预览 | 本机 `wenyan CLI`(`@wenyan-md/cli`,`WENYAN_CLI_PATH`)`render` 命令 | 纯排版输出 HTML,不碰微信 |
| 发布 | 远程 `wenyan-server`(`WENYAN_MCP_SERVER_URL`,微信凭据配在 server 端) | `POST /upload` → fileId;`POST /publish` → `{media_id}`(草稿箱) |

**关键结论:主题只在本机 CLI 渲染阶段应用,wenyan-server 只收渲染后的 HTML(`gzhContent`),不感知主题。** 因此扩展主题不需要改动 server 协议,也不需要往 server 注册主题。

## 2. 主题来源

权威清单由后端 `com.sparkora.service.WenyanThemeCatalog` 固定,共 **15 个**:

### 内置主题(8 个,group=`builtin`)

`default / orangeheart / rainbow / lapis / pie / maize / purple / phycat`

由 wenyan CLI 原生识别(`wenyan theme -l` 可列),渲染时传 `--theme <id>`。

### 社区主题(7 个,group=`community`,id 前缀 `custom:`)

| id | 显示名 | 色点 |
|---|---|---|
| `custom:chazi` | 姹紫 | `#773098` |
| `custom:mohei` | 墨黑 | `#5c5c5c` |
| `custom:nenqin` | 嫩青 | `#47c1a8` |
| `custom:hongfei` | 红绯 | `#f83929` |
| `custom:lanqing` | 兰青 | `#009688` |
| `custom:shanchui` | 山吹 | `#ffb11b` |
| `custom:quanzhanlan` | 全栈蓝 | `#3594f7` |

来源为 mdnice 社区主题 CSS(Apache-2.0 生态),CLI 不识别,须用 `--custom-theme` 渲染。

## 3. `--custom-theme` 用法与限制(CLI 2.0.11 实测)

| 事项 | 结论 |
|---|---|
| 渲染命令 | `wenyan render --custom-theme <本地CSS绝对路径>` |
| 与 `--theme` 同传 | **`--theme` 会覆盖 `--custom-theme`** → 社区主题必须**只传 `--custom-theme`、不传 `--theme`** |
| 网络 URL | **不支持**(传 URL 退出码 1) → CSS 必须随后端包内置 |
| 非法路径 | 退出码 1 → 走既有降级链(`degraded=true` + 中文原因) |
| 注册到宿主 | `wenyan theme --add/--rm` 可写 `~/.config/wenyan-md`,但会污染宿主环境且重名退出码 1;Sparkora **不用注册方式**,改用免注册的 `--custom-theme` |

### CSS 双份存在

| 位置 | 用途 |
|---|---|
| `src/main/resources/wenyan-themes/*.css` | 后端 CLI `--custom-theme` 用(启动时物化到数据盘) |
| `frontend/src/assets/wenyan-themes/*.css` | 浏览器预览(`@wenyan-md/core` + CSS 直传)用 |

**两份内容必须一致**,校验:`diff -r frontend/src/assets/wenyan-themes src/main/resources/wenyan-themes` 应为空。

### jar 安全物化

classpath 资源打包进 jar 后 `getFile()` 不可用,故 `WenyanThemeCatalog.@PostConstruct` 把 `classpath:wenyan-themes/*.css` 复制到 `{IMAGE_STORAGE_DIR}/../tmp/wenyan-themes/{slug}.css`(与 `PreviewService.createTempMd` 同一数据盘策略),缓存绝对路径。物化失败仅告警,该主题渲染时抛中文异常走降级链。

## 4. 发布链路的主题落点

```
前端下拉(后端目录驱动)
   → PUT /preview-style(目录校验) → projects.preview_theme
   → 浏览器预览:@wenyan-md/core + 社区 CSS(前端打包)
   → 发布:PublishService → PreviewService.renderByCli(主题在此应用)
        → gzhContent(纯 HTML,已含主题样式) → wenyan-server /upload → /publish → 公众号草稿箱
```

- 预览与发布**同参同源**(`PublishService` 复用 `PreviewService.preview`),保证「预览所见 = 发布真值」。
- `projects.preview_theme` 直接存主题 id(含 `custom:*`),`VARCHAR(64)` 足够,无需 schema 变更。
- `preview-options` / `publish-options` 的 `themes` 字段为对象数组 `[{id, name, group, color, bright}]`,前端按 `group` 分「内置主题 / 社区主题」两组。

## 5. 如何新增一个主题

### 新增内置主题

1. 确认目标 CLI 版本内置该主题(`wenyan theme -l`)。
2. 在 `WenyanThemeCatalog.BUILTIN` 增加一项(含色点/是否亮色)。
3. 前端无需改动(目录驱动);可选:更新 `docs/s0-spec.md` §11。

### 新增社区主题

1. 把 CSS 放到 `frontend/src/assets/wenyan-themes/<slug>.css` 与 `src/main/resources/wenyan-themes/<slug>.css`(**两份一致**)。
2. 前端 `frontend/src/utils/wenyanThemes.js`:`import ...?raw` + `CUSTOM_THEMES`(渲染兜底)+ `CSS_STORE` 登记。
3. 后端 `WenyanThemeCatalog.COMMUNITY` 增加 `custom:<slug>`(中文名/色点)。
4. 校验 `diff -r` 两份目录为空,跑 `mvn -q -DskipTests compile` 与 `cd frontend && npm run build`。

> 社区主题 CSS 若含 `:root { --sans-serif-font ... }` 等变量,浏览器预览时 `applyPreviewTheme` 只把 `#wenyan` 重写为 `.wenyan-preview`,`:root` 变量会成为全局(影响有限,仅字体变量);CLI 侧原生支持。当前 7 个社区主题均含 `:root`,经确认不影响应用样式。

## 6. 已知限制与风险

- 社区主题在微信编辑器的最终观感需真机/草稿箱人工确认(无法自动化)。
- `--custom-theme` 不支持网络 URL,新增社区主题必须把 CSS 打进后端包。
- wenyan-server 2.0.11 鉴权中间件对错误 key 挂起(不返回 401),客户端超时不宜过长。
