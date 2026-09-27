# 执行计划：发布超时阈值与探针超时解耦（09-27-wenyan-stale-conn）

## 实施步骤（按序）

- [x] **1. 配置：发布超时 30s → 180s（D1）**
  - [x] `.env` 新增 `WENYAN_MCP_PUBLISH_TIMEOUT_MS=180000`（此前**根本没配**，跑的是代码默认值）
  - [x] `.env.example` 默认值改 180000 + 写明 43s 实测依据
  - [x] `application.yml` 兜底默认 `${WENYAN_MCP_PUBLISH_TIMEOUT_MS:180000}`
        （防漏配环境变量时退回 30s 重现事故）
  - [x] **（check 补）`WenyanProperties.publishTimeoutMs` 代码默认值同步 180000**（第三处，原为 30000）

- [x] **2. 探针超时独立（D2）**
  - [x] `WenyanProperties` 新增 `private long verifyTimeoutMs = 5000;`
  - [x] `application.yml` 加 `verify-timeout-ms: ${WENYAN_MCP_VERIFY_TIMEOUT_MS:5000}`
  - [x] `.env.example` 补 `WENYAN_MCP_VERIFY_TIMEOUT_MS`（说明与发布超时的区别）+ 联动前端/nginx 提醒
  - [x] `.env` 补 `WENYAN_MCP_VERIFY_TIMEOUT_MS=5000`（显式化，避免隐性默认）
  - [x] `WenyanServerService`：抽 `buildRest(Duration)`，建**两个** `RestClient`
    （探针 `verifyTimeoutMs` / 发布 `publishTimeoutMs`）；`verify()`/`health()` 走探针实例，
    `uploadJson()`/`publish()` 走发布实例
  - [x] **探针不加自动重试**（通道真不可达时等待翻倍，见 design D2）

- [x] **3. 超时错误可读 + 防重复提示（D3）**
  - [x] 抽可复用超时判定 `describeTransportFailure`（遍历**整条 cause 链**，非只看顶层）
  - [x] `publish()` 超时文案：明确「服务端可能仍在处理并已写入草稿，请先到草稿箱确认，确认缺失后再重发」
  - [x] 兜底 `catch (Exception)` 过滤 `Error while extracting response…` 等框架串，改给中文归因
  - [x] **保持不自动重试**（非幂等红线）
  - [x] **（check 补）归因片段表 `FRAMEWORK_NOISE`（小写 + `toLowerCase()` 比对）**；兜底取**根因**消息
        并压空白/截断 200 字；循环 cause 链收敛；提示语抽为常量供单测锁定

- [x] **4. 前端等待提示对齐实耗（D4）**
  - [x] `StepPublish.vue`「约十几秒…」→「约 1 分钟…请勿刷新或重复点击(重复发布会产生重复草稿)」
  - [x] **（check 补）`doPublish` 入口加 `if (publishing.value) return` 防重入**
        （非幂等操作不能只靠 UI `loading` 禁用防重）

- [x] **5.（check 新增）跨层超时单调联动 —— 同一事故类别的第二处实例**
  - [x] `frontend/src/api/index.js` 的 `projectApi.publish` axios timeout `120000 → 300000`：
        原值 **小于**后端阈值 180s，等于「浏览器先断开、后端仍在写草稿」，与本次事故同型
  - [x] 核对 nginx `proxy_read_timeout/send_timeout` = 300s ≥ 前端最长 axios timeout（无需改）

- [x] **6.（check 新增）死代码清理**
  - [x] 删除无调用方的 1 参 `get(String path)` 重载（改双参后遗留）；并加注释说明**刻意不提供**
        「默认走发布客户端」的重载（会给「探针误用长超时」留后门）

## 验证步骤

- [x] **V1（AC1/AC2）** 重建 docker 后端 → 用项目 54 发布 → 成功，`publish_media_id` 落库，
      状态进入 `PUBLISHED_DRAFT`，耗时约 40~50s。→ **主会话已实测**（7.83s/7.46s 两次 `code=0` + mediaId；
      DB `PUBLISHED_DRAFT`、`last_publish_error` 清空）。注：容器内链路实测 5~8s，43s 是宿主机经 VPN 的路径。
