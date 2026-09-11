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
