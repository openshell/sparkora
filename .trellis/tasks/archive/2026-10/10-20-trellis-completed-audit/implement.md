# Implement — Trellis 完成态审计

## 1. Checklist

- [x] 1. 记录起点：HEAD、`task.py list`、目标任务状态。
- [x] 2. 创建 `research/evidence-matrix.md`，先列全部验收项。
- [x] 3. 审计 `00-join-c-cha` 并归档。
- [x] 4. 审计 `10-12`：检查代码，运行前端构建与 Playwright。
- [x] 5. 审计 `10-19`：核对 AC 与真机/浏览器证据。
- [x] 6. 审计 `10-04`：核对全部子任务和最终集成要求。
- [x] 7. 审计 `10-05`：逐项核对 AC-P1～AC-P10。
- [x] 8. 对可归档任务更新验收记录并逐一归档。
- [x] 9. 单独提交、检查和推送。
- [x] 10. 运行 `task.py list` 确认只剩真实开放任务。

## 2. Validation

```powershell
git status --short --branch
git diff --check
python ./.trellis/scripts/task.py list
python ./.trellis/scripts/task.py list-archive
```

前端：

```powershell
Set-Location frontend
npm run build
npx playwright test
Set-Location ..
```

后端（仅在父级最终评审需要时）：

```powershell
$env:JAVA_HOME = 'C:\Program Files (x86)\Common Files\JDK\jdk-21'
mvn test
```

## 3. Review gates

- 证据矩阵没有空项或“推测通过”。
- 任务归档前核对 target path 和 task.json `status=completed`。
- 父任务归档前列出 children completed 状态。
- 不把真机未验证项写成已验证。
- 任何代码改动都必须另走实现任务/子代理和 check。

## 4. Rollback

- 归档或文档错误：revert 对应提交。
- 测试失败：保留任务 open，记录失败命令和首个错误。
- 发现产品缺陷：停止归档相关任务，登记修复后再继续。

## 5. Follow-up

- 将审计结论写入任务 research 和最终说明。
- 若本任务产生可复用规则，按 `trellis-update-spec` 判断是否更新 spec。