- [x] **V2（AC3）** 把 `.env` 临时改回 `30000` → 重建 → 发布 → 错误提示为中文可读语义、
      含「可能已写入草稿/到草稿箱确认」、**不含** `Error while extracting response`。
      → **check 已用自动化测试覆盖等价路径**（`WenyanServerServiceTransportTest.
      publish读超时给出中文可读提示且不含框架串`：本地「接受连接但永不回应」socket 触发真实读超时，
      走完整 `publish()` 路径，断言含「草稿箱/已写入」且不含 `Error while extracting response` /
      `java.lang.` / `Exception`）。**改 `.env` + 重建 docker 的真机复现仍留用户验收**（不必做，
      风险是往草稿箱多灌一篇，与本任务非幂等红线冲突）。
- [x] **V3（AC4）** `.env` 的 `WENYAN_MCP_SERVER_URL` 指向不可达地址 → 重建 →
      `publishOptions` 在 **≤10s** 返回（不是 180s）。→ **check 已用自动化测试覆盖**（同上：
      `通道不可达时探针按短超时快速失败` —— 探针档 1200ms、发布档 180s 同时配置，断言 `verify()`
      在 `2×` 量级内返回 false，证明探针**未**误用发布档）。
- [x] **V4（AC5）** 核对两个超时是独立配置项，调其一不影响另一个。→ 代码核对 + 单测
      `探针与发布是两个独立RestClient实例` / `两档超时由各自配置项驱动`（含 `WenyanProperties`
      兜底值 180000 / 5000 断言）。
- [x] **V5（AC6/AC7）** 前端提示文案核对；回归预览渲染、配图上传、发布参数、正常发布。→ 文案已核对；
      预览渲染/配图上传链路本次未触碰（`npm run build` 通过）。**预览与配图的真机回归仍属主会话/用户验收**。
- [x] **V6（AC7）** `mvn -q -DskipTests compile` + `cd frontend && npm run build`。→ 均通过；
      并追加 `mvn test` **510 tests / 0 failures**（新增 `WenyanServerServiceTransportTest` 16 例）。

## Review Gates

- [x] G1 `WenyanServerService` 中 `verify()`/`health()` 确认走**探针**实例，
      `uploadJson()`/`publish()` 走**发布**实例（人工核对构造与调用点）。
      → ✅ `verify()`→`get("/verify", probeRest)`；`health()`→`get("/health", probeRest)`；
      `uploadJson()`→`postForData()`→`rest`；`publish()`→`rest.post()`。
      **check 补**：删除 1 参 `get(String)` 死重载并注明「刻意不提供默认长超时重载」；
      单测 `探针与发布是两个独立RestClient实例` 锁定。
- [x] G2 `publish()` 内**无任何重试**（grep 确认）。→ ✅ 无循环/无 retry；仅注释说明「绝不自动重试」。
- [x] G3 超时文案含「可能已写入草稿」语义，且不含框架内部异常类名串。→ ✅
      `PUBLISH_TIMEOUT_MSG` + `PUBLISH_DRAFT_HINT_MSG` 抽为常量，单测断言含「草稿箱/已写入」、
      不含 `Error while extracting response`/`java.lang.`/`Exception`；AC3 端到端用例见 V2。
- [x] G4 `.env`/`.env.example`/`application.yml` 三处默认值一致（180000），无 30000 残留。
      → ✅ 实际**四处**一致（额外含 `WenyanProperties` 代码默认值）；并清掉文档残留
      （`AGENTS.md:62`、`docs/spec/publish.md` §2 仍写「默认 30s」，均已改）。`WENYAN_MCP_VERIFY_TIMEOUT_MS`
      同步在 `.env`/`.env.example`/`application.yml`/`docs/README.md` 四处可见。
- [x] G5 编译 + 前端构建通过。→ ✅ `mvn -q -DskipTests compile`、`mvn test`（510/0）、
      `npm run build`（40.95s）。
