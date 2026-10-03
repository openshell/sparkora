# C4 写作按映射取用

> 父任务:`../10-03-gen-cognitive-redesign`。
> **依赖:C3**(需已确认的 WritingBlueprint)。

## Scope

`DeepWriterService.write` 从「整本 fact_sheet 注入」改为**按蓝图 argumentStructure 逐节、按 evidenceMap.entryKeys 硬约束取用**;数值回查升级为白名单比对。

## Acceptance Criteria

- [ ] 逐节写作,每节仅注入该节 `entryKeys` 对应 fact 条目(按 key 从 fact_sheet 投影)。
- [ ] prompt 硬约束:蓝图未映射的 fact/数值不得进入正文;`MISSING` 论点写定性陈述或标待核实。
- [ ] 数值回查升级:蓝图允许集合外的正文数值 → `fact_risks` high。
- [ ] 保留元话语三层防线、按 kind 分组、截断提额重试。
- [ ] 单测:越界数值被标、未映射证据不进 prompt、无回归;`mvn test` 通过。

## Out of Scope

- 蓝图生成(C3)、UI(C5)。
