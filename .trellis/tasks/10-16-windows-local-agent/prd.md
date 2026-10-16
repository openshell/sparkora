# prd.md — Windows BYD Local Agent 执行器

> 父任务：`10-16-byd-dynasty-local-push`

## Goal

实现运行在微信 PC 版所在 Windows 机器上的 BYD 王朝小红书小程序 Local Agent，负责真实本地检测与草稿写入。

## Requirements

### R1 运行形态

- Agent 是本机可执行程序或脚本，运行环境为 Windows。
- Agent 通过 Sparkora API 领取任务和回传结果。
- Agent 不暴露公网端口。
- Agent 配置保存在本地，不上传敏感配置到 Server。

### R2 本地配置

Agent 配置至少包含：

```text
SPARKORA_BASE_URL
AGENT_TOKEN / AGENT_ID
BYD_DYNASTY_MMKV_DIR
BYD_DYNASTY_UPLOAD_URL
BYD_DYNASTY_SIGN_KEY
BYD_DYNASTY_UPLOAD_SESSION_ID
BYD_DYNASTY_CLUB_ID
BYD_DYNASTY_CLUB_TITLE
BACKUP_DIR
```

必须支持本地文件、环境变量或二者结合；禁止把配置写死进代码。

### R3 预检

Agent 必须实现完整预检：

1. OS 是 Windows。
2. 微信 MMKV 目录存在。
3. 主文件和 crc 文件存在。
4. 主文件结构可解析。
5. crc 中 IV 和 actual length 可读取。
6. 文件可读可写。
7. 微信相关进程完全退出。
8. 磁盘空间足够。
9. 备份目录可写。
10. Agent 与 Server 通信正常。

预检未通过不得领取或执行 MMKV 写入。

### R4 图片处理与上传

- Agent 从任务 payload 或授权后的 Sparkora 接口获取图片。
- 长边超过 1920px 时缩放。
- 统一转 JPEG。
- 上传到 BYD 图片接口。
- 每张图片间随机延迟 800–2500ms。
- 上传失败时任务置为 FAILED，不写入 MMKV。

### R5 MMKV 写入

- 读取 `platformDrafts` 当前值。
- 校验草稿上限 20。
- 追加新的草稿。
- 图片块后必须跟随 sibling 空 text。
- 换行必须是真实 `\n`。
- 手写 CFB128 续接逻辑必须与现有 MMKV 加密格式一致。
- 写入主文件和 crc 文件。

### R6 备份与验证

- 写入前备份主文件和 crc 文件。
- 写入后重新读取并确认：
  - 本次 draftId 存在；
  - 标题一致；
  - message 数量一致；
  - 图片 URL 正确；
  - platformDrafts 结构可解析。
- 任一校验失败必须上报 FAILED，并提示恢复备份思路。

### R7 结果回传

Agent 回传至少包含：

```text
jobId
status
draftId
draftCount
publishedAt
uploadedImageUrls
preflightSummary
error
logsSummary
```

## Acceptance Criteria

- [ ] AC-1 Agent 能注册并通过心跳更新 Server 状态。
- [ ] AC-2 预检 JSON 覆盖上述检查项。
- [ ] AC-3 微信运行时任务被拒绝。
- [ ] AC-4 MMKV 目录或文件异常时任务被拒绝。
- [ ] AC-5 写入前产生备份。
- [ ] AC-6 写入后重新解析 MMKV 成功。
- [ ] AC-7 图片上传失败时不发生 MMKV 写入。
- [ ] AC-8 任务结果能正确回传 Server。

## Notes

- 可基于 `D:\code\AI\weixin\auto_draft.py` 的核心逻辑改造，但不将其作为 Server 端运行依赖。
- 不做自动杀微信进程。
