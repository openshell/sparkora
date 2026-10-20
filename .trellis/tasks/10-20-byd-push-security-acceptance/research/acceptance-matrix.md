# BYD 推送安全验收矩阵

> 任务：`10-20-byd-push-security-acceptance`
> 原验收任务：`10-16-integration-security-validation`
> 证据日期：2026-10-21
> 分支：`feature/byd-dynasty-miniapp-push`
> 证据原则：只记录实际运行的测试、代码审查结果和明确的保留项；不把历史成功记录等同于所有异常场景已验证。

## 0. 状态与证据边界

- `已测`：有本轮实际运行的自动化测试，或本轮已复核的历史测试输出。
- `代码审查`：逐行检查生产代码和调用路径，但没有可直接复现的运行时测试。
- `替代验证`：不能安全复现真实故障，使用同一失败路径的 Mock、临时文件或恢复测试代替；不声称覆盖真实进程终止、断电或磁盘故障。
- `保留`：当前代码或证据不足以证明安全契约成立，必须作为风险或后续工作记录。
- 本轮没有修改真实项目、版本、MMKV、Token、`.env` 或真实推送数据。
- 真实推送记录仅从本机 `agent/byd-dynasty/work/push-history.jsonl` 和 Agent 状态文件只读核对。
- 测试不读取或输出 `.env`、Agent Token、上传签名密钥或私钥。
- `2026-10-21` 已从独立干净 worktree 部署提交 `0c2d949` 并完成页面、HTTP 与容器健康复验。

## 1. 原 R1 十个联调场景

