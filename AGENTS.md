# Sparkora

新媒体内容创作平台(Sparkora):AI 辅助的「主题 → 简报 → 多版本正文 → 编辑 → 发布」创作工作台。

## Project

双模块单仓库:

- **后端** `src/` — Spring Boot 3.3.4(Java 21)+ MyBatis-Plus 3.5.7 + Spring Security(JWT, jjwt 0.12.6)+ PostgreSQL。入口 `src/main/java/com/sparkora/SparkoraApplication.java`。包结构 `com.sparkora`,Maven 构建(`pom.xml`)。
- **前端** `frontend/` — Vue 3 + Vite 5 + Element Plus 2.8 + Pinia + vue-router,`unplugin-auto-import`/`unplugin-vue-components` 自动引入 Element Plus 组件。移动端优先响应式,不引 Vant 等额外移动端框架。
- **规格文档** `docs/s0-spec.md` 是唯一权威规格(路由/权限/字段级/接口契约/状态机);`prototypes/` 存 HTML 原型。
- 当前阶段:已完成 S0~S4(登录、项目 CRUD、简报生成、多版本正文、风格库、配图三来源、wenyan 同核预览 + 七牛图床)+ **S5 发布模块**(五步流程闭环:发布步 `StepPublish.vue` → `PublishService`(与预览同源渲染,gzhContent JSON)→ 远程 wenyan-server `/upload`→`/publish` → 公众号草稿箱;发布成功进 `PUBLISHED_DRAFT`(终态,可重发覆盖);2026-08-31 实测 2.0.11 接口:`/verify` 是 GET、`/upload` multipart 字段名 `file`、`/publish` 收 JSON `{fileId}`,字段级契约与验收状态见 spec §12)。

## Commands

```bash
# 联调环境一键控制(推荐):start/stop/restart/status/logs,目标可选 backend|frontend|all
./dev.sh start                    # 启动前后端(日志在 /tmp/sparkora-logs/,探活 /api/auth/me)
./dev.sh restart backend          # 只重启后端(改 Java 代码后常用)
./dev.sh logs backend -f          # 跟随后端日志;./dev.sh logs [目标] [行数|-f]
# 后端(仓库根目录)
mvn -q -DskipTests compile        # 编译(已验证;默认 maven 仓库只读时加 -Dmaven.repo.local=/tmp/m2repo)
mvn spring-boot:run               # 本地运行,端口读 .env 的 SERVER_PORT(当前 5661;application.yml 默认 8080),读根目录 .env
mvn test                          # 目前 src/test 为空
# 前端(frontend/ 目录)
npm run dev                       # Vite dev server(http://localhost:5173,代理 /api → 后端 SERVER_PORT,见 vite.config.js)
npm run build                     # 产线构建(已验证)
```

- 验证后端改动至少跑 `mvn -q -DskipTests compile`;前端改动跑 `npm run build`。
- 无 mvnw wrapper,直接用系统 `mvn`;运行需要 PostgreSQL + 根目录 `.env`(模板见 `.env.example`,**绝不提交真实 `.env`**)。

## Architecture

- `com.sparkora.common.R<T>` — 统一响应包装 `{code, msg, data}`;`code=0` 成功,失败用 `R.fail(code, msg)`,msg 为中文提示。
- `com.sparkora.security.*` — `SecurityConfig`(保护 `/api/**`)+ `JwtAuthenticationFilter` + `JwtUtil`(密钥/过期读 `.env` 的 `JWT_*`);`SecurityUtil.require()` 取 `CurrentUser`;`@PreAuthorize("hasAnyRole('ADMIN','EDITOR')")` 做接口级授权。
- `com.sparkora.web.controller` — REST 控制器,路径前缀 `/api`:`AuthController`(login/logout/me)、`ArticleProjectController`、`StyleController`。
- `com.sparkora.service` + `com.sparkora.ai` — 业务与 AI 调用层(`AiClient`/`AiImageClient`,配置来自 `AiProperties` ← `.env` 的 `AI_*`)。
- `com.sparkora.mapper` + `domain.entity` — MyBatis-Plus mapper 与实体;全局配置:表前缀 `sparkora_`、`id-type: auto`、逻辑删除字段 `deleted`、下划线转驼峰。
- `src/main/resources/db/schema.sql` — 幂等建表(`CREATE TABLE IF NOT EXISTS`),`spring.sql.init.mode: always` 启动时自动执行;加表/列要改这里(幂等写法)。
- 前端:`src/api/http.js` axios 实例(`baseURL: '/api'`,自动带 `Bearer` token,401 统一跳 `/login`);`src/store/user.js` Pinia;`src/views/project/Step*.vue` 为向导式步骤子路由,外层 `ProjectLayout.vue` 步骤条。

## Conventions

- 后端代码、注释、commit message 均用中文;commit 风格 `feat(S2a): 描述` / `fix(ui): 描述`(scope 为阶段号或 ui)。
- 配置一律走环境变量(.env → `application.yml` 占位符 `${XXX:default}`),不在代码里硬编码 URL/密钥;新增配置同步更新 `.env.example`。
- 所有 `/api` 接口:方法级 `@PreAuthorize` + `R<T>` 包装;写操作入参用 `@Valid` DTO(`domain.dto`);角色模型仅 ADMIN / EDITOR / VIEWER 三种,viewer 只读。
- 实体审计字段(created_by / created_at / updated_at / deleted)由控制器手工赋值,沿用现有写法。
- 前端页面用 Element Plus(`el-form` + rules 校验,移动端单列、触控目标 ≥44px);图标用 `@element-plus/icons-vue`;不新增重型 UI 框架。
- 表结构变更:改 `schema.sql`(幂等)+ 对应 entity/mapper + `docs/s0-spec.md` 字段级表格,三处同步。
- 配图存储 `data/`(已 gitignore),`/images/**` 由 `WebConfig` 静态映射 `IMAGE_STORAGE_DIR`;AI 生成图必须转存本地不留临时 URL。
- S4 预览:wenyan CLI render(同核)+ 七牛图床(`QINIU_*`,兼容裸 AK/SK),契约与限制见 spec §11;`docs/img.md` 为七牛接入文档(密钥只放 .env)。
- S5 发布:`WenyanServerService`(客户端,x-api-key)+ `PublishService`(预览同源渲染→upload→publish→PUBLISHED_DRAFT 可重发);`WENYAN_MCP_PUBLISH_TIMEOUT_MS` 默认 30s;发布验收清单见 spec §12。

## Notes

- Reasonix 配置:`reasonix.toml`(permissions/sandbox 预授权)、`.reasonix/skills/`(sparkora-dev 联调、sparkora-spec-check 规格核对)、`.reasonix/settings.json` + `.reasonix/hooks/secret-guard.sh`(PostToolUse 检测 .env 密钥误入代码)。项目记忆即本文件(AGENTS.md)。
- (待补充)
