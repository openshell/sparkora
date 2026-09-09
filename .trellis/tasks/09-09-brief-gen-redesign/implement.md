# 执行计划:检索设置页 + 取消快速模式 + 知识库可暂停

> 前置:prd.md + design.md 已定;按序执行,每步末有验证命令。

## Step 1 后端设置模块(表+服务+接口)

- [ ] `schema.sql` 增 `sparkora_setting` 幂等建表(含 deleted 列)
- [ ] `domain/entity/SettingEntity` + `mapper/SettingMapper`
- [ ] `service/SettingService`:单行读写 + 内存缓存,写刷缓存;`isKbEnabled()/isWebSearchEnabled()`
- [ ] `web/controller/SettingController`:GET(ADMIN/EDITOR) + PUT(ADMIN, `@Valid SettingUpdateDto`)
- [ ] DTO:`domain/dto/SettingUpdateDto`
- 验证:`mvn -q -DskipTests compile`

## Step 2 深度链路接入设置开关

- [ ] `SubAgentRunner` 装配资料工具前查设置:kbEnabled=false → 仅 WEB SearchTool;webSearchEnabled=false → 无资料工具 + prompt 注入「未检索任何外部资料,factRisks 必须标注」
- [ ] `DeepResearchService`/`DeepWriterService`/`FactSheetService`:rag_status DISABLED 分支;来源可信度文案随开关调整
- 验证:`mvn -q -DskipTests compile`;联调:kb=false 生成一次,日志确认子代理工具装配 + rag_status=DISABLED

## Step 3 取消快速模式(入口收敛)

- [ ] `ArticleProjectService.create`:genMode 恒写 DEEP(DTO 字段忽略)
- [ ] FAST 生成路由入口封死(BriefController/ArticleProjectController 中 FAST 分支拒绝或移除)
- [ ] 存量 FAST 项目重新生成 → 深度流程(状态守护沿用)
- 验证:`mvn -q -DskipTests compile`;联调:新建项目无模式入参,生成走 /deep/clarify

## Step 4 前端设置页 + 模式移除 + DISABLED 展示

- [ ] `/settings` 路由+页面(el-switch×2、说明文案、保存 PUT、非 ADMIN 隐藏写入口)
- [ ] 创建页移除模式单选;车型关联改可选折叠区(锚点语义)
- [ ] `CitationList`/简报页/版本卡片:DISABLED 文案「知识库已停用(全局设置)」
- [ ] 存量 FAST 项目 StepBrief 一次性「生成流程已升级」提示
- 验证:`npm run build`;联调:页面读写设置生效

## Step 5 规格三处同步 + 车型残留清理(R4)

- [ ] `docs/s0-spec.md`:§6b/6c 增 DISABLED 与设置开关语义;§7 深度模式标唯一模式(快速模式作废);设置 API 契约字段级;`sparkora_setting` 表结构
- [ ] 车型残留核实:锚点文案/提示词中「必须/绑定」表述清理(保留锚点加权)
- 验证:`grep -n` 抽查 spec 与代码一致性;`mvn -q -DskipTests compile` + `npm run build` 终验

## Step 6 端到端验收(对照 PRD AC)

- [ ] AC1~AC7 逐条过(dev.sh 联调起环境,深度流程全链路走一遍)
- [ ] `09-03-rag-mandatory-gate` 任务 notes 标注 supersede,一并处理归档

## 回滚点

- 每 Step 独立可 revert;`sparkora_setting` 表幂等,残留无害。
- Step 3(模式收敛)是最大行为变更点,若联调暴露深度流程不可用的阻断性问题,单点 revert Step 3 即恢复快速模式。

## review gate

- Step 2/3 完成后:`trellis-check` 全量检查一次;Step 6 前前端 build + 后端 compile 必须双绿。