| # | 场景 | 预期安全行为 | 代码锚点 | 测试证据或替代验证 | 结论 |
|---|---|---|---|---|---|
| R1-1 | Agent 未启动 | 创建任务在落库前失败，UI 不得把“未配置”和“离线”混淆 | `src/main/java/com/sparkora/service/BydDynastyPushService.java:289-300`（先检查用户、渠道、版本、在线 Agent 和预检）；同文件 `:616-623`（按 owner 查 ONLINE Agent）；`frontend/src/components/publish/BydDynastyPanel.vue:11-14, 128` | `BydDynastyPushServiceTest#agent离线时创建任务直接失败不落任务`：断言中文离线错误且 `jobMapper.insert`、项目更新均未发生；`project-steps.spec.js#BYD panel reports configured agent offline instead of disabled` 覆盖已配置但离线状态 | 已测 |
| R1-2 | Agent 在线但微信仍在运行 | 预检返回失败，Agent 不领取/不写 MMKV | `agent/byd-dynasty/byd_local_agent.py:1186-1212`（固定 `tasklist` 检查）；`:1260-1263`（发现微信进程即报错） | `test_preflight_blocks_when_wechat_is_running`：模拟 `weixin.exe`，断言 `ready=false` 且错误提示“完全退出微信”；另以 `test_tasklist_uses_replace_decoding` 覆盖进程输出解析 | 已测（临时模拟） |
| R1-3 | Agent 在线但 MMKV 文件缺失 | 预检失败，不进入写入；备份目录也不应被误用 | `byd_local_agent.py:1240-1258`（主文件/CRC 存在性、可解析性） | `test_preflight_blocks_missing_mmkv_files`：临时目录无文件，断言两个存在性检查为 false、`ready=false` | 已测（临时目录） |
| R1-4 | Agent 在线但 MMKV 文件结构异常 | 解析失败时拒绝写入，返回可行动错误 | `byd_local_agent.py:1176-1183`（`readable_mmkv` 捕获解析异常）；`:1252-1258`（预检汇总解析错误） | `test_preflight_blocks_malformed_mmkv_files`：写入伪造的主文件和 CRC，断言 `mmkvPlatformDraftsParsable=false` 且预检失败；解析器本身另有 `test_parse_mmkv_reads_platform_drafts` 正向覆盖 | 已测（伪造文件） |
| R1-5 | BYD 图片上传失败 | 图片上传必须在编译草稿和写 MMKV 之前；失败不得写入 | `byd_local_agent.py:1391-1405`（v2 先 `upload_payload_media`，再 `compile_messages`/`build_draft`/`write_mmkv`） | `test_execute_job_does_not_write_mmkv_when_media_upload_fails`：上传函数抛错，断言未调用 `write_mmkv`；上传映射、JPEG 重编码和视频上限也有独立测试 | 已测（Mock 上传） |
| R1-6 | MMKV 写入中途中断 | 写入前有备份；写入后校验失败时恢复原文件；恢复失败必须明确要求人工处理 | `byd_local_agent.py:497-522`（先备份，写入后读取并 `_verify_bytes`，异常调用 `restore_files`）；备份内容由 `backup_files` 保存 | `test_write_mmkv_restores_backup_when_post_write_verification_fails`：模拟“写入后校验失败”，断言主文件和 CRC 均恢复、备份目录有两个文件。**没有实际杀进程、断电或磁盘掉线测试**，因此不是对真实中断的保证 | 替代验证；真实进程中断仍保留 |
| R1-7 | 草稿数量达到 20 | 在备份和写入前拒绝，不能覆盖原文件 | `byd_local_agent.py:493-495`（`len(old_drafts) >= 20` 直接抛错） | `test_write_mmkv_rejects_20_drafts_without_modifying_files`：临时 MMKV 构造 20 条草稿，断言抛“20 篇上限”、主文件/CRC 字节不变、备份目录未创建 | 已测（临时 MMKV） |
| R1-8 | 同一任务重复上报 | 只有当前 Agent、RUNNING 状态的任务能完成一次结果回写；重复上报不能再次修改项目或公众号字段 | `BydDynastyPushService.java:195-230`（Agent 归属，UPDATE 同时限制 `status=RUNNING` 和 `agent_id`）；`:161-191`（领取使用条件更新） | `BydDynastyPushServiceTest#同一agent不能重复上报已结束任务`：检查结果 UPDATE 同时限制 `status=RUNNING` 与 `agent_id`，模拟条件更新返回 0，断言抛错且项目不更新；`#其他agent不能重复上报同一任务`：跨 Agent 上报被拒绝；`#agent领取采用条件更新防止并发重复领取`：检查条件 UPDATE SQL | 已测（Mock 状态机）+ 代码审查 |
| R1-9 | 多用户/任务归属隔离 | Agent 只领取绑定 owner 的任务；媒体下载同时校验 job、Agent 和 payload 引用 | `BydDynastyPushService.java:100-120`（注册绑定配置 owner）；`:170-185`（领取过滤 `created_by`）；`BydDynastyMediaService.java:471-495`（`authorizeAgent`、payload 引用和下载路径校验） | `BydDynastyMediaServiceTest#任务范围下载缺少AgentId_错误Agent或未引用媒体均拒绝`；`#正文媒体解析拒绝跨项目过期和类型不匹配`；`#任务范围下载要求领取Agent_payload引用和READY暂存文件`；另见 Agent 注册 owner 测试 | 已测（Mock 服务层） |
| R1-10 | 正常推送成功 | 服务端创建任务，Agent 领取、上传媒体、写草稿并成功回传 | `BydDynastyPushService.java:289-328`；`byd_local_agent.py:1378-1412`；真实记录见下节 | 历史真实记录：`2026-10-20 18:35:13`，job `da3f37ba08974838b5277c6b58291808`，project `67`，draft `20fee5d96f1441c8ad87a1c29a40097b`，`draftCount=6`，耗时 `9327ms`，`serverReported=true`；同文件 `18:06:35` 还有一条 `draftCount=5` 的成功记录。PRD 记录该次为图片 8、视频 0。 | 已测（历史真机证据）；草稿箱人工核对保留 |

- 预检到领取的硬门禁：`byd_local_agent.py:1642-1653` 只有在 `preflight.ready=true` 时才调用 `claim_job`；`once` 路径同样在 `:1609-1615` 直接返回未预检通过的任务。因此 R1-2～R1-4 的预检失败不会进入 MMKV 写入。

### R1 的明确缺口

