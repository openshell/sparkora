# 车型同步数据安全与可靠性修复

## Goal

消除官网同步链路“空/半响应把已有车型数据写坏”的风险，修复任务并发、卡死、指纹漏更、图片下载与错误可观测性问题；完成存量体检与安全灰度，使同步动作可以放心用于日常运营。

本任务聚焦同步核心链路，不重做车型页视觉设计。当前任务 `10-21-knowledge-car-sync-ux` 的交互整改保持不动，本任务独立验收。

## Background（已确认事实）

- 生产库 57 台车型，46 台有主图，11 台无主图；11 台的官网 `goodsInfo` 返回空条目（实测 `10031`: `items=[]`，`goodsAttrList=[]`），但库里仍为 `SUCCESS`。
- 这 11 台里仍有存量数据：例如 `10039` 有 3 个版本、12 个参数组；`10031` 有 4 个版本。卡片仍显示「同步」按钮。
- 全量 `syncOne` 对空响应会继续执行：`persistVersions` 先删后插、`persistParams` 先删后插、`cleanForModel` 删清洗、`rebuildForModel` 删块/向量，最后 `persistModel` 仍写 `SUCCESS` + `lastSyncAt`（`CarModelService.java:281-302,675-699,711-738`，`CarChunkService.java:75-109`）。
- `persistModel` 对 `name/price/features/introImages` 直接赋值；字段缺失时旧值被 null 覆盖，尤其 `introduce` 为空会把 `intro_images` 写 null（`:680-699`）。
- `persistImages` 只收集成功图片 id，部分失败时用不完整列表覆盖旧图集（`:592-610`）；单图失败仅日志，不进入任务明细。
- `persistModel` 在清洗/向量化之前写 `SUCCESS`/`lastSyncAt`；后续失败只改 `FAILED`，不回滚时间。
- `persistModel/persistVersions/persistParams` 的 `@Transactional` 由同类 `this` 调用，Spring 代理不生效；项目已在 `CarChunkService` 用自注入 `self` 解决同类问题。
- `createJob` 在事务内调用 `@Async runJob`；异步线程可能在提交前读不到任务行而直接退出，任务永久 `RUNNING`（`CarSyncJobService.java:66-105`）。
- `runJob` 的 `status=RUNNING→RUNNING` 条件更新不构成并发锁；`createJob/createBackfillJob/retry` 未检查运行中任务；详情页 `syncOne` 没有按车型锁。
- 60 分钟 stale 清理只改任务行，不能停止旧线程；旧线程会继续写任务/车型数据（`:233-250`）。
- `computeFingerprint` 不含 `img`，也不含 `goodsParams/goodsAttrList`；封面变化、图库封面被删、参数/版本单独变化都会被「指纹一致」跳过（`:403-417,461-477`）。
- `rebuildForModel` 先删旧块/向量再 embedding；`EmbeddingBatchRunner` 的失败计数被忽略，全部失败仍按成功返回。
- INCREMENTAL 跳过也 `success++`；历史任务 8 为 `success=46, failed=0`，46 条明细全部是「跳过:指纹一致」。
- `createBackfillJob` 每次处理全库所有车型，不是只补缺失；57 台任务约 2 分 40 秒。
- `BydCmsClient` 不校验业务 `code`/`data` 结构；`catalog` 在 data 非数组时返回空列表；无重试/退避/限流；HMAC header 时间戳与签名时间戳分别计算，可能跨秒不一致。
- 图片下载接受任意 URL 并跟随重定向，无域名白名单/私网拦截/大小上限；`BodyHandlers.ofByteArray` + `ImageIO.read` 会全量读入/解码（38.7MB、1920×43353 的图片有 OOM 风险）；未知魔数兜底成 png。
- 失败明细直接存 `e.getMessage()`，历史任务 4 的明细里出现完整 SQL/表名/字段名。
- 当前 `carSyncEnabled=false`，自动增量关闭；手动同步/图片补入库仍可触发上述问题。

## Requirements

### R1 官网响应预检与空数据安全（P0）

- `syncOne` 在写库前校验 `goodsInfo`、`goodsParams`、`goodsAttrList` 三个响应；任一为空/不完整则中止，不执行任何 delete/update。
- 空/不完整响应写 `source_state=EMPTY/INCOMPLETE` 与 `sync_note`，只更新 `last_checked_at`，不得写 `SUCCESS` 或刷新 `last_sync_at`。
- 官网空条目的存量车型保留现有版本/参数/清洗/向量；全量同步入口需被禁用或改为非破坏性的「重新检查」。
- 图片补入库继续作为空数据车的安全入口，但必须正确标注「官网无数据」，不能算作成功同步。

### R2 写入原子性与字段保护（P0）

- `persistModel` 仅合并非空字段；不得用 null 覆盖 `name/salesNetwork/vehicleId/price/features/introImages/carRights`。
- `intro_images` 只由图片入库结果写 asset id；`introduce` 为空时保留旧图集。
- `persistVersions/persistParams` 仅在拿到完整新数据时替换；空/不完整时保留旧数据并写 note。
- 多表替换使用真正生效的事务边界（自注入代理或独立 Service）；失败必须回滚，不能留下半套版本/参数。
- 图集更新必须全有或全无，或按内容哈希与旧列表合并；部分图片失败不得缩短旧图集。

