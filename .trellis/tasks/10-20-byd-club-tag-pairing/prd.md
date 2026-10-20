# 修正 BYD 车友会标签成对选择与校验

## Goal

把“车友会”和“标签名”收敛为一个用户可理解的“车友会标签”选择，并保证 BYD 草稿协议中的 `clubId` 与 `clubTitle` 始终作为一组生成、校验、持久化和下发。用户选择后应能成功推送带图片/视频的小程序文章，且公众号图片链路不受影响。

## Background

- `clubId` 是车友会标签的内部标识，`clubTitle` 是同一标签的显示名称，二者不是两个独立业务选项。
- BYD 草稿顶层协议要求同时写入 `clubId` 与 `clubTitle`；原始 `D:\code\AI\weixin\editor.html` 使用单个“车友会标签”下拉框，提交时才拆成两个字段。
- 当前 Sparkora 页面直接暴露两个自由输入框：
  - `frontend/src/components/publish/BydDynastyPanel.vue:49-53`
  - 两个值分别加载和提交：`BydDynastyPanel.vue:147-164`
- 服务端当前分别回退请求、项目和默认值，可能产生 ID 与名称不匹配：
  - `src/main/java/com/sparkora/service/BydDynastyPushService.java:290-291`
  - payload 写入：`BydDynastyPushService.java:444-445`
- 项目已保存 `byd_dynasty_club_id` / `byd_dynasty_club_title`，但成功回写路径尚未写入这两个字段：
  - `src/main/java/com/sparkora/service/BydDynastyPushService.java:330-340`
- Agent 当前也分别回退 payload 和本地配置：
  - `agent/byd-dynasty/byd_local_agent.py:1113-1126`
- 本项目在项目创建时已通过 `project.channel` 区分 `gzh` 与 `byd_miniapp`；本任务不得改变该渠道边界。

## Requirements

- R1 前端只展示一个“车友会标签”选择器，选项显示 `clubTitle`，留空表示“不使用”；不得再提供两个可独立编辑的输入框。
- R2 服务端是标签选项和成对关系的唯一权威：请求、项目历史值和默认配置都必须以 `clubId + clubTitle` 成对解析。
- R3 成对校验：两个字段都为空表示不使用；两个都有值表示有效选择；仅有一个非空时必须返回中文 400，不得自动拼接或部分回退。
- R4 `state` 接口返回可供前端展示的成对标签选项；前端不得硬编码 `220|四川迪友会`，也不得自行推导标签名称。
- R5 项目历史值只在成对完整时使用；推送成功后把本次实际使用的成对值写回项目，失败任务不得污染项目选择。
- R6 Agent 增加最后一道成对保护：payload 与本地默认配置不得逐字段混合；字段不完整时拒绝写 MMKV。
- R7 BYD payload 仍保持顶层 `clubId` / `clubTitle`，不得改为 message 流字段；图片/视频上传和比亚迪 CDN 链路不得回归。
- R8 公众号 `gzh` 的七牛媒体、`sparkora-img:` 和发布逻辑保持原样。
- R9 完成后提交到当前功能分支、推送远端，并在开发服务器重建 backend/frontend 镜像、替换容器并完成健康检查。

## Acceptance Criteria

- AC1 BYD 推送页只有一个“车友会标签”下拉框，显示“不使用”和可用标签名称，不再出现“车友会”和“标签名”两个输入框。
- AC2 选择“四川迪友会”时，创建任务 payload 同时包含 `clubId=220`、`clubTitle=四川迪友会`。
- AC3 选择“不使用”时，payload 中两个值为空，Agent 草稿的两个值也为空。
- AC4 POST 请求只传 `clubId` 或只传 `clubTitle` 时返回中文 400，且不创建 job。
- AC5 请求中的成对值不属于服务端可用标签时返回中文 400，且不创建 job。
- AC6 请求未指定时，完整项目历史值优先于完整默认值；两者都不完整时不产生半套字段。
- AC7 推送成功后项目两个字段与 payload 成对值一致；推送失败不修改项目历史选择。
- AC8 Agent 收到半套 payload 或半套本地默认配置时抛出明确中文错误，且不写 MMKV。
- AC9 `mvn test`、Agent `pytest`、前端 `npm run build` 全部通过。
- AC10 提交已推送至 `origin/feature/byd-dynasty-miniapp-push`，远程镜像构建成功，backend 健康、frontend 可访问、`/api/auth/me` 返回 401。

## Out of Scope

- 不新增车友会标签的后台管理 CRUD；当前选项来自完整默认配置和项目历史值。
- 不新增数据库迁移；复用现有 `byd_dynasty_club_id` / `byd_dynasty_club_title` 列。
- 不修改 BYD 图片/视频暂存、上传、MMKV 消息格式或公众号发布链路。
- 不在本任务中发布新的 Windows Agent 安装包；只修正和测试 Agent 源码。

## Technical Notes

- 目标分支：`feature/byd-dynasty-miniapp-push`。
- 部署目标：`sparkora-dev:/data/code/sparkora`，通过 `./deploy.sh --no-pull` 重建镜像并替换容器。
- 仓库存在与本任务无关的脏文件，提交时必须按明确路径暂存，禁止 `git add -A`。