# prd.md — 渠道化发布:自由编辑区与分渠道推送

> 分支:`feature/byd-dynasty-miniapp-push`

## Goal

把「生成→发布」从单一公众号链路改为**按渠道分流的编辑与推送**:项目绑定渠道后进入该渠道的工作台,编辑完成后直接推送到对应渠道草稿。

- 微信公众号(gzh) → 复用现有四步向导,预览页即自由编辑区 → 推送公众号草稿箱(现状保持)。
- 微信王朝小程序(byd_miniapp) → 两种创作方式,最终都经自由编辑器 → 推送小程序草稿箱。

## 背景

公众号内容面向公众,小程序内容面向比亚迪社区/车主,内容属性不同。现状发布步把两个渠道面板揉在一起,小程序推送直接复用公众号版本内容(`BydDynastyPushService.buildPayload` 取 `currentVersion.contentMd`,BydDynastyPushService.java:296),用户没有为小程序独立编辑内容的入口。

## 已确认事实(代码库证据)

1. **向导结构**:`ProjectLayout.vue` 四步(简报/版本/预览/发布);预览页 `StepPreview.vue` 已含 `MarkdownEditor` 自由编辑 + 配图工具栏(文字/粘贴图/图库)。
2. **创建入口唯一**:`ProjectEdit.vue` 表单 → `POST /projects`(frontend/src/api/index.js:16);表单字段(genSource/topic/读者/字数)面向公众号生成参数。
3. **公众号发布链路**:`PublishService` → wenyan-server → `PUBLISHED_DRAFT`(文档见 `docs/spec/publish.md`),本任务**零改动**。
4. **小程序推送已上线(10-16)**:Server 编排(`BydDynastyPushService`)+ Windows 本地 Agent(`agent/byd-dynasty/byd_local_agent.py`,MMKV 写入)+ 前端 `BydDynastyPanel.vue`(Agent 状态/车友会/任务轮询,现嵌于 `StepPublish.vue`)。
5. **推送前置**:`createJob` 仅校验「版本存在且 contentMd 非空 + Agent 在线 + 预检通过」,**不校验项目状态机**(BydDynastyPushService.java:283-296)。
6. **payload 形态**:`{title, messages[text|image], images, clubId, clubTitle, coverImageId}`——markdown 文字 + 图库图片即可覆盖,无需富文本。
7. **项目级 BYD 结果字段已隔离落库**:`byd_dynasty_club_id/club_title/published_at/last_job_id/last_draft_id/last_draft_count/last_error`(与公众号 `publish_media_id` 等列互不相扰)。

## 已确认决策(用户)

- **D1 项目绑定单一渠道**:创建时选定渠道,工作台按渠道裁剪,内容域互不污染;公众号链路保持现状。
- **D2 小程序两种创作方式**:
  1. 自由编辑器直写(手写文字+配图 → 完成即推送);
  2. 官方活动发文(接收官方活动要求 + 用户输入自己的情况 → AI 生成文章 → 推送)。

## Requirements

### R1 项目渠道属性与创建分流

- `sparkora_article_project` 新增 `channel` 列(`gzh` | `byd_miniapp`),默认 `gzh`,存量项目回填 `gzh`(Flyway 迁移)。
- 新建项目表单(PackageEdit)增加渠道选择(默认公众号);选「王朝小程序」时表单裁剪公众号生成参数(genSource/topic/读者/字数 隐藏,提示进入小程序工作台)。
- 项目列表卡片显示渠道标识。

### R2 公众号渠道 = 现状保持

- gzh 项目走现有四步向导:简报 → 版本 → 预览(自由编辑区) → 发布(公众号表单内嵌 BydDynastyPanel 从发布步移除,见 R5)。
- 现有 `docs/spec/publish.md` 契约、`ProjectStatusService` 状态机、预览-发布衔接全部不变。

### R3 小程序渠道工作台(R1 选定 byd_miniapp 后进入)

byd 项目采用「生成 → 编辑 → 推送」三步工作台(复用 ProjectLayout 路由骨架,步骤集合与 gzh 不同):

- **生成步(可选)** = 方式二「活动发文」:两个输入块——官方活动要求(粘贴全文)+ 我的情况(自由文本),AI 生成文章写入编辑器;可跳过(=方式一)。
- **编辑步(核心自由编辑区)**:MarkdownEditor 自由编辑文字 + 配图(图库/粘贴图/上传,复用现有配图三来源能力)+ 标题编辑;不渲染 wenyan 样式(小程序按消息流转义,无需公号排版主题)。
- **推送步**:仅显示 BYD 王朝面板(Agent 状态/预检/车友会表单/任务状态轮询,复用现有 `BydDynastyPanel.vue` 裁剪迁移),推送按钮直接建任务,完成后显示草稿 ID;支持重推(覆盖草稿语义与现状一致)。
- 编辑内容存版本表(currentVersion.contentMd + 图片关联),推送链路 `buildPayload` 不改。

