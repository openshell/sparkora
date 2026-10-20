# implement.md — 渠道化发布:自由编辑区与分渠道推送

> 前置:prd.md、design.md;按序执行,每步带验证命令

## 执行清单(有序)

### 步骤 1:数据层 — channel 列

- [x] 新建 `src/main/resources/db/migration/V24__project_channel.sql`（main 已占用 V21~V23，迁移号顺延为 V24）(`ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'gzh'`;注释含任务号)
- [x] `ArticleProjectEntity` 加 `channel` 字段
- [x] 验证：`mvn compile` 通过（步骤 1）

### 步骤 2:创建分流

- [x] `ProjectRequest`(domain/dto)加 `channel`(可选)；draft 值对象落在 `BydDynastyGenerateService.BydDynastyDraft`
- [x] `ArticleProjectController.create`：channel 白名单；byd 放宽 topic 与仿写约束；创建即建空草稿版本并指向 current_version_id
- [x] 单测：生成服务 6 例（渠道门禁/必填/互斥/成功建版本/失败回写/state）；创建分流已由接口冒烟覆盖
- [x] 验证：编译通过；`mvn test` 1181 用例全绿

### 步骤 3:活动发文 AI 生成

- [x] `BydDynastyGenerateService`：原子抢占 + `@Async` + `structured` + 新版本落库；失败走 `failVersionsToReady` 写 `last_version_error`
- [x] `ProjectBydDynastyController` 加 generate / generate-state；DTO 校验与 R<T> 包装
- [x] prompt 资产 `prompts/byd/generate-system.st` / `generate-user.st`（纯文本分段约束）
- [x] 单测：`BydDynastyGenerateServiceTest`（mock AiClient）
- [x] 验证:`mvn -DskipTests compile` 通过；`mvn test` 1125 用例全绿

### 步骤 4:前端 API 与创建表单

- [x] `frontend/src/api/index.js`：`bydApi.generate` / `bydApi.generateState`（30s）
- [x] `ProjectEdit.vue`：渠道 el-radio-group（默认 gzh；编辑入口不存在，创建后不可改）(默认 gzh);byd 选中时隐藏公众号字段集(genSource/topic/读者/字数/风格仿写),显示说明文案;提交带 channel;byd 创建成功跳 `/projects/:id/byd-editor`
- [x] `ProjectList.vue`：王朝代小程序 tag + 空主题兜底(byd 显「王朝小程序」,topic 空时标题兜底「未命名小程序文章」)
- [x] 验证：`npm run build` 通过

### 步骤 5:三步工作台

 - [x] `router`：新增 `/projects/:id/byd-generate|byd-editor|byd-push` 子路由 + 渠道守卫(gzh/byd 路由互斥重定向)
 - [x] `ProjectLayout.vue`：按 project.channel 裁剪步骤条与 `maxReachableStepOf`(gzh 四步不变;byd 三步)
- [x] `BydGenerateStep.vue`(生成表单+轮询+错误黄条+「跳过直达编辑」)
- [x] `BydEditorStep.vue`(标题+MarkdownEditor+配图工具栏;保存/title 接口;dirty 离开提示;「去推送」自动先保存;不渲染 wenyan)
- [x] `BydPushStep.vue`(摘要 + `BydDynastyPanel` 挂载)
- [x] `StepPublish.vue` 摘除 BydDynastyPanel（回归纯公众号）(回归纯公众号;**回归 AC-6**)
- [x] viewer 只读贯穿三步(按钮禁用/只读态)
- [x] 验证：`npm run build` + 5662 联调接口冒烟（建项目/编辑保存/生成/推送面板可见性）(建 byd 项目→编辑→(mock)生成→推送面板可见)

### 步骤 6:文档三处同步

- [x] `docs/spec/publish.md`：标注发布步渠道解耦发布步已渠道解耦(公众号纯发布,BYD 面板迁移)
- [x] 新增 `docs/spec/channel.md`（channel 字段/三步工作台/generate 契约/payload 内容源）+ `project-lifecycle.md` 字段表:channel 字段、byd 三步工作台、generate 接口契约、payload 内容源说明
- [x] `docs/README.md` 模块索引同步

## 验证命令

```bash
mvn -q -DskipTests compile                    # 每步编译
mvn test                                      # 后端全量(改动服务契约后必跑)
cd frontend && npm run build                  # 前端构建
./dev.sh restart backend && ./dev.sh logs backend -f   # 联调
```

## 风险文件与回滚点

- `StepPublish.vue`(摘面板):还原 import + 模板段落即可回滚
- `ArticleProjectController.create`、`ProjectRequest`:向后兼容(新字段可选),回滚无数据影响
- `ProjectStatusService`:**只复用不改动**(写权收敛不受破坏)
- V24 迁移:列默认 gzh,回滚保留无碍

## start 前检查

- [x] prd/design/implement 齐备且经用户批准(最终规划汇总后明确同意)
- [x] `implement.jsonl`/`check.jsonl` 已填真实 spec/research 条目
- [x] `task.py start` 后才动产品代码