# implement.md — BYD Agent Windows 一键控制面板

## Checklist

- [x] 新增 `byd_agent_control.py` 控制面板与进程管理逻辑。
- [x] 在 `byd_local_agent.py` 的 `run_loop` 增加停止标记检查。
- [x] 新增 `Start-BYDAgent.cmd` 双击入口。
- [x] 新增控制面板单元测试（12 passed）。
- [x] 更新 `agent/byd-dynasty/README.md`。
- [ ] 运行 Agent 单测。
- [x] Windows 冒烟验证控制面板启动、安全停止、重启与状态刷新。

## Validation

```powershell
cd D:\code\AI\sparkora\agent\byd-dynasty
$env:PYTHONUTF8="1"
python -m pytest -q
```

```powershell
Start-Process .\Start-BYDAgent.cmd
```

## Rollback

删除控制面板、启动脚本和对应测试；恢复 `run_loop` 原实现即可。Agent 主执行逻辑和配置不受影响。
