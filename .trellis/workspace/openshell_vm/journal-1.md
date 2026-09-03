# Journal - openshell_vm (Part 1)

> AI development session journal
> Started: 2026-09-03

---



## Session 1: 配图并入预览:移除 IMAGES_READY 与独立配图步骤
<!-- trellis-session: v=2 fp=82617d6bf8a0a981 -->

**Date**: 2026-09-03
**Task**: 配图并入预览:移除 IMAGES_READY 与独立配图步骤
**Branch**: `s4-preview-publish`

### Summary

将独立配图步骤并入预览,流程五步改四步;彻底移除 IMAGES_READY 状态,VERSIONS_READY 后直接可预览/发布;预览工具栏新增配图面板(图库插入+AI生图);删除 complete-images 接口与 StepImages.vue;同步 spec 文档。附带提交工作区遗留的 S6 多车型关联改动。

### Git Commits

| Hash | Message |
|------|---------|
| `e43842c` | feat(S6): 配图并入预览步骤,移除 IMAGES_READY 状态与独立配图步骤 |

### Status

[OK] **Completed**

## 2026-09-03 · S6.1 知识库必查+降级可见(rag-mandatory-gate)

**Branch**: `feat/qiniu-only-image-library`(沿用当前分支,不建 PR)

### Progress

- [x] CarRagService: RagStatus/RagResult/retrieveForGeneration(必查入口,不抛异常)
- [x] AiProperties/application.yml/.env.example: ragMinScore=0.3 / ragRejectScore=0.5 配置化
- [x] BriefService/VersionService: 必查+降级可见+factRisks 标注+rag_status 落库
- [x] StepBrief.vue/StepVersions.vue 检索状态标签
- [x] docs/s0-spec.md §6b 契约节 + §7b 配置表
- [x] mvn test 6/6 绿(新增 CarRagServiceTest)+ npm build 通过
- 留真机验收:AC1 检索失败模拟(dev.sh 联调断 embedding)、AC3/AC4 真实生成回归

### Notes

- 门槛语义:相似度衡量相关性非事实正确性;防知识库错误数据靠入库源头(PRD 已写明)。
- task.py start 提示 base_branch 即当前分支;用户流程历来在当前分支直接提交,不建 PR,可接受。