### R3 成功口径（P0）

- `SUCCESS` 与 `last_sync_at` 只在采集、图片、清洗、向量全部完成且达到最低成功阈值后写入。
- 清洗/向量/图片部分失败写 `PARTIAL`，全部失败写 `FAILED`；失败原因与图片/向量失败数进入任务明细。
- 新增 `last_checked_at`，空数据/跳过只更新检查时间。

### R4 任务可靠性与并发（P0/P1）

- 异步任务在事务提交后触发；避免 `selectById` 读不到未提交任务行。
- 全局任务串行（或队列）+ 按 `goodsId` 锁；`createJob/createBackfillJob/retry/详情页 syncOne` 使用同一互斥语义。
- 任务增加 heartbeat/cancel 语义；stale 清理不得在旧线程仍可能写入时把任务判失败并放行新任务。
- `runJob` 外层 try/finally 保证任何异常都落终态并写 `error_msg`。
- 任务计数区分 `processed/synced/skipped/failed`；全跳过显示「检查完成」而不是「同步完成」。

### R5 增量完整性与图片/向量（P1）

- 拆分 `image_fingerprint`（`img + introduce`）与 `params_fingerprint`（`goodsParams + goodsAttrList`）；基础信息指纹保留。
- 增量分派：仅图片变化只更新图片；仅参数/版本变化只更新参数/清洗/切块；全未变跳过。
- 「图片补入库」默认只处理缺失/失败/过期图片，另提供全量重扫。
- 向量重建先构建新块/向量，再原子替换旧数据；embedding 失败计数必须影响任务终态，不能静默成功。
- 无主图回退图生成顶部裁剪缩略图，卡片不直接加载超大原图。

### R6 外部接口与图片加固（P1/P2）

- `BydCmsClient` 按端点校验 `code` 与 `data` 结构；`catalog` 失败抛错，不把故障当空目录。
- 增加有限重试、指数退避、`Retry-After`、速率限制；HMAC 时间戳只计算一次。
- 图片下载限制 HTTPS + 比亚迪/CDN 域名白名单，阻断私网地址，重定向后重新校验；限制大小/像素；严格校验图片内容；用 `ImageReader` 读取尺寸，避免全量解码。
- 用户可见错误使用分类短文案；原始 SQL/URL/堆栈只进日志。
- 同步接口校验 `goodsIds` 去重、批量上限、是否为合法目录/存量车型；参数错误返回 400。

### R7 存量体检与灰度（P0/P1）

- 上线前只读审计 57 台：官网响应、版本/参数/清洗/块数量、封面/图集引用、同步时间与失败明细。
- 备份相关表；修复前不执行破坏性写入。
- 11 台官网空数据车标记 `EMPTY`，保留数据，禁用全量同步；有缺失但官网可用的车安全重同步；官网不可用的标记 `STALE`。
- 保持 `carSyncEnabled=false` 部署，手动同步/并发/失败回滚验证通过后再开定时增量。

## Acceptance Criteria

- [ ] 模拟 `goodsInfo.items=[]`、`goodsParams.data=null`、`goodsAttrList.data=[]`：车型版本/参数/清洗/块/向量数量不变，状态为 `EMPTY`，不是 `SUCCESS`。
- [ ] 模拟部分字段缺失：旧 `name/price/features/intro_images` 保留；图集不被 null 或短列表覆盖。
- [ ] 模拟 5 张详情图成功 3 张：旧图集不被缩短，任务明细含图片失败 note。
- [ ] 模拟清洗/embedding 失败：状态 `PARTIAL/FAILED`，`last_sync_at` 不前进，旧向量保留。
- [ ] 模拟版本/参数插入失败：事务回滚，旧版本/参数保留。
- [ ] 两个并发同步请求：同一 `goodsId` 只被一个任务处理；另一个返回 409/排队；无重复版本/参数。
- [ ] 任务执行中抛异常/进程中断：任务不会永久 `RUNNING`；heartbeat 超时后新任务可安全接管。
- [ ] 只改官网 `img`：封面更新，不触发全量清洗/向量；只改参数：参数/清洗/块更新，图片不动。
- [ ] 官网返回 429/5xx：有限重试后失败，任务明细可读；catalog 失败不产生空目录任务。
- [ ] 超大图片/HTML 错误页/内网 URL：被拒绝或跳过，有明确 note，不写入图库，不发生 SSRF。
- [ ] 57 台体检报告：11 台 `EMPTY`、46 台封面正常、无未解释的版本/参数/向量缺失。
- [ ] `mvn -q -DskipTests compile`、`mvn test`、`npm run build`、Playwright smoke 全部通过。
- [ ] 规格文档（`docs/spec/knowledge/car.md`、`.trellis/spec/backend/*`、`.trellis/spec/frontend/*`）同步更新。

## Out of Scope

- 重做参数清洗/切块/向量化算法本身。
- 车型页完整视觉改版、手动封面选择器（另开 UX 任务；本任务只做同步安全所需的禁用/状态提示）。
- 移动端适配。
- 直接在生产库执行数据修复；本任务提供体检/修复脚本与执行说明，生产变更需运维确认。
- 官网签名算法逆向变更（仅修时间戳一致性）。

## Open Questions

无。所有阻塞决策已由用户批准的计划确定；实施中发现新问题时回写本文件并重新评审。
