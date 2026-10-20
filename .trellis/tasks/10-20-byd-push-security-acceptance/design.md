# Design — BYD 推送安全验收收口

## 1. Audit layers

验收按四层建立证据矩阵：

```text
Frontend -> Server API/Service -> Agent -> Local MMKV / BYD CDN
```

每个 R1 场景映射到：

- 入口代码/测试。
- 预期失败或成功行为。
- 实际执行命令/证据。
- 是否阻断 MMKV 写入。

## 2. Server boundaries

重点核对：

- `BydDynastyPushService` 的任务创建、版本解析、状态隔离和媒体解析。
- Agent 认证 Filter/Interceptor 与 `X-Agent-Token` / `X-Agent-Id` 校验。
- 任务按用户和 Agent 归属领取，不可跨用户读取媒体。
- 任务重报、幂等键、超时和草稿数量限制。
- BYD payload v2、固定圈子 pair 和 null pair 语义。

优先使用现有单测和 Mock；禁止通过修改真实 project/version 行构造边界。

## 3. Agent boundaries

重点核对：

- 微信进程运行时拒绝写 MMKV。
- MMKV 缺失/结构异常时拒绝写入并保留备份。
- 媒体下载/比亚迪 CDN 上传失败时不写 MMKV。
- 写入过程异常可恢复，备份可用于比对/恢复。
- Agent 不执行服务端任意命令。
- 失败日志不含 Token 或本机敏感路径。

## 4. Real-device evidence

已有候选成功记录：

```text
2026-10-20 18:35:14
图片 8，视频 0
Agent byd-dynasty-agent-local
微信已退出，预检通过
```

验收矩阵必须注明：

- 该记录覆盖了正常推送、图片、Agent 领取和写 MMKV 的哪些部分。
- 是否单独覆盖视频。
- 是否验证草稿内容而不只是返回成功状态。

## 5. Verification strategy

- `mvn test`：Server/接口/状态机/权限单元测试。
- `python -m pytest -q`：Agent payload、上传、MMKV、失败回滚。
- `npm run build`：前端模板和协议字段。
- `docker compose ps`、HTTP 200/401：仅在运行时部署变化时执行。
- 只读查看当前 UI/Agent 状态可以作为真机辅助证据，不替代安全单测。

## 6. Archive design

不直接删除任务目录。完成原验收子任务后：

1. 更新原任务 AC 和 evidence。
2. `task.py archive 10-16-integration-security-validation`。
3. 确认 BYD 父任务 children 状态。
4. `task.py archive 10-16-byd-dynasty-local-push`。
5. 最后归档本任务。

## 7. Rollback

- 若安全验收发现高风险缺陷，停止归档，创建修复任务或在本任务内完成经批准修复。
- 产品代码变更按 `byd-miniapp-push.md` 与 container-deployment 契约回滚。
- 部署失败按 `docs/deploy.md` 的回滚流程处理。
- 不修改生产环境数据行。