- 没有打开比亚迪王朝小程序草稿箱逐项核对标题、正文、换行、图片顺序、车友会标签和草稿数量；因此 R1-10 的“成功记录”只证明 Server 收到成功结果和本地写入了草稿，不证明内容人工验收通过。
- 没有真实视频推送的成功记录；视频只由临时媒体、payload 编译和上传单测覆盖。
- 没有真实杀进程、断电或磁盘 I/O 中断测试；R1-6 使用写入后校验失败的替代故障注入。

## 2. 原 R2 安全边界

| 安全项 | 代码/测试证据 | 结论 |
|---|---|---|
| Agent 认证必需 | `BydDynastyAgentController.java:39-107` 对 register、heartbeat、claim、result、media 均检查 `X-Agent-Token`；`BydDynastyPushService.java:84-90` 使用 `MessageDigest.isEqual` 常量时间比较；`BydDynastyPushServiceTest#agentToken使用专属常量比较且JWT无关` 覆盖正确、错误、空值和未配置 Token | 已测（错误/空/未配置 Token 被拒绝） |
| 过期 Token 被拒绝 | 当前代码没有 Agent Token TTL、签发时间或过期字段；只有静态共享 Token 的相等比较。没有可执行的过期 Token 测试，也不能声称该边界通过 | 保留/不可验证；若产品要求“过期”语义，应另开最小修复任务加入 TTL 设计，不应伪造测试 |
| 不能领取不属于授权用户的任务 | 注册将 `owner_user_id` 绑定到服务端配置；`claimJob` 按 `created_by = agent.ownerUserId` 过滤；`BydDynastyMediaService.authorizeAgent` 再校验 job 的绑定 Agent、owner 和 `X-Agent-Id` | 已测（服务层 Mock）+ 代码审查 |
| Agent 不执行 Server 下发的任意命令或脚本 | Server payload 只由 `buildPayload` 生成标题、消息、媒体引用和固定圈子字段；Agent 没有执行 payload 中命令的分支。唯一与任务路径相关的子进程是固定参数 `tasklist /NH /FO CSV`（`byd_local_agent.py:1191-1199`）；控制面板也只调用固定的预检/启动命令（`byd_agent_control.py:270-283`） | 代码审查 |
| 所有 BYD 敏感配置只在本机 Agent 配置 | `AgentConfig`（`byd_local_agent.py:66-98`）从本机配置读取 `SPARKORA_AGENT_TOKEN`、上传签名密钥、session、MMKV 目录等；服务端 payload builder 只发送文章和媒体引用，不把上传密钥放入 payload；服务端媒体映射不返回 `storagePath` | 代码审查 |
| Server 不保存用户本机路径 | BYD 服务端媒体表保存的是 server-relative `storage_path`，不是用户 Windows 本机路径；`BydDynastyMediaService.mediaMap` 不返回 `storagePath`（`:607-622`）；`downloadPath` 是受控任务范围和 mediaId 组成的服务端路径（`BydDynastyPushService.java:551-558`，`BydDynastyMediaService.java:485-495`）。`BydDynastyPushServiceTest#图库URL媒体使用URL下载而不暴露任务下载路径` 覆盖返回形状 | 代码审查 + 已测 |
| 日志不泄露 Token、密钥、URL 或本机路径 | `safe_error_text`（`byd_local_agent.py:127-155`）替换配置密钥、本机目录、HTTP(S) URL、签名/Token 字段和 Windows 路径；`test_log_and_safe_error_text_redact_sensitive_paths_and_signatures` 断言输出不含原始秘密和路径 | 已测 |

**R2 结论：**错误 Token、空 Token、未配置 Token、任务归属、跨 Agent 媒体访问、服务端不执行任意命令、服务端不保存用户本机路径和日志脱敏均有证据；**静态 Token 的过期语义未实现，不能宣称完成“过期 Token”验收。**

## 3. 原 R3 稳定性

