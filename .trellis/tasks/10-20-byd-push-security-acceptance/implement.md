# Implement — BYD 推送安全验收收口

## 1. Checklist

- [x] 1. 阅读原 `10-16-integration-security-validation/prd.md` 与当前 BYD 推送代码。
- [x] 2. 生成 `research/acceptance-matrix.md`，覆盖原 R1～R4 / AC-1～AC-8。
- [x] 3. 核对前端、Server、Agent、MMKV 四层安全边界。
- [x] 4. 执行后端测试、Agent pytest、前端构建。
- [x] 5. 运行只读服务器/Agent/UI 状态检查，确认部署健康。
- [x] 6. 核对真机成功记录与圈子页面行为，记录覆盖和缺口。
- [x] 7. 若有缺口：记录修复项并执行经批准的最小修复、测试；提交由主会话处理。
- [x] 8. 更新原验收任务 AC；归档原验收子任务改由主会话统一处理（本轮未归档）。
- [ ] 9. 归档 `10-16-byd-dynasty-local-push`，提交父子任务元数据。
- [ ] 10. 归档本任务并验证 `task.py list` 不再出现 BYD 僵尸任务。

## 2. Validation commands

后端：

```powershell
$env:JAVA_HOME = 'C:\Program Files (x86)\Common Files\JDK\jdk-21'
mvn test
```

Agent：

```powershell
Set-Location agent/byd-dynasty
python -m pytest -q
Set-Location ../..
```

前端：

```powershell
Set-Location frontend
npm run build
Set-Location ..
```

服务器只读：

```powershell
ssh sparkora-dev 'cd /data/code/sparkora && docker compose ps'
ssh sparkora-dev 'cd /data/code/sparkora && curl -sS -o /dev/null -w "frontend=%{http_code}\n" http://127.0.0.1:8088/'
ssh sparkora-dev 'cd /data/code/sparkora && curl -sS -o /dev/null -w "backend=%{http_code}\n" http://127.0.0.1:5661/api/auth/me'
```

## 3. Review gates

- 每个场景标明“已测/代码审查/不可复现并替代验证/保留”。
- 不把一次成功推送当成所有异常场景已验证。
- 测试不修改生产数据行或真实 MMKV。
- 安全测试不输出 Token、`.env` 或密钥。
- 若代码有变更，必须先完成 check 才能归档。

## 4. Rollback

- 发现缺陷：保持任务 open，记录首个失败证据。
- 代码修复失败：revert 修复提交，保留原实现和部署记录。
- 服务器异常：按部署指南保留日志并回滚到部署前提交，不删除数据卷。
- 归档错误：revert 归档提交。

## 5. Output

- acceptance matrix。
- 测试/构建/部署结果。
- 真机覆盖说明。
- 归档提交和服务端状态摘要。
---

## 执行记录（2026-10-21）

- 已生成并更新 `research/acceptance-matrix.md`，其中所有未验证项均明确标记为替代验证或保留。
- 本轮实际测试：`mvn test` 1248/0/0/0；Agent `python -m pytest -q` 60 passed；`npm run build` 通过（4242 modules，26.71s）；Playwright 定向 `project-steps.spec.js` 9 passed（11.5s）。全量 Playwright 首轮因 Windows 视觉基线缺失为 27 passed / 24 failed；生成本地基线后重跑 52 passed，但基线未提交，不能作为干净 checkout 结论。
- 已修复并测试：`BydDynastyPanel.vue` 成功态优先 `job.result.draftId`，仅在旧响应且 `lastJobId` 对齐时回退 `state.lastDraftId`；`project-steps.spec.js` 覆盖该契约、旧 ID 串用负例和 BYD 路由边界；Agent 新增微信/MMKV 缺失/异常/20 条草稿测试；后端新增同 Agent 重复上报测试。
- 保留风险：Agent Token 无 TTL、RUNNING 任务无租约/reaper、create-job 无 API 幂等键；真实草稿箱内容与视频真机未验证。
- 归档步骤 8～10 本轮不执行：主会话负责最终 check、提交、部署与统一归档复核。服务器远程 tracked 工作区存在无关脏改动，部署不得在未处理前执行。