- [x] G6 规格同步完成（见 design §6 四处）。→ ✅ 四处全部落地，另补 2 处
      （`docs/README.md` env 表、`AGENTS.md` Notes）：
      - `docs/spec/publish.md`：新增 **§1.1**（43s 实测依据 / 180s 阈值取值理由 / 三条契约 /
        提示语表 / 已证伪假设指引 / 风险登记）+ §2 配置项与「超时阈值三处单调联动」+ `publishEnabled` 门禁说明
      - `docs/wenyan.md`：新增 **§7**（环境事实 / 事故模式链 / 三处超时单调推广 / 已证伪假设表 /
        排障纪律「探测也会写数据」/ 附带发现的 query-vs-body）+ §6 更正「错误 key 挂起」旧结论
      - `.trellis/spec/backend/external-cli-integration.md`：新增「外部服务超时按实测设定并留余量」
        约定（三处单调、2~4 倍余量、探针独立短超时且不提供默认长超时重载、非幂等超时提示「可能已生效」
        且绝不自动重试、排障纪律）+ 降级链补 2 条（cause 链遍历 / 片段常量大写化与真实拼写）
      - `.trellis/spec/backend/error-handling.md`：新增「禁止把框架内部异常串直接透给用户」约定
        （4 条要求：整链归因、片段表中和、压长度、超时优先于框架串）+ 非幂等超时 Warning
      - **额外** `.trellis/spec/frontend/index.md`：新增「同步长耗时操作（非幂等）的等待提示必须与实耗相符
        + 禁止重复触发」（含 axios timeout ≥ 后端阈值、入口防重入）——即第 5 步发现的前端同型问题

### check 阶段发现并已自修复的问题

| # | 位置 | 问题 | 修复 |
|---|---|---|---|
| 1 | `frontend/src/api/index.js` | `projectApi.publish` axios timeout **120000 < 后端 180000**：浏览器先断开、后端仍在写草稿 → **与本次事故同型**的跨层倒挂（只改后端没改前端） | 提到 `300000`（= 本文件最长值，nginx 300s 无需改），并加注释锁定三处单调关系 |
| 2 | `WenyanServerService` | `get(String path)` 1 参重载在双参化后**无任何调用方**（死代码） | 删除；补注释说明刻意不提供「默认长超时」重载（会给探针误用留后门） |
| 3 | `WenyanServerService`（check 自引入） | 框架串片段常量为小写却用大小写敏感 `contains` → `"Error while extracting response"` 漏判。**由新单测抓出** | 片段表统一小写 + 比对前 `toLowerCase()`；单测钉死 |
| 4 | `WenyanServerService`（check 自引入） | 片段写成 `"no suitable http message converter"`（插了空格）→ 框架实际抛 `No suitable HttpMessageConverter` → 永远匹配不上。**由新单测抓出** | 改为 `no suitable httpmessageconverter`（camelCase 真实拼写） |
| 5 | `WenyanServerService` | `describeTransportFailure` 兜底取**顶层** `getMessage()`（`I/O error on POST request for ...` 无指向性）且不截断 | 改取**根因**消息 + `replaceAll("\\s+"," ")` + 截断 200 字 |
| 6 | `WenyanServerService` | cause 链遍历只防自引用，**a↔b 成环会死循环** | 改 `IdentityHashMap` 已访问集合收敛；单测钉死 |
| 7 | `WenyanServerService` | 类注释与 `WenyanProperties` 注释仍留**已被证伪**的旧结论「错误 key 会挂起 → 客户端超时不放宽」——正是 30s 误判的源头，留着会诱导重犯 | 两处均加「已被实测推翻」更正 + 指向 `docs/wenyan.md §7` |
| 8 | `StepPublish.vue` | 仅靠 `el-button :loading` 隐式禁用防重；「双击开两个确认弹层」「Enter 快速确认」仍可在 `publishing` 置位后二次进入 → 非幂等操作有重复草稿窗口 | `doPublish` 入口加 `if (publishing.value) return` |
| 9 | `.env.example` / `.env` | 缺 `WENYAN_MCP_VERIFY_TIMEOUT_MS`（implement.md 步骤 2 明确要求）→ 探针阈值仍是隐性默认 | 两处补 5000 + 说明与发布超时的区别 + 三处联动提醒 |
| 10 | `AGENTS.md:62` / `docs/spec/publish.md` §2 | 仍写「`WENYAN_MCP_PUBLISH_TIMEOUT_MS` 默认 30s」，与代码/配置矛盾（G4 残留） | 改为 180s 并附 43s 实测依据；`.env`/`.env.example`/`application.yml`/`WenyanProperties` **四处**一致 |
| 11 | `docs/README.md` env 表 | 未列 `WENYAN_MCP_VERIFY_TIMEOUT_MS` | 补入 |
| 12 | `frontend/src/api/index.js:29` | 注释「约十几秒」与实耗（43s+）不符，会诱导用户重试 | 随 timeout 改动一并更正为实耗量级 + 联动说明 |