| 稳定性项 | 代码/测试证据 | 结论 |
|---|---|---|
| 写入前有可用备份 | `byd_local_agent.py:497-503` 先调用 `backup_files`，失败即停止；`test_write_mmkv_with_text_payload` 验证写入成功后存在备份；本机 `work/backup/` 只读看到多次真实运行生成的 `.bak`/`.crc.bak` | 已测（临时文件）+ 只读真实备份证据 |
| 任务失败不污染公众号发布状态 | `applySuccessToProject` 只设置 `byd_dynasty_*` 字段；`BydDynastyPushServiceTest#agent结果成功只写BYD字段不触碰公众号字段` 检查 SQL set 不包含 `publish_media_id`、`publish_theme`、`published_at` 或 `status`；失败分支只调用 `writeBydError` | 已测（Mock SQL） |
| Agent 离线可从 UI 快速发现 | 心跳超时由 `markStaleAgentsOffline` 将 ONLINE 置为 OFFLINE（`BydDynastyPushService.java:626-637`）；`state` 返回 `agentOnline`/预检；面板有独立离线提示；`project-steps.spec.js#BYD panel reports configured agent offline instead of disabled` 验证“已配置但离线”不会误报配置缺失 | 已测（服务层逻辑 + 前端 Mock E2E） |
| 超时/重试不造成双写草稿 | `reportResult` 使用 `status=RUNNING` 条件更新；Agent 成功回传失败只记录一个最终历史事件，重试不会改变已经结束的任务。但当前**没有 RUNNING 任务租约、超时回收或 reaper**：Agent 崩溃后任务可能永久停在 RUNNING，无法证明超时重试链路完整；也没有真实超时故障注入 | 部分/保留：已有状态机防护，但租约和超时恢复未完成 |
| 重复点击不创建不可追踪的重复任务 | `BydDynastyPanel.vue:61, 103, 142, 174-190` 使用 `submitting` loading 和按钮禁用，阻挡同一组件的连续点击；但是创建任务 API 没有 idempotency key，两个浏览器标签页或直接 API 重试仍可创建多个独立任务，且没有“按用户+项目+窗口去重” | 部分/保留：UI 防护已存在，API 幂等未实现 |

**R3 结论：**备份、状态隔离和离线发现已通过；超时/重试双写和重复点击幂等只有部分保护，不能把 R3 宣称为完全通过。

## 4. 原 R4 真机与部署

| 项目 | 证据 | 结论 |
|---|---|---|
| 真实推送成功记录 | `agent/byd-dynasty/work/push-history.jsonl` 两条 `SUCCESS`；最近一条为 2026-10-20 18:35:13，`draftCount=6`、`durationMs=9327`、`serverReported=true`。任务 PRD 另行记录该次为 2026-10-20 18:35:14、图片 8、视频 0；PRD 时间与 history 的 18:35:13 相差 1 秒，可能是同一轮 UI/落盘时间的展示差异，但现有记录没有唯一关联字段可以直接证明。`push-history` 本身不含媒体计数，因此未把 8/0 当作独立记录字段。 | 已有真机成功记录 |
| Agent 在线、预检和圈子页面 | `agent/byd-dynasty/work/agent.status.json` 只读显示 v1.1.0 ready、MMKV 草稿 6、微信进程为空、上传配置已配置；前端 `project-steps.spec.js` 覆盖 BYD 面板路由、离线提示和成功草稿 ID 回退；测试 mock 含圈子字段，但没有断言圈子表单的真实渲染或提交语义。真实页面未重新自动点击 | 历史状态 + 前端契约测试；不是新的完整真机点击验收 |
| 至少成功写入一条 BYD 小程序草稿 | 上述 `SUCCESS` 记录含 draftId 和 draftCount；`execute_job` 只有在 `write_mmkv` 返回后才成功回传 | 已满足“至少一条写入记录” |
| 人工核对草稿内容 | 未打开真实草稿箱；未逐项确认标题、正文、换行、图片顺序、车友会标签和草稿数量；视频成功路径也没有真实样本 | 保留/未验证 |
| 部署健康检查 | 本轮按要求未 commit/push/deploy；前端产品代码有变更，所以若要上线必须重建 frontend 镜像。建议主会话在推送目标提交后执行 `./deploy.sh --no-pull`，验收 backend healthy、frontend 200、backend `/api/auth/me` 401 | 未执行部署 |
| 服务端/Agent 运行时变更判断 | 产品运行时代码只改了 `BydDynastyPanel.vue`；Agent 新增内容仅为测试，后端新增内容仅为测试；因此不需要重建 backend/Agent 运行时，但 frontend 镜像需要重建。最终仍以主会话的提交差异为准 | 待主会话最终确认 |

