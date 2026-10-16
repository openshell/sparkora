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

## 安全要求

- `config.env` 只保存在本机，不能提交到 Git；
- 不要将 Agent Token、MMKV 路径、上传签名密钥写入任务 payload；
- `config.env` 与备份目录应备份在可信位置；
- 建议创建专用 Windows 用户或最少权限目录运行 Agent。
