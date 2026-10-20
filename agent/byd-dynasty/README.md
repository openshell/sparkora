# Sparkora BYD Dynasty Windows Local Agent

本模块运行在安装微信 PC 的 Windows 电脑上，负责 Sparkora 服务端不能安全完成的本地动作：

- 注册/心跳 Sparkora Server；
- 完成本地预检；
- 从 Sparkora 任务 payload 获取图片、压缩并上传 BYD 图片接口；
- 备份 BYD 小程序 MMKV 文件；
- 向小程序草稿箱追加草稿；
- 重新解析 MMKV 并校验本次结果；
- 将成功/失败结果回传 Sparkora Server。

Agent 不暴露 TCP 监听端口，也不使用浏览器扩展或油猴脚本。

## 一键使用（Windows 推荐）

双击：

```text
Start-BYDAgent.cmd
```

会打开「BYD 推送助手」控制面板，并自动在后台启动 Agent，不需要手动打开
PowerShell、设置环境变量或输入命令。

控制面板提供：

- Agent 进程状态与 PID；
- 微信进程检测；
- 最近一次预检结果；
- 启动 Agent / 安全停止；
- 「推送历史」页签：按任务归并展示最新状态、草稿数与失败原因；
- 「运行日志」页签：展示统一 Agent 日志；
- 打开配置、统一日志、推送历史文件和发布页。

关闭控制面板不会停止后台 Agent。点击「安全停止」会写入停止标记，
Agent 在当前任务执行完毕后的调度周期退出，不会强杀正在写入 MMKV 的进程。

日志与历史位置：

```text
work/agent.log              # 统一 Agent 日志（新版本优先读取）
work/push-history.jsonl     # 推送事件流（按任务保留最新状态）
work/control.log            # 控制面板自身异常日志
work/agent.stdout.log       # 旧版日志，仅在 agent.log 为空时兼容读取
work/agent.stderr.log       # 旧版日志，仅在 agent.log 为空时兼容读取
```

Agent 日志每行格式为本地时间 `YYYY-MM-DD HH:MM:SS [LEVEL] message`，并立即
刷新到文件。领取、开始、成功、失败都会记录；空任务轮询不会每 5 秒重复写
日志。推送历史只保存任务 ID、项目/版本、标题、草稿数、耗时和安全错误信息，
不保存 Token、本机路径、payload 正文、图片 URL 或签名参数。

## 部署

1. 将 `agent/byd-dynasty` 目录复制到 Windows 电脑；
2. 准备 Python 3.11+；
3. 安装依赖：

```powershell
python -m pip install -r requirements.txt
```

4. 复制并填写本地配置（不要提交真实配置）：

```powershell
Copy-Item config.example.env config.env
notepad config.env
```

5. 先做预检：

```powershell
python .\byd_local_agent.py preflight
```

6. 启动守护进程：

```powershell
python .\byd_local_agent.py run
```

如需只执行一个调度周期，可使用：

```powershell
python .\byd_local_agent.py once
```

## 配置

必填：

| 变量 | 说明 |
| --- | --- |
| `SPARKORA_BASE_URL` | Sparkora 服务端地址 |
| `SPARKORA_AGENT_TOKEN` | 与服务端 `BYD_DYNASTY_AGENT_TOKEN` 相同 |
| `SPARKORA_AGENT_ID` | 本机稳定 Agent ID |
| `BYD_DYNASTY_MMKV_DIR` | BYD 小程序 `usrmmkvstorage0` 目录 |
| `BYD_DYNASTY_UPLOAD_URL` | BYD 图片上传地址 |
| `BYD_DYNASTY_UPLOAD_SIGN_KEY` | BYD 上传签名密钥 |
| `BYD_DYNASTY_UPLOAD_SESSION_ID` | BYD 上传会话 ID |

可选：`BYD_DYNASTY_CLUB_ID`、`BYD_DYNASTY_CLUB_TITLE`、`BYD_DYNASTY_BACKUP_DIR`、
`BYD_DYNASTY_MIN_FREE_DISK_MB`、`BYD_DYNASTY_HEARTBEAT_INTERVAL_SECONDS`、
`BYD_DYNASTY_POLL_INTERVAL_SECONDS`。

## 预检

Agent 会检查：

- Windows 环境；
- MMKV 主文件与 `.crc` 文件存在、可读、可写；
- `platformDrafts` 可解析；
- 微信相关进程已完全退出；
- 磁盘剩余空间；
- 备份目录可写；
- Sparkora Server 可访问。

预检结果会上传到 Sparkora Server；JSON 中不包含本机路径。

## 写入与备份

写入前一定会备份 MMKV 主文件和 `.crc` 文件。写入后 Agent 会重新解密解析
`platformDrafts`，校验本次 `draftId`、标题、消息序列和图片 URL。若落盘失败，
会尝试恢复备份并回传 FAILED。写草稿前必须完全退出微信，包括系统托盘。

## 故障排查

- 双击后没有窗口：查看 `work/control.log`，确认 Python 3.11+ 已安装；缺少
  `pythonw.exe` 时启动脚本会回退到 `python.exe`。
- Agent 启动后立即退出：打开「运行日志」，先检查 `config.env` 是否缺失必填项。
- 显示微信进程仍在运行：从系统托盘完全退出微信，并结束
  `WeChatAppEx.exe` / `Weixin.exe` 后再重新预检。
- 点击安全停止后仍显示运行中：如果 Agent 正在上传图片或写入 MMKV，控制面板
  会提示「任务完成后退出」。不要手动结束进程，等待当前任务结束。
- 推送历史没有新增记录：检查 `work/push-history.jsonl` 是否可写；历史写入失败
  不会中断任务执行。
- 日志出现结果回传失败：本地草稿可能已经写入成功。历史记录会保留
  `SUCCESS` 并把「结果已回传服务端」标为否，请先确认本机草稿箱，再联系管理员。

## 安全要求

- `config.env` 只保存在本机，不能提交到 Git；
- 不要将 Agent Token、MMKV 路径、上传签名密钥写入任务 payload；
- `config.env` 与备份目录应备份在可信位置；
- 建议创建专用 Windows 用户或最少权限目录运行 Agent。
