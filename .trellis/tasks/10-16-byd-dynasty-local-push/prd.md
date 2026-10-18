# prd.md — BYD 王朝小程序本地推送集成

> 架构结论：`Sparkora Server` 不直接写用户本机微信 MMKV；推送能力由运行在微信所在 Windows 机器上的 `BYD Dynasty Local Agent` 完成。

## Goal

1. 在 Sparkora 发布工作台中新增「比亚迪王朝小程序」草稿推送渠道。
2. 用户仍可继续使用现有公众号发布链路，两个渠道状态互不污染。
3. Server 负责任务创建、权限、审计、状态机与草稿 payload。
4. Windows Local Agent 负责本机环境检测、备份、图片上传、MMKV 写入与结果回传。

## Background

- 公众号发布是 server-to-server，不需要客户端检测。
- BYD 王朝小程序草稿依赖微信 PC 版本地 MMKV 文件，只能在用户微信所在 Windows 机器上执行。
- 因此 BYD 推送必须拆为「云端编排」和「本地执行」两层。

## Requirements

### R1 架构分层

- Sparkora Server 只做业务编排、任务调度、状态记录和 API。
- Local Agent 只在本机执行环境检测、MMKV 读写、图片上传和备份恢复。
- Server 不得尝试访问用户本机文件系统或微信进程。

### R2 本地安全门禁

- Agent 必须提供预检能力，检测项包括：
  - Windows OS；
  - 本地配置；
  - 微信 MMKV 目录与主文件 / crc 文件；
  - 文件可读可写；
  - MMKV 结构可解析；
  - 微信相关进程完全退出；
  - 备份目录可写；
  - 磁盘空间充足。
- 任一项失败必须阻止写入。
- 不自动杀微信进程。

### R3 推送执行

- Agent 执行任务前必须再次完整预检。
- 写入 MMKV 前必须备份主文件和 crc 文件。
- Agent 完成写入后必须重新解析 MMKV，验证草稿确实追加成功。
- 只有本地校验通过后才允许上报 SUCCESS。

### R4 图片链路

- Agent 负责从 Sparkora 下载任务图片。
- 图片压缩策略沿用参考项目：
  - 长边 > 1920px 时缩放；
  - 统一转 JPEG；
  - 上传间隔随机 800–2500ms。
- BYD 图片上传成功后使用返回的可访问 URL 构造草稿。

### R5 前端体验

- 发布页可切换发布目标：
  - 公众号草稿箱；
  - BYD 王朝小程序。
- BYD 渠道必须展示：
  - Agent 是否在线；
  - 本地预检结果；
  - 推送任务状态；
  - 成功时间 / 草稿数量；
  - 失败原因；
  - 微信退出提醒。
- Agent 离线或预检失败时禁用推送按钮。

### R6 可审计

- Server 记录任务创建人、目标 Agent、执行时间、结果和错误。
- 项目维度保存最近 BYD 推送信息和最近失败原因。
- 任务必须可追踪，不能只在前端展示一次性结果。

## Out of Scope

- 不做油猴脚本。
- 第一阶段不做 Edge 插件，后续只作为 Agent 的 UI 增强层。
- 不做多微信账号自动切换。
- 不做服务端直连用户本机 MMKV。
- 不做定时自动推送。

## Acceptance Criteria

- [ ] AC-1 Server API 可创建 BYD 推送任务并记录初始状态。
- [ ] AC-2 Agent 可注册、心跳、领取任务、回传结果。
- [ ] AC-3 Agent 预检失败时任务变为 FAILED，且不发生 MMKV 写入。
- [ ] AC-4 Agent 写入前产生主文件与 crc 备份。
- [ ] AC-5 Agent 写入后重新解析 MMKV，确认草稿追加成功。
- [ ] AC-6 前端可见 Agent 在线状态、预检结果和任务状态。
- [ ] AC-7 Agent 离线或预检失败时推送按钮不可用。
- [ ] AC-8 BYD 推送结果不影响公众号发布字段与状态。
- [ ] AC-9 `mvn test` 与 `frontend npm run build` 通过，并完成至少一次真机草稿验证。

## Notes

- 本任务拆为四个子任务：
  1. Server 任务模型与 Agent API；
  2. Windows Local Agent 执行器；
  3. 发布页渠道接入；
  4. 联调与安全验收。
- 分支：`feature/byd-dynasty-miniapp-push`。