### check 新增测试

`src/test/java/com/sparkora/service/WenyanServerServiceTransportTest.java`（16 例，纯内存/本地 socket，不连库不连真 server）：

- 超时识别：`SocketTimeoutException` / `HttpTimeoutException`（包在 `RestClientException` 里）/ 裸异常 三形态
- 框架串不泄漏：`Error while extracting response` / `No suitable HttpMessageConverter` 均被中和，
  结果不含 `java.lang.String` / `content type` / 类名
- **AC4 等价验证**：本地「接受连接但永不回应」socket + 探针档 1200ms/发布档 180s → `verify()`
  在 2× 量级内返回 false（探针未误用发布档）
- **AC3 等价验证**：同一 socket 走完整 `publish()` 路径 → 抛中文 `IllegalStateException`，
  含「草稿箱」「已写入」，不含 `Error while extracting response`/`java.lang.`/`Exception`
- 客户端分离：反射断言 `rest` 与 `probeRest` 是两个不同实例；`WenyanProperties` 兜底 180000/5000
- 健壮性：循环 cause 链收敛、超长消息截断、无消息异常、根因优先、提示语长度 < 990 截断口径


## 回滚点

- 步骤 1–4 均为局部改动；异常时还原 `WenyanServerService`、`WenyanProperties`、
  `application.yml`、`.env`/`.env.example`、`StepPublish.vue` 即可，无 DB/协议残留。
- 若某环境 `/publish` 异常变慢，只需下调该环境的 `WENYAN_MCP_PUBLISH_TIMEOUT_MS`，不改代码。

## 实测记录（主会话执行，2026-09-27）

### 环境事实

- `10.126.126.1`（wenyan-server 与 Postgres 同机，Postgres 端口 **5201**）经 **`tun0` VPN 隧道**可达，
  本机 `10.126.126.3`。`/publish` 的耗时波动主因是这条隧道，不是代码。
- `Keep-Alive: timeout=5`（服务端响应头实测）。
- 默认账号可用于联调：`admin/admin123`（`docs/spec/acceptance.md`）。

### 根因证据

| 调用方 | 同一 fileId `/publish` 耗时 | 结果 |
|---|---|---|
| 宿主机 curl | 43.4s / 13.2s / 5.5s | 成功 |
| **容器内** curl（与后端同网络路径） | 6.1s / 5.4s / 4.5s | 成功 |
| JDK HttpClient 独立进程（默认 HTTP_2） | 6.8s / 5.0s | 成功 |
| JDK HttpClient 独立进程（强制 HTTP_1_1） | 5.6s / 5.3s | 成功 |
| **原后端（阈值 30s）** | 43s、68s | **失败** |

两次失败的间隔恰为阈值：`21:12:34.392 → 21:12:44.154`（9.76s，EOF）、
`21:41:09.645 → 21:41:39.661`（30.016s，读超时）。

### 被证伪的假设（勿重复排查）

1. **JDK 复用失效 keep-alive 连接** —— 同一 client 上 `verify → sleep(6s) → publish`（> 服务端 5s
   keep-alive）实测 publish 仍 6.2s 成功；JDK 会自行丢弃失效连接。
2. **`Connection: close`** —— JDK 直接抛 `IllegalArgumentException: restricted header name: "Connection"`，
   需全局 `-Djdk.httpclient.allowRestrictedHeaders=connection`，代价不划算。
3. **HTTP/2 h2c 升级** —— 显式 `HTTP_1_1` 与默认 `HTTP_2` 耗时无差异，均成功。
4. **社区主题 CSS 外链图片**（09-11 先例）—— `grep -nE "url\(https?://" wenyan-themes/*.css` 零命中。
5. **正文图下载慢** —— 7 张逐张实测全 200，合计 ~2.5s / 17MB。

### 修复后验证（docker 重建后）

- 容器内 `WENYAN_MCP_PUBLISH_TIMEOUT_MS=180000` 生效；`publish-options` 0.32s，
  `publishEnabled=true`、`wenyanServer=wenyan-cli 2.0.11`。
