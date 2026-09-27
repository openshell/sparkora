# wenyan 主题与发布机制

> 回链：[系统说明总览](README.md) ｜ 模块契约：[spec/preview.md](spec/preview.md)、[spec/publish.md](spec/publish.md)。
> 本文沉淀 Sparkora 排版预览与发布的 wenyan 关键机制,便于后续开发理解。权威字段级契约以模块文档为准。

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
3. 前端无需改动(目录驱动);可选:更新 [spec/preview.md](spec/preview.md)。

### 新增社区主题

1. 把 CSS 放到 `frontend/src/assets/wenyan-themes/<slug>.css` 与 `src/main/resources/wenyan-themes/<slug>.css`(**两份一致**)。
2. 前端 `frontend/src/utils/wenyanThemes.js`:`import ...?raw` + `CUSTOM_THEMES`(渲染兜底)+ `CSS_STORE` 登记。
3. 后端 `WenyanThemeCatalog.COMMUNITY` 增加 `custom:<slug>`(中文名/色点)。
4. **检查 CSS 不得含任何外链图片**(`grep -nE "url\(https?://" <slug>.css` 应为空)——见下方警告。
5. 校验 `diff -r` 两份目录为空,跑 `mvn -q -DskipTests compile` 与 `cd frontend && npm run build`。

> **Warning: 社区主题 CSS 禁止引用外链图片**(09-11-quanzhanlan-broken-image 教训)。
>
> 发布链路会把渲染后 HTML 里引用的所有图片下载后转存到微信,任一图片下载失败即**整次发布失败**(报错「下载图片失败 URL: ...」)。社区主题 CSS 的 `background-image: url(...)`、`content: url(...)` 等会随渲染进入 HTML,一旦外链图床失效(如 `imgkr.cn-bj.ufileos.com` 已 HTTP 400)该主题就再也发不出去。
>
> - 新增主题前必查:`grep -nE "url\(https?://" src/main/resources/wenyan-themes/*.css frontend/src/assets/wenyan-themes/*.css`(应为空)。
> - 装饰性图标优先用 `linear-gradient`/纯 CSS 绘制,或内联 `data:` URI;确需图片则必须自托管到可控图床并长期可用。
> - 历史事故:全栈蓝 `quanzhanlan.css` 的 `#wenyan h2::before` 曾引用失效图壳图标,导致该主题发布持续失败,修复方式为直接移除该 `background-image` 行。

> 社区主题 CSS 若含 `:root { --sans-serif-font ... }` 等变量,浏览器预览时 `applyPreviewTheme` 只把 `#wenyan` 重写为 `.wenyan-preview`,`:root` 变量会成为全局(影响有限,仅字体变量);CLI 侧原生支持。当前 7 个社区主题均含 `:root`,经确认不影响应用样式。

## 6. 已知限制与风险

- 社区主题在微信编辑器的最终观感需真机/草稿箱人工确认(无法自动化)。
- `--custom-theme` 不支持网络 URL,新增社区主题必须把 CSS 打进后端包。
- wenyan-server 2.0.11 鉴权中间件对错误 key 挂起(不返回 401),客户端超时不宜过长。
  > 2026-09-27 更正:当前部署 **无效 key 即刻 401**(`/verify` 实测),不再挂起。原「客户端超时不放宽」那条
  > 正是初版把 `WENYAN_MCP_PUBLISH_TIMEOUT_MS` 压在 30s 的理由,属**误判**,已按实耗提到 180s(见 §7)。

## 7. `/publish` 实测耗时与超时事故模式(09-27-wenyan-stale-conn)

> 本节记录**实测**结论,目的是让后续排障**不再重复走弯路**。初版设计基于一个后来被证伪的假设
> (「JDK 复用了失效的 keep-alive 连接」),若不显式记录,后续极可能重犯。

### 7.1 环境事实

