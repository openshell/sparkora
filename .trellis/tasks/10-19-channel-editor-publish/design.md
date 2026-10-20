# design.md — 渠道化发布:自由编辑区与分渠道推送

> 前置:prd.md(需求与验收);分支 `feature/byd-dynasty-miniapp-push`

## 1. 架构与边界

渠道分流发生在**项目级**:项目带 `channel` 属性,后端子域控制器本已分离(公众号 `ProjectPublishController`、小程序 `ProjectBydDynastyController`),本任务不合并不新增大域,只做:

```
新建项目选渠道(channel)
  ├─ gzh         → 现有四步向导 + StepPublish(纯公众号,面板摘除)   [现状保持]
  └─ byd_miniapp → 三步工作台: 生成(可选) → 编辑 → 推送(BYD 面板)
```

- 公众号发布链路(`PublishService`/wenyan/`docs/spec/publish.md`)与小程序推送链路(10-16 契约)**零改动**——推送内容源仍是 `currentVersion`(title/contentMd/coverImageId),只要 byd 项目也有版本记录,`buildPayload` 原样可用(BydDynastyPushService.java:296)。
- `createJob` 本就不校验项目状态机(仅版本非空 + Agent 就绪),「编辑完直接推」无需状态门禁改动。

## 2. 数据模型(Flyway V24)

`V24__project_channel.sql`:

```sql
ALTER TABLE sparkora_article_project
  ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'gzh';
```

- 存量项目自动 `gzh`,语义不变;不需要回填脚本。
- `ArticleProjectEntity` 加 `channel` 字段;列表/详情接口自然带出。
- byd 推送结果继续走既有 `byd_dynasty_*` 列(10-16 已隔离),本任务不加列。

## 3. 后端设计

### 3.1 创建分流(ArticleProjectController.create)

- `ProjectRequest`(domain/dto)新增 `String channel`(可选,默认 gzh);校验只允许 `gzh` | `byd_miniapp`。
- **分渠道校验**: `channel=byd_miniapp` 时 `topic` 放宽为可选(方式一允许空项目);`genSource/imitationText` 公众号约束只对 gzh 生效。控制器内手动分支(现有 create 就在控制器组装,保持一致,pom 不引 group-validation 复杂度)。
- **byd 项目创建即建空草稿版本**:`status=DRAFT` + 新建 `ArticleVersionEntity`(title=`"、contentMd=""`;currentVersionId 指向)——方式一编辑步直接用 `PUT /versions/{id}/content|title`(现契约,无状态门禁,ProjectVersionController.java:87-115)。
  - 实现落点:版本插行复用 `ArticleVersionService` 若有公开创建方法,否则注入 `ArticleVersionMapper` 直接插(与控制器手工装配实体惯例一致)。

### 3.2 活动发文生成(方式二,AI)

新端点(异步,沿用 `start*`/`@Async run*` + 轮询范式):

| 方法 | 路径 | 权限 | 请求 | 响应 |
|---|---|---|---|---|
| POST | `/api/projects/{id}/byd-dynasty/generate` | ADMIN/EDITOR | `{activityBrief(≤20000,必填), personalInfo(≤5000,必填)}` | `{started:true, generationId?}`;前置不满足 `R.fail(400)` |
| GET | `/api/projects/{id}/byd-dynasty/generate-state` | 三角色 | — | `{generating, lastError?, currentVersionId}` |

- 新建 `BydDynastyGenerateService`(独立于 594 行的 PushService,AI 域与推送域职责分离):
  - `startGenerate`:经 `ProjectStatusService` 原子抢占(`markBriefGenerating` 同款约束,仅 DRAFT/READY 或陈旧)→ 状态 `GENERATING_VERSIONS`;
  - `@Async runGenerate`(自注入 `@Lazy self`):`AiClient.structured(system, user, maxTokens, BydDraftResult.class)` 反序列化 `{title, content}`;
  - 成功:创建**新版本**(不覆盖用户已编辑内容,幂等安全)→ `currentVersionId` 指向新版本 → `markVersionSuccess`(→`VERSIONS_READY`);
  - 失败:复用 `markVersionFailed` → `last_version_error` 中文落库;状态不卡死(陈旧自愈已有,ProjectStatusService)。
- Prompt 约束(payload 是 `messages[{type:'text'|'image'}]` 流,markdown 语法会原样进文本消息,见 parseMessages):
  - system:王朝小程序车友会内容助手;面向车主社区(口语化、真诚分享、第一人称);**输出分段纯文本,不使用 markdown 标题/表格/链接**;
  - user:`官方活动要求全文` + `我的情况` 结构化拼接。