- `POST /api/projects/54/publish` 连续 2 次：**7.83s / 7.46s，`code=0` + `mediaId`**。
- 最终以 `theme=custom:hongfei` 发布：**7.88s 成功**，DB 状态 `PUBLISHED_DRAFT`、
  `publish_media_id` 已写、`last_publish_error` 清空。
- `mvn -q -DskipTests compile` ✅ ／ `npm run build` ✅（39.76s）／ `docker compose up -d --build` ✅ healthy。

### 事故与遗留

- **草稿箱污染（已由用户清理）**：定位过程中反复打 `/publish`，每次成功都会留一篇草稿，
  累计约 15 篇。教训：`/publish` 非幂等，**探测阶段复用真实 fileId 同样会落草稿**，
  压测/探测应先用一次性的小载荷并提前告知用户草稿会累积。
- **顺带发现**：`POST /api/projects/{id}/publish` 用 `@RequestParam`（query 参数）而非 JSON body；
  按 JSON body 发送会被**静默忽略**、主题回落 `default` 且无任何报错。
- 未覆盖：VPN 抖动仍可能让 `/publish` 变慢，180s 是余量而非根治；根治需 wenyan-server 侧异步化（范围外）。

---

## 收口复核（trellis-check 之后，主会话）

`trellis-check` 自查发现并修复了 4 处问题，其中一处是本任务之外的**同类事故**，必须记入：

1. **`frontend/src/api/index.js` 的 axios 发布超时原为 120s < 后端 180s** —— 若不修，
   浏览器会比后端**先**放弃：连接断开但后端仍在写草稿，症状与本次事故完全一致
   （前端报失败、草稿却已存在），只是把阈值从「后端 30s vs 实耗 43s」上移了一层。
   已改为 `300000`（= 本文件最长值），后端 180s 会**先**给出中文超时提示。
2. 删除 `WenyanServerService` 中无调用方的 `get(String)` 单参重载 —— 保留它等于给
   「探针误用发布档长超时」留后门（正是 AC4 的事故形态），故按契约删掉而非留着。
3. `FRAMEWORK_NOISE` 片段比对前统一 `toLowerCase()`，并按框架**真实**拼写修正
   `No suitable HttpMessageConverter`（原写 `"http message converter"` 插了空格，永远匹配不上）。
4. 补三层超时联动注释 + 传输层单测。

### AC3 / AC4 的证据升级（不再依赖人工故障注入）

`WenyanServerServiceTransportTest` 用**本地「接受连接但永不回应」的 `ServerSocket`**，
走**真实**的读超时路径，既不触碰真实 server、也不产生任何草稿：

- `publish读超时给出中文可读提示且不含框架串` —— 完整走 `publish()`：POST → 读超时 →
  归因 → 上抛中文 `IllegalStateException`，断言含「草稿箱」指引且不含框架类名串（AC3）。
- `通道不可达时探针按短超时快速失败` —— `verifyTimeoutMs=1200` + `publishTimeoutMs=180000`
  同时设置，断言 `verify()` 在 4×1200ms 内返回 false（AC4），即探针档未被发布档牵连。

结论：AC3/AC4 由**可复现的单测**锁定，比人工改阈值注入更严格（无生产扰动、无草稿副作用）。

### 最终验证

- `mvn test` → **Tests run: 510, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**。
- `docker compose up -d --build` 重建（check 改了后端与前端代码，且 `.env` 新增
  `WENYAN_MCP_VERIFY_TIMEOUT_MS` 需重建容器才能生效）→ backend healthy、frontend 已重建。
- 容器内 `printenv` 确认 `WENYAN_MCP_PUBLISH_TIMEOUT_MS=180000`、`WENYAN_MCP_VERIFY_TIMEOUT_MS=5000`。
- **线上产物**已含修复：前端产物 `index-*.js` 中 `publish:(e,t)=>at.post(.../publish,null,{params:t,timeout:3e5})`；
  nginx `proxy_read_timeout/proxy_send_timeout 300s`。
- 三层超时链（任一侧小于上一侧即重现「假失败 + 重复草稿」）：
  后端 180s（先给出可执行的中文提示）< 前端 axios 300s = nginx 300s。
- 顺带修正 `AGENTS.md` 的过时描述：其中「`mvn test` 目前 `src/test` 为空」不成立，
  仓库实际有 510 个用例（本次新增 16 个）。
