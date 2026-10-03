# C2 研究规划拆分

> 父任务:`../10-03-gen-cognitive-redesign`。
> **依赖:C1**(需 TaskBrief 作为输入)。

## Scope

把纯事实研究规划从澄清中剥离为独立 `ResearchPlannerService`(入参 TaskBrief),`research_plan` 结构收敛为**纯事实子问题**(不再混装意图题),驱动既有并行研究链路。

## Acceptance Criteria

- [ ] `ResearchPlannerService` 基于 TaskBrief 产出 `research_plan{keyQuestions[](仅事实),dataNeeds[],hypotheses[],toolHints[]}`。
- [ ] 现有 `ensureBackgroundQuestion` 确定性兜底迁移至研究规划侧,幂等;背景题不进入用户澄清表单。
- [ ] `ClarifyPlanDto` 拆分/重命名,移除意图与事实混装字段。
- [ ] `DeepResearchService`(selectResearchWindow/isBackgroundQuestion 引用点)对齐新结构。
- [ ] 规划仅含事实问题(用例验证);`mvn test` 通过。

## Out of Scope

- 澄清对话交互(C1)、蓝图(C3)。
