# 渠道化工作台（公众号 / 王朝小程序）

> 回链：[系统说明总览](../README.md)
> 关联：[publish.md](publish.md)（公众号发布）、[preview.md](preview.md)（预览编辑区）、[project-lifecycle.md](project-lifecycle.md)（项目字段）

职责：项目按**发布渠道**分流到两套工作台。渠道在创建时选定并绑定项目，内容域互不污染：

| 渠道 | `channel` | 工作台形态 | 推送目标 |
|---|---|---|---|
| 微信公众号 | `gzh`（默认） | 现有四步向导：简报 → 版本 → 预览（自由编辑区）→ 发布 | 公众号草稿箱（wenyan-server，[publish.md](publish.md)） |
| 比亚迪王朝小程序 | `byd_miniapp` | 三步：生成（可选）→ 编辑（自由编辑区）→ 推送 | 王朝小程序草稿箱（Server 编排 + 本机 Windows Agent） |

> 背景（10-19-channel-editor-publish）：公众号内容面向公众，小程序内容面向比亚迪车友社区，属性不同。此前发布步把两个渠道揉在一起、小程序推送直接复用公众号版本内容，用户没有为小程序独立编辑内容的入口。

---

## 1. 数据模型

Flyway `V24__project_channel.sql`（main 已占用 V21~V23）：

```sql
ALTER TABLE sparkora_article_project
    ADD COLUMN channel VARCHAR(20) NOT NULL DEFAULT 'gzh';
```

- 存量项目自动 `gzh`，行为不变；无需回填。
- `byd_miniapp` 项目**创建即建空草稿版本**（`title=""`、`content_md=""`），`current_version_id` 指向该版本——方式一（自由手写）从空版本开始。
- 小程序推送结果继续写既有 `byd_dynasty_*` 列（10-16 已隔离），与公众号 `publish_*` / `published_at` 互不相扰。
- 小程序编辑保存正文只写版本表（不改项目 `status`），故推送门禁对 `byd_miniapp` 只要求「有当前版本 + 正文非空 + Agent 在线/预检通过」；公众号项目仍按状态机要求版本就绪。

---

## 2. 接口契约（全部 `R<T>` 包装；HTTP 200）

| 方法 | 路径 | 权限 | 请求 | 响应/说明 |
|---|---|---|---|---|
| POST | `/api/projects` | ADMIN/EDITOR | `ProjectRequest` + `channel`（可选，缺省 `gzh`；只允许 `gzh`/`byd_miniapp`） | `R<Long>` 项目 id。`gzh`：`topic` 必填、仿写约束生效；`byd_miniapp`：`topic` 可空、忽略仿写参数，创建后立即建空草稿版本 |
| POST | `/api/projects/{id}/byd-dynasty/generate` | ADMIN/EDITOR | `{activityBrief ≤20000, personalInfo ≤5000}`，均必填 | `{started:true, status:"GENERATING_VERSIONS"}`；非小程序项目/字段缺失/生成中 → `R.fail(400)` 中文原因 |
| GET | `/api/projects/{id}/byd-dynasty/generate-state` | 三角色 | — | `{generating, lastError?, currentVersionId}`；生成完成后 `currentVersionId` 指向新版本 |
| POST | `/api/projects/{id}/byd-dynasty/push-jobs` | ADMIN/EDITOR | `{clubId?, clubTitle?}` | 10-16 契约不变：Server 编译 payload（取 `current_version_id` 的 title/contentMd/图片），本机 Agent 领取执行。**状态门禁按渠道**：`byd_miniapp` 只要求有当前版本（编辑保存不改项目状态，DRAFT 亦可推）；`gzh` 保持原契约（`VERSIONS_READY`/`PUBLISHED_DRAFT` 才可推） |

### 2.1 活动发文生成（方式二）

