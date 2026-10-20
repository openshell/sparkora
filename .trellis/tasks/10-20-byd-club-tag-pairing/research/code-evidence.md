# 车友会标签代码证据

## 当前用户界面

- `frontend/src/components/publish/BydDynastyPanel.vue:49-53`：两个自由输入框“车友会”“标签名”。
- `BydDynastyPanel.vue:102-103`：分别维护 `clubId`、`clubTitle` 状态。
- `BydDynastyPanel.vue:147-148`：分别从 state 恢复。
- `BydDynastyPanel.vue:162-165`：分别提交。

## 服务端与协议

- `src/main/java/com/sparkora/web/dto/BydPushJobCreateRequest.java:7-10`：请求接收两个字符串。
- `src/main/java/com/sparkora/service/BydDynastyPushService.java:262-263`：state 分别返回两个项目字段。
- `BydDynastyPushService.java:290-291`：两个字段分别执行 request → project → server default 回退。
- `BydDynastyPushService.java:444-445`：payload 写入顶层 `clubId` / `clubTitle`。
- `BydDynastyPushService.java:330-340`：成功回写未设置 club 两列。
- `src/main/java/com/sparkora/config/BydDynastyProperties.java:27-29`：默认值目前也是两个独立字段。
- `src/main/resources/application.yml:113-114`：默认值来自 `BYD_DYNASTY_CLUB_ID` / `BYD_DYNASTY_CLUB_TITLE`。
- `src/main/resources/db/migration/V23__byd_dynasty_local_push.sql:59-60`：项目表已有成对存储列。

## Agent

- `agent/byd-dynasty/byd_local_agent.py:78-79`：本地默认值分别读取。
- `byd_local_agent.py:1113-1126`：payload 与默认值逐字段 `or` 回退后写入草稿。

## 原始业务语义

- `D:\code\AI\weixin\editor.html:66-70`：原始 UI 是单个“车友会标签”下拉框，选项值为 `220|四川迪友会`。
- `D:\code\AI\weixin\editor.html:200-201`：提交时才拆分为 `clubId` 与 `clubTitle`。
- `D:\code\AI\weixin\README.md:122`：明确说明车友会标签通过顶层 `clubId` + `clubTitle` 实现。

## 关键结论

1. `clubId` 与 `clubTitle` 是同一业务对象，必须成对。
2. Sparkora 当前两个输入框是协议字段泄漏到 UI，不是两个业务选项。
3. 只做前端合并仍不够；服务端与 Agent 还必须阻断半套字段和项目持久化不一致。