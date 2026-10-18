# prd.md — BYD 推送任务模型与 Server Agent API

> 父任务：`10-16-byd-dynasty-local-push`

## Goal

定义并实现 Sparkora 服务端的 BYD 王朝推送编排层：数据模型、任务状态机、Agent 注册/心跳/领取/回传 API，以及草稿 payload 编译。

## Requirements

### R1 数据模型

新增 Flyway 迁移，包含：

1. `sparkora_byd_dynasty_agent`
   - agent 唯一 ID；
   - 绑定用户；
   - 机器名；
   - OS 与 Agent 版本；
   - 在线状态；
   - 最近心跳时间；
   - 最近预检 JSON。

2. `sparkora_byd_dynasty_push_job`
   - job ID；
   - project/version；
   - agent；
   - 状态：`PENDING / CLAIMED / RUNNING / SUCCESS / FAILED / EXPIRED`；
   - payload JSON；
   - result JSON；
   - 错误信息；
   - 创建人、领取时间、开始时间、完成时间。

3. `sparkora_article_project` 增加独立 BYD 字段：
   - `byd_dynasty_published_at`；
   - `byd_dynasty_last_job_id`；
   - `byd_dynasty_last_draft_id`；
   - `byd_dynasty_last_draft_count`；
   - `byd_dynasty_club_id`；
   - `byd_dynasty_club_title`；
   - `byd_dynasty_last_error`。

### R2 Agent API

实现：

```http
POST /api/agent/byd-dynasty/register
POST /api/agent/byd-dynasty/heartbeat
POST /api/agent/byd-dynasty/claim-job
POST /api/agent/byd-dynasty/jobs/{id}/result
```

要求：

- Agent 必须使用专属认证。
- Agent 只能领取绑定用户或授权范围内的任务。
- 任务领取具有排他性。
- 同一个任务只能由同一 Agent 上报结果。
- 心跳超时后 Agent 标记 OFFLINE。

### R3 Push Job API

面向项目提供：

```http
GET /api/projects/{projectId}/byd-dynasty/state
POST /api/projects/{projectId}/byd-dynasty/push-jobs
GET /api/projects/{projectId}/byd-dynasty/push-jobs/{jobId}
```

要求：

- 创建任务前校验用户角色、项目状态、当前版本和正文可推送性。
- Agent 离线或最近预检失败时返回 400并提供明确中文原因。
- 任务必须带唯一 job id，Agent 可据此重试或防止重复上报。
- 推送失败只写 BYD 字段，不修改公众号发布字段。

### R4 草稿 payload

Server 编译包含：

- 标题；
- 正文 message 列表；
- 图片源信息；
- clubId / clubTitle；
- draftId；
- project/version/job 痕迹字段；
- payload 版本号。

禁止：

- payload 中泄露本机 MMKV 路径；
- 把 MMKV 加密逻辑放到 Server；
- 直接把本地环境检测结果当作缓存真相。

## Acceptance Criteria

- [ ] AC-1 Flyway 迁移可执行且幂等。
- [ ] AC-2 Agent 注册 / 心跳接口可更新在线状态。
- [ ] AC-3 Agent 领取任务具有并发排他性。
- [ ] AC-4 Agent 离线时创建任务返回 400。
- [ ] AC-5 推送任务状态可正确流转并被审计。
- [ ] AC-6 Server 生成的 payload 符合 Local Agent 契约。
- [ ] AC-7 BYD 推送失败不影响公众号发布字段。
- [ ] AC-8 `mvn test` 通过。

## Notes

- 相关规约参考：
  - `.trellis/spec/backend/index.md`
  - `.trellis/spec/backend/database-guidelines.md`
  - `.trellis/spec/backend/error-handling.md`
  - `docs/spec/publish.md`