**R4 结论：**有一条真实成功写入记录，但没有完成真实草稿箱人工核对和视频真机覆盖；部署必须等待主会话提交/推送后执行。

## 5. 原 10-16 AC-1 至 AC-8

| AC | 结论 | 证据/说明 |
|---|---|---|
| AC-1 所有联调场景有记录 | `[x]` | 本文件 R1-1 至 R1-10 均有代码锚点、测试、替代验证或保留项 |
| AC-2 异常场景阻止 MMKV 写入或给出恢复指引 | `[x]`（有条件） | 微信运行、文件缺失/异常、上传失败和 20 条上限均有测试；真实写中断未复现，使用备份恢复测试替代，恢复失败有明确人工检查指引 |
| AC-3 Agent Token 校验与任务归属验证通过 | `[ ]`（部分通过） | 正确、错误、空、未配置 Token 和跨用户/跨 Agent 归属有测试；**过期 Token 无 TTL，属于保留项，因此不勾选 AC** |
| AC-4 BYD 状态与公众号状态隔离 | `[x]` | `agent结果成功只写BYD字段不触碰公众号字段` 检查 SQL set |
| AC-5 正常真机推送至少成功一次 | `[x]` | 两条历史 SUCCESS 记录；人工草稿内容核对不包含在该 AC 的证据内 |
| AC-6 备份文件可恢复或比对 | `[x]` | `test_write_mmkv_restores_backup_when_post_write_verification_fails` 恢复主文件和 CRC；真实备份文件存在 |
| AC-7 `mvn test` 与前端构建通过 | `[x]` | 本轮实际结果见下节；Agent pytest 也通过 |
| AC-8 输出最终部署/启动/使用说明 | `[x]` | 已部署 `0c2d949` 至 `sparkora` Compose 项目；backend healthy、frontend 200、backend `/api/auth/me` 401；页面显示真实草稿 ID `20fee5d96f1441c8ad87a1c29a40097b` 与草稿数量 6 |


## 5.1 本任务 `10-20-byd-push-security-acceptance` AC 状态

| AC | 结论 | 说明 |
|---|---|---|
| AC-1 原 R1 十个场景均有证据、替代验证或保留项 | `[x]` | 本文件第 1 节已逐项记录。 |
| AC-2 原 R2 全部安全边界有代码/测试证据 | `[ ]`（部分通过） | Token 认证、归属和日志边界有证据；静态 Token 无 TTL，“过期 Token”仍保留。 |
| AC-3 原 R3 五项稳定性均有结论 | `[ ]`（部分通过） | 备份、状态隔离、离线发现已有证据；RUNNING 租约/reaper 与 create-job 幂等键仍未实现。 |
| AC-4 至少一条正常真机成功记录已写入并说明覆盖范围 | `[x]` | 两条历史 SUCCESS；草稿箱内容和视频真机未覆盖。 |
| AC-5 后端、Agent、前端构建通过 | `[x]` | 结果见第 6 节。 |
| AC-6 运行时变更完成镜像重建和健康检查 | `[x]` | 独立 worktree 部署 `0c2d949`；backend healthy、frontend 200、backend 401；`/projects/67/byd-push` 显示成功任务、真实草稿 ID 和草稿数量 6。 |
| AC-7 父子任务按顺序归档并提交关联记录 | `[ ]` | 产品修复与部署提交已完成，但 Token TTL、RUNNING 租约/reaper、create-job 幂等键仍为真实缺口；安全验收未通过前不得归档原 BYD 父子任务。 |
| AC-8 未修改/删除生产数据、MMKV、Token 或凭据 | `[x]` | 本轮仅运行测试、构建和只读状态检查；测试使用 Mock/临时目录。 |

