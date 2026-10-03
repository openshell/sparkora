# C3 简报写作蓝图

> 父任务:`../10-03-gen-cognitive-redesign`。
> **依赖:C1、C2**(需 TaskBrief + research_plan + fact_sheet)。

## Scope

简报定位从「手册摘要」改为**写作蓝图**:thesis + 论证结构 + **evidenceMap**(论点→证据→绑定 fact_sheet entry key)+ narrativeArc + constraints + 缺口 + 质量信号;并提供**人工评审门**。

## Acceptance Criteria

- [ ] `BlueprintService` 产出 `WritingBlueprint`(含 thesis/argumentStructure/evidenceMap/narrativeArc/constraints/gaps/quality)。
- [ ] evidenceMap 绑定 `fact_sheet.entries[].key`;coverage(COVERED/PARTIAL/MISSING)确定性计算;MISSING 入 gaps。
- [ ] 质量信号:argumentDensity/evidenceCoverage/gapCount/taskBriefConsistency 可计算并落库。
- [ ] `blueprint_status=REVIEWING` 落库;`POST /deep/blueprint/confirm` 置 CONFIRMED 解锁写作;`/deep/status` 透出蓝图。
- [ ] `BriefService.generateFromFactSheet` 委托/退役,保留异步/截断重试/状态守护。
- [ ] 单测:绑定+coverage、质量信号、评审门流转;`mvn test` 通过。

## Out of Scope

- 写作取用(C4)、UI(C5)。
