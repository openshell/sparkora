# design.md — BYD Agent Windows 一键控制面板

## Boundary

改动限定在 `agent/byd-dynasty`。服务端、前端发布页和 Agent 任务协议不变。

## Control Panel

- UI：Python `tkinter/ttk`，使用 `pythonw.exe` 启动以避免黑色控制台窗口。
- 进程：控制面板通过 `subprocess.Popen` 启动 `byd_local_agent.py run`，使用 `CREATE_NO_WINDOW`，stdout/stderr 追加到 `work/` 日志。
- 状态：PID 保存在 `work/agent.pid`；控制面板通过 Windows 进程 API 判断存活。
- 微信检测：复用 `tasklist` 判断 `weixin.exe` / `wechatappex.exe`。
- 预检：后台线程调用 `byd_local_agent.py preflight`，解析 JSON，不阻塞 UI。
- 配置：只展示配置路径，不读取或显示敏感值。
- 发布页：从 `SPARKORA_BASE_URL` 的主机推导前端地址；默认使用 `8088` 端口。

## Graceful Stop

- 控制面板写入 `work/agent.stop`。
- `run_loop` 在每个调度周期开始时检查该文件；存在时删除并退出。
- 若正在执行任务，循环不会中断当前写入；控制面板只等待并提示，不强杀。

## Startup

- `Start-BYDAgent.cmd` 使用 `pythonw.exe` 启动控制面板。
- 若 `pythonw.exe` 不在 PATH，回退到当前 Python 解释器。
- 控制面板打开后自动检查进程，未运行则启动 Agent。

## Testing

- 单元测试覆盖 PID 存活判断、预检输出解析、停止标记和微信进程解析。
- Windows 手工冒烟：双击入口、自动启动、状态刷新、安全停止、日志/配置/发布页入口。

## 增量设计：日志与推送历史

### Event Flow

```text
Server claim-job
  -> process_claimed_job
  -> push-history.jsonl: RECEIVED
  -> payload parse + local preflight
  -> push-history.jsonl: RUNNING
  -> execute_job
  -> push-history.jsonl: SUCCESS / FAILED
  -> control panel reducer: latest event per jobId
```

### Logging

- `byd_local_agent.py` 使用统一 `log(level, message)` 输出本地时间戳。
- 新日志统一写入 `work/agent.log`，子进程 `stderr` 合并到同一文件。
- 预检状态只在变化时输出；空任务轮询不重复记录。
- 任务事件只记录安全元数据，不记录 payload 正文、图片 URL、Token 或本机路径。

### History Storage

- `work/push-history.jsonl` 为追加式事件流，单行一个 JSON。
- 事件状态：`RECEIVED`、`RUNNING`、`SUCCESS`、`FAILED`。
- `read_push_history()` 忽略损坏行、按 `jobId` 取最新事件、按时间倒序返回。
- 事件文件限制最大事件数量，避免无限增长。

### Control Panel

- 现有日志区改为页签：`推送历史` + `运行日志`。
- 历史页签使用 `ttk.Treeview` 展示时间、状态、项目、标题、草稿数、任务 ID。
- 选中历史记录后显示完整失败原因或任务详情。
- 刷新历史时不重建未变化的表格，避免闪烁。