## 6. 本轮实际测试结果

| 命令/范围 | 结果 | 备注 |
|---|---|---|
| 后端 `mvn test`（`JAVA_HOME=C:\Program Files (x86)\Common Files\JDK\jdk-21`） | 1248 passed / 0 failures / 0 errors / 0 skipped，BUILD SUCCESS | 2026-10-21 check 阶段重跑（含同 Agent 重复上报的条件更新断言） |
| Agent `python -m pytest -q` | 60 passed（1.99s） | 含缺失/异常 MMKV、微信、20 条草稿测试 |
| 前端 `npm run build` | 通过，4242 modules，22.00s | 最终展示修复后的构建结果，不等同于真实页面验收 |
| Playwright 定向 `npx playwright test tests/smoke/project-steps.spec.js` | 9 passed（10.1s） | 覆盖公众号不挂载 BYD 面板、byd-push 路由、离线 Agent 提示、`job.result.draftId` 优先、同任务旧响应回退、跨任务禁止串用旧 draft ID，以及预检/项目状态两级草稿数量回退 |
| Playwright 全量 | 首轮 27 passed / 24 failed；基线生成后本地重跑 52 passed | 首轮 24 个失败均为缺少 `*-win32.png` Windows 视觉基线，输出包含 “A snapshot doesn't exist ... writing actual”；基线生成后重跑 52/52 通过，但这依赖本轮生成且未提交的本地 Windows 基线，不能据此宣称干净 checkout 全量通过。没有 smoke/logic 失败；生成的快照和 `test-results/` 不纳入提交 |
| 独立 worktree 部署（2026-10-21） | commit=`0c2d949`；backend Up/healthy，frontend Up，frontend 200，backend `/api/auth/me` 401；未覆盖原 `fix/knowledge-car-sync` checkout | 使用 `/data/code/sparkora-byd-deploy-d183ca3` 独立 worktree；容器数据卷仍指向原 `/data/code/sparkora/data` |
| 真实页面复验（`/projects/67/byd-push`） | Agent 在线、预检通过、圈子“四川迪友会”、草稿数量 6；最近成功任务 `da3f37ba08974838b5277c6b58291808` 显示草稿 ID `20fee5d96f1441c8ad87a1c29a40097b` | 修复了历史结果缺少 `draftId` 时页面显示 `-`，以及预检缺少计数时未回退项目最后草稿数的问题；未人工打开草稿箱核对正文/图片顺序 |

## 7. 结论与后续

1. **可以归档的证据部分：** R1 的异常场景、R2 的错误 Token/归属/媒体权限、R3 的备份/隔离/离线提示、R4 的历史成功记录均有可追溯证据。
2. **不能判定为完全通过：**
   - Agent Token 过期 TTL 未实现；
   - RUNNING 任务没有超时租约/reaper，Agent 崩溃可能留下永久 RUNNING；
   - create-job API 没有幂等键，跨标签页/API 重试仍可能创建多个任务；
   - 真实草稿箱内容未经人工核对，视频未真机覆盖；
   - 真实 MMKV 进程中断/断电没有复现，只有恢复测试替代；
   - 服务器部署健康检查未在本轮执行。
3. **若后续需要补齐：** 优先单独评估 Agent Token TTL、任务租约/reaper 和 create-job 幂等键；这些是安全/稳定性产品改动，不应由本验收矩阵伪装成已通过。
4. **部署：** 已从独立干净 worktree 构建并部署 `0c2d949`，未覆盖服务器上另一个并发任务的工作区；容器健康与真实页面复验均通过。
5. **本任务 AC：** AC-6 已完成；AC-2、AC-3、AC-7 仍因静态 Token 无 TTL、RUNNING 任务无租约/reaper、create-job 无幂等键以及原 BYD 父子任务尚未归档而未完成，因此本任务继续保持 open，不能据此归档。