# PRD — BYD 推送安全验收收口

## Goal

完成原有 `10-16-integration-security-validation` 的联调、安全、稳定性和真机验收，确认 BYD Server、Windows Local Agent、前端推送页和 MMKV 写入链路达到可归档状态，并正确关闭 BYD 推送父任务。

## Background

- BYD 推送实现已提交并部署。
- 页面已验证固定圈子开关、Agent 在线和预检通过。
- 2026-10-20 18:35:14 已有一次正常推送成功记录：图片 8、视频 0。
- 原验收子任务 `10-16-integration-security-validation` 仍为 planning，其 R1 定义 10 个联调场景，AC-1～AC-8 尚未勾选。
- 本次任务负责证据化与必要修复，不重复重写已实现的推送功能。

## Requirements

### R1 联调场景覆盖

逐项处理原 PRD R1：

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

每项必须有测试、代码审查或真机证据；无法安全复现的项目必须说明原因和替代验证方式。

### R2 安全验收

- Agent 认证必需，错误/过期 Token 被拒绝。
- 任务归属校验阻止跨用户领取和下载。
- Agent 不执行 Server 下发的任意命令或脚本。
- BYD 敏感配置只保存在 Agent 本机配置。
- Server 不持久化用户本机路径。
- 日志和验收记录不泄露 Token、密钥或 `.env` 值。

### R3 稳定性验收

- MMKV 写入前存在可用备份。
- 失败任务不污染公众号发布状态。
- Agent 离线可在 UI 快速发现。
- 超时/重试不造成双写草稿。
- 重复点击不会创建不可追踪的重复任务。

### R4 真机与部署验收

- 核对真实推送成功记录及其覆盖范围。
- 推送页能展示 Agent 在线、预检通过和圈子语义。
- 至少成功写入一条 BYD 小程序草稿；标题、正文、图片/视频、圈子和草稿数量按可获得的证据确认。
- 若代码有改动，执行后端、Agent、前端测试与容器部署健康检查。

### R5 归档顺序

1. 更新并归档 `10-16-integration-security-validation`。
2. 确认全部 children 完成，归档 `10-16-byd-dynasty-local-push`。
3. 归档本任务 `10-20-byd-push-security-acceptance`。

## Acceptance Criteria

- [ ] AC-1 原 R1 的 10 个场景均有证据、替代验证或明确的保留项。
- [ ] AC-2 原 R2 的全部安全边界有代码/测试证据。
- [ ] AC-3 原 R3 的备份、状态隔离、离线发现、超时防双写和重复点击幂等均有结论。
- [ ] AC-4 至少一条正常真机成功记录已写入验收矩阵并说明覆盖范围。
- [ ] AC-5 后端 `mvn test`、Agent `python -m pytest -q`、前端 `npm run build` 通过。
- [ ] AC-6 若运行时变更：镜像重建、容器 healthy、前端 200、后端 401 均通过。
- [ ] AC-7 原有 BYD 父子任务与本任务按顺序归档，`10-20-miniapp-media-push` 关联记录已提交。
- [ ] AC-8 未修改/删除生产数据、MMKV、Token 或用户凭据。

## Out of Scope

- 新增与验收无关的产品功能。
- 对真实用户草稿做破坏性测试。
- 绕过微信客户端安全限制或自动化操作微信 UI。
- 以测试名义修改生产数据库行。

## Dependencies

- 必须先审阅 `10-16-integration-security-validation/prd.md`。
- 必须在归档 BYD 父任务前完成本任务。
- 如发现代码缺陷，必须先修复、验证、提交，再继续归档。