| 事项 | 实测值 |
|---|---|
| wenyan-server 响应头 | `Keep-Alive: timeout=5` |
| 部署拓扑 | wenyan-server 与 Postgres 同机(`10.126.126.1`),经 **`tun0` VPN 隧道**可达;本机 `10.126.126.3` |
| `/publish` 实耗 | 宿主机 curl 43.4s / 13.2s / 5.5s;**容器内** curl 6.1s / 5.4s / 4.5s |

`/publish` 的耗时**波动主因是这条 VPN 隧道,不是代码**;图越多越久(server 端需逐张 fetch 转存微信)。

### 7.2 事故模式:客户端超时 < 服务端实耗 ⇒「假失败」+ 重复草稿

```
/publish 实耗 43.4s  >  客户端读超时 30s
        ↓
客户端 30s 抛读超时 → 前端显示「发布失败」+ last_publish_error
        ↓
服务端仍在跑,~43s 成功写入公众号草稿箱   ⇒ 草稿箱里已经有这篇文章
        ↓
用户重试(自然排障动作) ⇒ 又一篇重复草稿(脏数据自我放大)
```

**修法**:`WENYAN_MCP_PUBLISH_TIMEOUT_MS` 30000 → **180000**(实耗 ~4 倍余量),并在 `.env` /
`.env.example` / `application.yml` 三处显式一致;探针另给独立的 `WENYAN_MCP_VERIFY_TIMEOUT_MS`
(默认 5s),否则通道不可用时 `publish-options` 会被发布超时挂住 180s。契约详见
[spec/publish.md](spec/publish.md) §1.1。

**推广为通用教训**:任何**非幂等**的外部调用(写草稿/写库/扣款/发消息),客户端超阈值都必须
(a) 提示「可能已生效,请先到下游确认再决定重试」,且 (b) **绝不自动重试**。三处超时必须单调:
后端阈值 ≥ 实耗,前端 axios timeout ≥ 后端阈值,nginx `proxy_*_timeout` ≥ 前端最长 axios timeout ——
任一侧倒挂就复现本事故。**详见 `.trellis/spec/backend/external-cli-integration.md` 的同名约定。**

### 7.3 已被证伪的假设(勿重复排查)

| 假设 | 证伪方式与结果 |
|---|---|
| JDK 复用失效 keep-alive 连接导致 EOF | 用真实 `java.net.http.HttpClient` 复现:同一 client 上 `verify → sleep(6000)`(> 服务端 `Keep-Alive: timeout=5`)→ `publish`,**200 成功,耗时 6.2s**。JDK 会自行丢弃失效连接 |
| `Connection: close` 是可行修法 | JDK 直接抛 `IllegalArgumentException: restricted header name: "Connection"`,需全局 `-Djdk.httpclient.allowRestrictedHeaders=connection`,代价不划算 |
| HTTP/2 h2c 升级导致异常 | 显式 `HTTP_1_1` 与默认 `HTTP_2` 耗时无差异(5.3~6.8s),均成功 |
| 社区主题 CSS 外链图片拖慢发布(09-11 先例) | `grep -nE "url\(https?://" {src/main/resources,frontend/src/assets}/wenyan-themes/*.css` → 零命中 |
| 7 张正文图下载慢 | 逐张实测全 200,合计 ~2.5s / 17MB |

初版设计中的 `ChannelStatus` 五态分类、幂等/非幂等重试白名单、`Connection: close` 改造
**均建立在「连接复用缺陷」这一被证伪的假设上,已刻意砍掉**——只保留「按实耗设阈值 + 超时提示防重复」。

### 7.4 排障纪律:探测也会写数据

`/publish` **非幂等**:定位过程中反复对真实 `fileId` 打 `/publish`,每次成功都会留一篇草稿
(一次排障累计约 15 篇,已由用户在公众号后台清理)。**压测/探测阶段复用真实 fileId 同样会落草稿** ——
应先用一次性的小载荷,并**提前告知用户草稿会累积**。

> 顺带发现:`POST /api/projects/{id}/publish` 用 `@RequestParam`(query 参数)而非 JSON body;
> 按 JSON body 发送会被**静默忽略**、主题回落 `default` 且无任何报错。契约见
> [spec/publish.md](spec/publish.md) §2。