### R4 活动发文 AI 生成(方式二)

- 新增后端接口:`POST /api/projects/{id}/byd-dynasty/generate`(ADMIN/EDITOR),入参 `{activityBrief(官方活动要求), personalInfo(我的情况)}`,校验非空;调 `AiClient` 生成 `{title, contentMd}`;生成结果保存为项目新版本(编辑步立即可改),失败写项目级错误字段(沿用生成链路错误落库习惯)。
- 生成链路异步化,沿用现有 `start*`/`@Async run*` + 前端轮询范式。
- 幂等/重复生成安全:每次生成创建新版本,不覆盖用户已编辑内容。

### R5 发布步渠道解耦

- `StepPublish.vue` 移除内嵌的 `BydDynastyPanel.vue`(byd 面板迁至 R3 推送步),发布步回归纯公众号形态;gzh 项目从此不见小程序面板。

### R6 权限与既有契约不回退

- viewer 全程只读;推送/生成为 ADMIN/EDITOR。
- 公众号 `/publish`、wenyan、`publish.md` 契约零回归;小程序推送契约(10-16)零回归——本任务只做入口/工作台分流与内容来源解耦。

## Acceptance Criteria

- [x] AC-1 新建项目可选渠道;选小程序后进入三步工作台,选公众号进入现有四步向导;存量项目行为不变(默认 gzh)。
- [x] AC-2 小程序项目编辑步可自由编辑文字/标题/配图,保存进版本表;刷新不丢。
- [x] AC-3 小程序项目推送步可见 Agent 状态/预检/车友会表单,推送成功显示草稿 ID;Agent 离线/预检失败时禁推送并给出中文指引(沿用 10-16 语义)。
- [x] AC-4 活动发文:填官方活动要求+个人情况后生成文章落入编辑器(可再编辑后推送);两项必填校验;生成失败有中文错误提示。
- [x] AC-5 推送内容与公众号内容解耦:同一账号下「小程序项目」payload 来自该项目版本表,不引用任何 gzh 项目内容。
- [x] AC-6 gzh 项目发布步不再出现小程序面板;小程序编辑/推送全程不触碰公众号状态机与 publish 列。
- [x] AC-7 `mvn test` 与 `npm run build` 通过;`docs/spec/publish.md`、`docs/spec/**` 按三处同步约定更新。

## Out of Scope

- 公众号生成/发布链路的任何行为改动(含预览页 wenyan 渲染)。
- 王朝小程序本地 Agent / MMKV 写入逻辑的改动(10-16 已交付)。
- 活动模板库、活动会话多轮对话、官方活动结构化解析(后续迭代)。
- 同一内容双渠道分发(项目仍绑定单一渠道)。
- 公众号渠道的「纯手写直发」入口(用户未要求)。

## Open Questions

- 无(阻塞项已清零;D1/D2 已由用户拍板,工作台结构与 AI 接口形态在 design.md 定稿)

---

## 验收记录（2026-10-19/20）

- 分支基线修正：本地 `feature/byd-dynasty-miniapp-push` 原落后 main 27 个提交；已 fast-forward 到 main（`491c8c9`）。main 已占用迁移 V21~V23，本任务迁移顺延为 **`V24__project_channel.sql`**（原拟 V22 会撞号）。
- 接口冒烟（dev 后端 5662，共享 DB）：byd 空主题创建成功且自动建空版本（`currentVersionId` 指向）；`gzh` 空主题 400；非法 channel 400；generate 缺字段 400 中文；generate-state 正常。
- 活动发文真机链路：活动要求 + 个人情况 → 新版本生成（中文 title/正文、`version_label=BYD`）→ `currentVersionId` 切换、状态 `VERSIONS_READY`。
- 编辑保存：`PUT /versions/{id}/content|title` 成功。
- 质量检查修复（trellis-check）：推送服务对 byd 的状态门禁分流（原 `VERSIONS_READY` 会阻断手写→推送）、空正文只存标题、采纳 persist 注入、viewer 配图入口禁用、生成防重入、轮询关闭全局 toast 等。
- 自动化：`mvn test` **1188 用例全绿**（含新增生成/推送门禁单测）；`npm run build` 通过。
- 未验证/留待真机：浏览器端到端交互、Windows Agent 真机推送、活动文 prompt 质量主观验收。
