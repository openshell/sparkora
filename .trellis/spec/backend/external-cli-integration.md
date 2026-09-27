# External CLI Integration

> 调用外部命令行工具(如本机 `wenyan` CLI)的约定与陷阱。

---

## Overview

项目通过 `ProcessBuilder` 调用外部 CLI 完成渲染等能力(先例:`PreviewService.renderByCli` 调 `wenyan render`)。本文件沉淀参数优先级、资源物化、错误降级三类可复用契约。

---

## 参数优先级陷阱(必读)

外部 CLI 常有「同义参数互相覆盖」的隐式优先级,顺序/组合错误会导致**静默渲染成错误主题**(无报错)。

先例(实测 wenyan 2.0.11):

```bash
wenyan render -t default --custom-theme /path/chazi.css --file x.md   # ❌ --theme 覆盖 --custom-theme → 出 default 观感
wenyan render --custom-theme /path/chazi.css --file x.md              # ✅ 社区主题:只传 --custom-theme,不传 --theme
wenyan render -t lapis --file x.md                                    # ✅ 内置主题:只传 --theme
```

**约定**:调用前必须确认「哪些参数互斥」。互斥参数用分支构造命令,不要同时传。写实现时对照本文档与 `docs/wenyan.md`。

---

## Convention: 外部服务超时按实测耗时设定并留余量(09-27-wenyan-stale-conn 事故)

外部服务/CLI 的超时阈值**不能拍脑袋**,也不能沿用旧版本的「错误 key 会挂起」这类**已被实测推翻**的旧结论。

### 三处超时必须单调递增(任一侧倒挂 = 事故)

```
外部服务实耗  ≤  后端读超时(WENYAN_MCP_PUBLISH_TIMEOUT_MS 等)
             ≤  前端 axios timeout(frontend/src/api/index.js)
             ≤  nginx proxy_read_timeout / proxy_send_timeout(frontend/nginx.conf.template)
```

先例:`/publish` 实测 43.4s(7 图 17MB),后端阈值曾为 30s ⇒ **前端报「发布失败」而服务端随后成功写入草稿**;
修复时把后端提到 180s 时,前端 axios(120s)若不同步上调,会以另一种形式复现同一事故。**改任一侧须联动核对另外两侧。**

### 阈值取值:实测值 × 2~4 倍余量

- 43s 实耗 → 取 180s(≈4 倍)。60s 不够:43s 已用掉 72%,图翻倍即逼近上限。
- 余量要覆盖:**载荷增长(图更多)、下游变慢、链路抖动**(本例 wenyan-server 经 `tun0` VPN 隧道,I/O 抖动显著)。

### 探针/健康检查必须用独立短超时

把发布超时调大时,**不能连带调大探针**——否则通道不可用时「发布参数」接口(乃至整个页面)被挂住同值时长,
比修之前更糟。做法:同一客户端类里建**两个** `RestClient`,用私有工厂去重(先例 `WenyanServerService.buildRest(Duration)`),
`/verify`、`/health` 走短超时实例,`/upload`、`/publish` 走长超时实例。

> **不要提供「默认走长超时客户端」的 1 参重载**——那等于给「探针误用长超时」留后门,正是本事故的二阶形态。
> 探针**不加自动重试**:纯只读,通道真不可达时重试只会让等待翻倍。

### 非幂等调用超时 ⇒ 必须提示「可能已生效」,且绝不自动重试

**超时不等于失败。** 对写下游的调用(写草稿/写库/扣款/发消息),客户端超时的真实含义是
「我不知道服务端有没有做完」,而不是「没做成」。因此错误文案必须:

1. 明确告知**可能已生效**,并指引用户**先到下游确认**再决定是否重发;
2. **绝不自动重试**(重试 = 重复数据);
3. 提示语不得含框架内部异常串(见 `error-handling.md`)。

先例文案:`发布超时:服务端可能仍在处理并已写入公众号草稿箱,请先到草稿箱确认,确认缺失后再重发`。

### 排障纪律:探测也会写数据