- 异步范式：`BydDynastyGenerateService.startGenerate` 经 `ProjectStatusService` 原子抢占置 `GENERATING_VERSIONS`（生成中互斥），随后 `@Async runGenerate` 调 `AiClient.structured` 产出 `{title, content}`；成功**新建版本**（不覆盖用户已编辑内容）→ 指向 `current_version_id` → `VERSIONS_READY`；失败走 `failVersionsToReady` 写 `last_version_error`（中文，≤1000）。
- Prompt 资产：`src/main/resources/prompts/byd/generate-system.st` / `generate-user.st`。约束：面向车主社区、第一人称口语化、**分段纯文本**（不使用 Markdown 标题/表格/链接）——推送 payload 是 `messages[text|image]` 流，Markdown 语法会原样进入文本消息。
- 幂等：每次生成创建新版本，重复触发安全（生成中并发被状态机拒绝）。

---

## 3. 前端

### 3.1 路由

| 路径 | name | 组件 |
|---|---|---|
| `/projects/:id/byd-generate` | `project-byd-generate` | `views/project/BydGenerateStep.vue` |
| `/projects/:id/byd-editor` | `project-byd-editor` | `views/project/BydEditorStep.vue` |
| `/projects/:id/byd-push` | `project-byd-push` | `views/project/BydPushStep.vue` |

`ProjectLayout.vue` 按 `project.channel` 切换步骤条（gzh 四步 / byd 三步）并在项目加载后做渠道路由纠偏：byd 项目误入公众号步骤 → 回编辑步；gzh 项目误入小程序步骤 → 回状态机对应步骤。

### 3.2 三步工作台

- **生成步（可选）**：官方活动要求 + 我的情况两个 textarea，提交后 2s 轮询 `generate-state`，成功后跳编辑步；「跳过，直接进入编辑器」= 方式一。
- **编辑步（自由编辑区）**：标题 + `MarkdownEditor` 正文 + 配图抽屉（图库 / AI 生图 / 智能建议，复用 `components/preview/PreviewImageDrawer.vue`）；保存走既有 `PUT /versions/{id}/content` 与 `/title`；粘贴图沿用 `sparkora-img:` 占位 + 「去推送」时统一上传图床（`usePendingImageFlush`），先落库后清条目；离开有未保存确认。不渲染 wenyan 主题/预览（小程序无排版主题概念）。
- **推送步**：标题/字数/插图数摘要 + `BydDynastyPanel.vue`（Agent 状态、预检、车友会、任务轮询；10-16 逻辑原样迁移）；正文为空或含粘贴图占位时阻断推送。

### 3.3 其它入口

- `ProjectEdit.vue`：顶部「发布渠道」单选（默认公众号）；选小程序隐藏公众号生成参数区，创建后直达 `/byd-editor`。
- `ProjectList.vue`：小程序项目显示「王朝小程序」标签；空主题兜底「未命名小程序文章」。

---

## 4. 兼容与回滚

- 所有旧接口签名不变；`channel` 缺省 `gzh`，存量项目与公众号四步向导零回归。
- `BydDynastyPushService` / Agent 契约 / payload 编译零改动——内容源仍是 `current_version_id` 对应版本。
- 回滚：V24 列保留无碍（向后兼容）；前端新增路由/组件可单独回退；`BydDynastyPanel` 若要回迁发布页，还原 `StepPublish.vue` 的 import 与模板即可。

## 5. 验收状态

- [x] 接口冒烟（2026-10-19，dev 后端 5662）：byd 空主题创建成功且自动建空版本；`gzh` 空主题 400；非法渠道 400；generate 缺字段 400 中文；generate-state 正常。
- [x] 活动发文真机链路：输入活动要求+个人情况 → 新版本生成（标题/正文中文、`version_label=BYD`）→ `currentVersionId` 切换、状态 `VERSIONS_READY`。
- [x] 编辑保存：`PUT /versions/{id}/content|title` 成功（正文未变化时前端跳过 content 写入，允许先存标题）。
- [x] 推送门禁：`byd_miniapp` 项目 DRAFT 状态（手动编辑路径）可直接建推送任务；`gzh` 项目仍要求 `VERSIONS_READY`（单测锁定）。
- [x] `mvn test` 用例全绿；`npm run build` 通过。
- [ ] 前端浏览器真机验收（建 byd 项目 → 编辑 → 生成/跳过 → 推送面板）→ 留用户联调确认。
