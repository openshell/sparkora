# 实施上下文检查表（10-20 BYD 媒体）

> 本文件是大型 `.trellis/spec` 索引的精简注入版，只保留本任务相关约束；实施时仍应按 `implement.md` 阅读完整规范。

## 后端

- Flyway 迁移必须幂等：`CREATE TABLE IF NOT EXISTS`、`CREATE INDEX IF NOT EXISTS`；不得修改既有迁移。
- 新表必须有项目归属、过期时间、逻辑删除或明确清理策略；查询必须带 `project_id`，禁止跨项目读取。
- 用户侧 BYD 接口统一中文 400；Server 不直接写 MMKV，不调用比亚迪上传。
- 大文件使用受限 InputStream + 临时文件 + 原子 rename；不要把 300MB 视频整体读入 JVM 内存。
- 任务创建是事务边界：媒体校验失败不得插入 job，也不得更新项目 last job 字段。
- Agent 下载必须校验 Token、job 归属、job 状态、payload 引用、media 项目归属和过期时间。

## 前端

- HTTP 响应拦截器已返回 `R<T>` 本体：调用方读 `res.data`，不要再读 `res.data.data`。
- 新接口先补 `src/api/index.js` 具名导出；`.vue` 不直接 import http。
- `MarkdownEditor` 的 `mediaMode` 默认必须是 `gzh`，确保公众号 `sparkora-img:` + pending store 零回归。
- BYD token 只通过 `src/utils/bydMediaRefs.js` 解析；编辑器、推送页、测试共用。
- BYD 上传失败不得插入 token；上传中禁用推送；视频先读元数据做第一层校验。
- Boolean prop 绑定必须显式布尔化（如 `:disabled="!!busy"`），避免 Element Plus 把空串 cast 成 true。
- BYD 文章不设置封面，不把封面字段带入 payload。

## 部署

- 暂存目录 `BYD_MEDIA_STORAGE_DIR` 必须落在现有 `./data:/app/data` 卷内。
- Nginx 只对 BYD 原始流上传 location 设置 `client_max_body_size 310m`、`proxy_request_buffering off`；不要放宽全局 `/api/`。
- Agent 与 Server 的 payload v2 同批发布；Agent 保留 v1 读取路径仅用于排空旧任务。

## 验证

- 后端：`mvn test`，重点 `BydDynastyPlainTextRendererTest`、`BydDynastyPushServiceTest`、`BydDynastyMediaServiceTest`。
- Agent：`python -m pytest -q`，覆盖图片/视频上传、sibling、视频三段消息、失败不写 MMKV。
- 前端：`npm run build`，覆盖 `mediaMode` 默认 gzh 和 BYD token 解析。
- 真机：图片、视频、3 个视频、超限拒绝、草稿箱打开验证。