诊断「发布类」问题**不要反复对真实载荷/真实 id 重放**——每次成功都是一条真实脏数据(09-27 一次排障
累积约 15 篇重复草稿)。用一次性小载荷探测,并**提前告知用户数据会累积**。

---

## classpath 资源物化(jar 安全)

外部 CLI 需要**本地文件路径**时(不接受 URL、不能读 jar 内条目),把 `classpath:` 资源在启动期物化到数据盘:

```java
@PostConstruct
void materialize() {
    Path dir = imageProps.storageRoot().resolve("../tmp/wenyan-themes").normalize();
    Files.createDirectories(dir);
    try (InputStream in = new ClassPathResource("wenyan-themes/" + slug + ".css").getInputStream()) {
        Files.copy(in, dir.resolve(slug + ".css"), StandardCopyOption.REPLACE_EXISTING);
    }
}
```

- **必须用 `getInputStream()`,禁止 `getFile()`**——资源打包进 jar 后 `getFile()` 抛 `FileNotFoundException`。
- 物化目录复用数据盘策略(`{IMAGE_STORAGE_DIR}/../tmp/...`),与 `PreviewService.createTempMd` 同源,避免受限容器 `/tmp` 只读。
- 物化失败只 `log.warn` 并**不注册该资源**,让使用处抛中文异常走既有降级链,不阻断应用启动。

> **Warning**: 外部 CLI 若既支持「按名注册主题」又支持「按路径传自定义主题」,优先**免注册传路径**(如 `--custom-theme`),不要写宿主配置目录(如 `~/.config/xxx`)——污染宿主环境且重名会失败(退出码 1)。

---

## 降级链与错误契约

- CLI 非零退出/超时/空输出 → 抛 `IllegalStateException`(中文),由上层捕获降级(先例:`PreviewService` 降级为保底 markdown 渲染,返回 `degraded=true + degradedReason`)。
- 参数校验(如未知主题)在**进 CLI 前**完成,抛 `IllegalArgumentException` → 控制器映射 `R.fail(400)` 中文;绝不把用户输入直接拼进命令行(注入面)。
- 白名单/目录校验的单一真值放在一个类里(先例:`WenyanThemeCatalog`),校验、下发、渲染三处共用,避免多处清单漂移。
- **超时判定必须遍历整条 cause 链**,不能只看顶层:同一超时在不同请求工厂下分别是
  `HttpTimeoutException`(JDK HttpClient)/`SocketTimeoutException`(Simple/HttpURLConnection),
  且都被包进 `RestClientException` / `ResourceAccessException`。先例 `WenyanServerService.describeTransportFailure`。
- 归因片段常量写成**小写**并在比对前 `toLowerCase()`:否则 `"Error while extracting response"` 这类首字母大写的
  串永远匹配不上(09-27 单测钉死的真实漏判)。片段要按框架**真实拼写**写(camelCase 不插空格:
  `No suitable HttpMessageConverter`,不是 `No suitable Http message converter`)。

---

## Common Mistakes

### Common Mistake: 互斥 CLI 参数同时传导致静默错渲染

**Symptom**: 选了 A 主题,渲染出来是 B(默认)主题;无任何报错,`degraded=false`。

**Cause**: CLI 的 `--theme` 优先级高于 `--custom-theme`,两者同时传时后者被覆盖。

**Fix**: 按主题类型分支构造命令(内置 `--theme`;社区 `--custom-theme`),互斥参数绝不同传。

**Prevention**: 接入任何外部 CLI 时,先用真实命令验证参数组合的**实际输出**(而非只看退出码),把互斥关系写进 spec。

### Common Mistake: 用 `getFile()` 读打包后的 classpath 资源

**Symptom**: IDE 内运行正常,打成 jar 后启动即报 `FileNotFoundException`/`InvalidPathException`。

**Cause**: `ClassPathResource.getFile()` 只在资源以**文件系统**形式存在(exploded)时可用;jar 内资源无独立文件。

**Fix**: 一律 `getInputStream()`;需要路径时先物化到磁盘再传路径。

**Prevention**: 新增 classpath 资源消费点时,自检「打 jar 后还能跑吗」——凡传给外部进程的路径,必走物化。
