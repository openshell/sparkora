# prd.md — BYD 本地推送联调与安全验收

> 父任务：`10-16-byd-dynasty-local-push`

## Goal

完成 Sparkora Server、Windows Local Agent 和前端发布页的联调，验证安全边界、失败恢复路径和真机可用性。

## Requirements

### R1 联调场景

必须覆盖：

1. Agent 未启动。
2. Agent 在线但微信仍在运行。
3. Agent 在线但 MMKV 文件缺失。
4. Agent 在线但 MMKV 文件结构异常。
5. BYD 图片上传失败。
6. MMKV 写入中途中断。
7. 草稿数量达到 20。
8. 同一任务重复上报。
9. 多用户权限隔离。
10. 正常推送成功。

### R2 安全验收

- Agent 认证必需。
- 不能用过期或错误 token 领取任务。
- 不能领取不属于授权用户的任务。
- Agent 不得执行 Server 下发的任意命令或脚本。
- 所有 BYD 敏感配置保存在本机 Agent 配置中。
- Server 不保存用户本机路径。

### R3 稳定性验收

- 写入前必须有可用备份。
- 任务失败不能污染公众号发布状态。
- Agent 离线要能从 UI 快速发现。
- 任务超时不能造成双写草稿。
- 重复点击推送不能创建无法追踪的重复任务。

### R4 真机验证

最终需要用户在真实微信 PC 版环境验证：

1. 完全退出微信。
2. Sparkora 创建推送任务。
3. Agent 领取任务并完成本地预检。
4. Agent 上传图片并写入 MMKV。
5. 重开微信。
6. 打开比亚迪王朝小程序草稿箱。
7. 检查标题、正文、换行、图片、车友会标签和草稿数量。

## Acceptance Criteria

- [x] AC-1 所有联调场景有记录。
- [x] AC-2 异常场景全部阻止 MMKV 写入或给出明确恢复指引。
- [ ] AC-3 Agent token 校验与任务归属验证通过（错误 token/归属已通过；过期 token 无 TTL，保留）。
- [x] AC-4 BYD 推送状态与公众号发布状态隔离。
- [x] AC-5 正常真机推送至少成功一次。
- [x] AC-6 备份文件可用来恢复或比对。
- [x] AC-7 `mvn test` 与前端构建均通过。
- [ ] AC-8 输出最终部署 / 启动 / 使用说明。

## Notes

- 本任务不新增大功能，重点是收口和验收。

---

## 验收证据记录（2026-10-21）

- 验收矩阵：`.trellis/tasks/10-20-byd-push-security-acceptance/research/acceptance-matrix.md`，逐项覆盖 R1-1～R1-10、R2、R3、R4 和 AC-1～AC-8。
- R1：Agent 离线、微信进程、MMKV 缺失/异常、图片上传失败、20 条草稿、跨 Agent/跨用户归属和并发领取均有 Mock 或临时文件测试；真实进程中断使用“写入后校验失败并恢复备份”的替代验证，未声称覆盖断电/杀进程。
- R2：错误、空、未配置 Token 被拒绝；任务领取和媒体下载按 owner/Agent 校验；服务端不向 Agent 下发任意命令；服务端不返回用户本机路径；日志脱敏有测试。**静态 Agent Token 没有 TTL/过期机制，过期 Token 边界不能判定为已验证，保留为后续安全项。**
- R3：备份、恢复、BYD/公众号状态隔离和离线发现有证据；**RUNNING 任务没有超时租约/reaper，create-job 没有 API 幂等键**，超时重试和跨标签页重复点击仍保留风险。
- R4：`push-history.jsonl` 有两条真实 SUCCESS 记录；最近一条为 2026-10-20 18:35:13，`draftCount=6`、`serverReported=true`。没有打开真实草稿箱人工核对标题/正文/图片顺序/车友会标签，也没有视频真机记录。
- 测试：后端 `mvn test` 通过（1248/0/0/0）；Agent `python -m pytest -q` 通过（60 passed）；前端 `npm run build` 通过（4242 modules，26.71s）；Playwright 定向 `project-steps.spec.js` 通过（9 passed，11.5s）。Playwright 全量首轮 27 passed / 24 failed，24 个失败均为缺少 `*-win32.png` Windows 视觉基线，不是 smoke/logic 失败；生成本地基线后重跑 52 passed，但这些基线未提交，不能视为干净 checkout 的全量通过。
- 部署：本轮按用户要求未 commit/push/deploy；前端运行时文件有变更，若上线需重建 frontend 镜像。2026-10-21 只读检查远程为 `fix/knowledge-car-sync`@`491c8c9`，backend healthy/frontend 200/backend 401，但远程 tracked 工作区存在无关脏改动；主会话必须先处理并推送明确目标提交，再执行部署健康检查。AC-8 因此保持未勾选。
- 归档：未执行归档；由主会话在 final check 后统一处理。