- `startGenerate` 复用「生成中」互斥:同项目正在生成时返回 409 语义(`R.fail(400, "正在生成中")`),与简报/版本一致。

### 3.3 不改的

`BydDynastyPushService`/Agent 接口/`ProjectStatusService` 内部逻辑/AiClient。

## 4. 前端设计

### 4.1 路由与工作台裁剪(ProjectLayout.vue)

- 子路由新增(gzh 项目访问 byd 路由互相重定向,router 守卫按 `project.channel` 判断):
  - `/projects/:id/byd-generate` → `BydGenerateStep.vue`(生成步,可跳过)
  - `/projects/:id/byd-editor` → `BydEditorStep.vue`(编辑步,即自由编辑区)
  - `/projects/:id/byd-push` → `BydPushStep.vue`(推送步容器)
- `ProjectLayout` 步骤条按渠道:`gzh`=现有四步;`byd_miniapp`=三步(生成/编辑/推送),`maxReachableStepOf` 同口径裁剪。
- 创建 byd 项目完成后直接跳 `/byd-editor`(方式一默认起点);生成步入口保留(顶部步骤可回)。

### 4.2 组件

- `BydGenerateStep.vue`:两个 textarea(官方活动要求/我的情况,均必填 + 字数上限提示)+ 生成按钮(loading 轮询 generate-state)+ 错误黄条 + 「跳过,直接进入编辑」入口。
- `BydEditorStep.vue`(自由编辑区):
  - 标题输入 + `MarkdownEditor` 编辑正文 + 配图工具栏(复用现预览页组件与逻辑:图库抽屉/粘贴图占位上传/正文图引用统计 `utils/bodyImageRefs.js`);
  - 保存复用 `PUT /versions/{id}/content` + `/title`;dirty 离开提示与「去推送」自动先保存(与预览→发布衔接同规);
  - 不渲染 wenyan 主题/wenyan 预览右栏(小程序无排版主题概念)。
- `BydPushStep.vue`:摘要(标题/插图数)+ 现有 `BydDynastyPanel.vue` 整体迁移挂载(Agent 状态/预检/车友会/任务轮询逻辑原样保留,props 不变)。
- `StepPublish.vue`:摘除 `BydDynastyPanel` 引用,回归纯公众号发布页(发布表单/确认弹层/成功态不动)——**风险点,回归 AC-6**。
- `ProjectEdit.vue`:渠道 radio(`el-radio-group`,gzh/byd_miniapp);选 byd 隐藏公众号字段集(genSource/topic/读者/字数/风格),展示说明;提交带 `channel`。
- `ProjectList.vue`:卡片渠道标签(gzh 无标/byd 显「王朝小程序」tag)。

## 5. 兼容与回滚

- 全部旧接口签名零变化;`ProjectRequest.channel` 可选;`channel` 默认 gzh,存量项目、现有 gzh 工作台行为完全不变。
- 回滚:V24 列保留无碍(向后兼容);前端组件/路由独立,可单独回退;`BydDynastyPanel` 迁移涉及 StepPublish 与 BydPushStep 两处,回滚时还原 import 即可。

## 6. 权衡记录

- **「生成步」进向导 vs 编辑内弹窗**:选向导步骤(方式二是完整创作流程,表单大、需轮询;弹窗会让编辑步超载)。方式一用「跳过」直达编辑器。
- **AI 生成写版本 vs 写独立字段**:选版本表(推送链路/图片关联/审计全部现有能力零适配;独立字段则需要 payload 链路分叉)。
- **独立 BydDynastyGenerateService vs 并入 PushService**:选独立(594 行服务不再膨胀;AI 域失败语义/状态推进与推送域无关)。
- **controller 手动分支 vs JSR group validation**:选手动(与 create 现行风格一致,规模小)。

## 7. 风险

1. `StepPublish.vue` 摘除面板时回归公众号发布页完整性(AC-6,含 viewer 只读)。
2. 活动发文的 prompt 首版质量未知,验收按「生成结果可编辑可用」而非「免编辑」;后续可迭代活动模板库(out of scope)。
3. byd 项目 topic 可空后,列表页标题展示需有兜底(「未命名小程序文章/活动文章」),避免空标题卡片。
4. `parseMessages` 对 markdown 语法(如 `#`)的处理是原样文本——prompt 已约束纯文本输出;若实测 agent 端有更强解析能力,放宽到受限 markdown 属后续优化。