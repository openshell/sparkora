# 状态机推进逻辑收敛 实施计划

> 单一重构单元,一次实施;风险集中在「语义逐字等价」,靠单测 + AC1/AC2 grep 兜底。

## Step 1: 新建 ProjectStatusService + 单测

- [x] `src/main/java/com/sparkora/service/ProjectStatusService.java`(按 design.md 签名)
- [x] `src/test/java/com/sparkora/service/ProjectStatusServiceTest.java`:每方法断言 WHERE 白名单/两拆分/截断口径(20 用例全绿)
- 验证:`mvn -q -DskipTests compile` + `mvn test -Dtest=ProjectStatusServiceTest` ✅

## Step 2: 调用方逐个委托改造(每改一个编译一次)

- [x] 2a BriefService(删私有 claimGenerating/stuckGenerating/常量/projectStatusGuardMsg)
- [x] 2b ImitationService(同上;imitation_analysis 经 extraCols 同条 UPDATE)
- [x] 2c VersionService(删 stuckGenerating/常量)
- [x] 2d DeepController.generate(删死代码 projectMapper 字段)
- [x] 2e PublishService(markPublished/markPublishFailure;markFailure 留纯委托门面,控制器契约不变)
- [x] 2f ClarifyService(writeBriefError;STALE_PLANNING_MS 改引状态服务常量)
- 验证:每步 `mvn -q -DskipTests compile`;全部完成后 `mvn test` ✅

## Step 3: 既有测试同步

- [x] BriefServiceTest wrapper 断言改为 verify(ProjectStatusService) 参数捕获(保留断言意图);DeepControllerContractTest 构造签名同步
- 验证:`mvn test` 全绿 ✅

## Step 4: 全量验证 + 文档同步

- [x] `grep -rn 'set("status"' src/main/java`(仅 ProjectStatusService + CarSyncJob/NewsSyncJob;ArticleProjectController 为 setStatus INSERT 初始态)
- [x] `grep -rn "STALE_GENERATING_MS" src/main/java`(仅 ProjectStatusService 一处定义,ClarifyService 为引用)
- [x] `mvn test`(345 全绿) + `cd frontend && npm run build`(通过,前端零改动)
- [x] docs/README.md 4.2 注状态写权收敛;.trellis/spec/backend/error-handling.md 状态机服务段同步;database-guidelines.md 原子抢占段补指向

## 风险与回滚点

| 步骤 | 风险 | 回滚 |
|---|---|---|
| Step 2 | 委托改造时遗漏一处 WHERE 条件(语义漂移) | AC4 单测逐项断言;整 commit revert |
| Step 3 | 既有测试 verify 目标变更误删断言意图 | review 断言逐条对照 |

## 提交约定

- 单笔:`refactor(s2): 项目状态机推进收敛到 ProjectStatusService`
- 中文 message,scope 惯例

## start 前检查

- [x] implement.jsonl / check.jsonl 已填
- [x] 用户已确认规划摘要