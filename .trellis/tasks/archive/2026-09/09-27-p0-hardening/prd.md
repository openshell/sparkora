# P0 修复:超时对齐+并发回写+JWT校验

## Goal

修复 2026-09-27 设计评审发现的三项 P0 正确性缺陷，消除生产环境可复现的错误行为：

1. **超时配置倒挂**：产线 nginx 反代 `proxy_read_timeout 180s` 小于前端 axios 放宽的 300s，长耗时 AI 接口被 nginx 先掐断返回 504，前端仍傻等到 300s。
2. **updateById 全字段覆盖回写并发状态**：多处「select → 改字段 → updateById」全字段回写，会把并发推进的状态（如生成中/已发布）用旧快照覆盖回去；同类 bug 已在 preview-style 修过一次（UpdateWrapper 先例），其余位置漏修。
3. **JWT 不查库校验**：JwtAuthenticationFilter 直接信任 token claims 里的 role/username 注入 SecurityContext，`enabled=false` 禁用用户、角色变更在 token 有效期内（默认 24h）不生效；logout 后 token 依旧有效。

## Background（评审证据，file:line 锚点）

### R1 超时倒挂

- `frontend/nginx.conf.template:52` `proxy_read_timeout 180s` / `proxy_send_timeout 180s`。
- `frontend/src/api/index.js` 300s 超时的接口：`generateVersions`(L19)、`generateDeep`(L42)、`analyzeImitation` 之外的 `syncOne`(L91)、`rebuildAll`(L93)、`generateText`(L165)、`generateFromImage`(L173)、`generateFromImageUpload`(L187)、`regenerate`(L194)——全部为 300000ms。
- `application.yml:50` `AI_TIMEOUT_MS` 默认 120s。
- dev 链路（vite proxy → mvn spring-boot:run）不受 nginx 影响，仅产线容器受此倒挂影响。

### R2 updateById 并发回写

已修先例（不动，作为修复范式参考）：
- `ArticleProjectController.java:410-418` preview-style：UpdateWrapper 显式 set 仅目标列，注释明确「避免 updateById 全字段覆盖把并发写入(如生成中状态)回写旧值」。
- `ArticleProjectController.java:431-437` publish-meta：同上，空串落 NULL。
- `PublishService.java:107/131`：UpdateWrapper 精确 set。
- 抢占范式：`BriefService.claimGenerating`(L148-160)、`VersionService.generate`(L107-117)、`ImitationService.analyze`(L91-100)——「条件更新置生成中 + WHERE 状态白名单 + 陈旧 10min 自愈」原子抢占，claimed==0 时抛 409 语义异常。

缺陷位置（本任务修复范围）：
- `DeepController.java:156-162`：/deep/generate 成功后 select→set status/currentVersionId→updateById 全字段回写。
- `ArticleProjectController.java:146-161` update(PUT /{id})：select→改业务字段→updateById 全字段覆盖，会覆盖并发推进的 status/current_version_id/publish_* 等列。
- `ArticleProjectController.java:308-315` setSelectedTitle：select→set selected_title→updateById。
- `VersionService.java:152/162`：generate 成功/失败分支 select 前已持有旧快照 p（AI 调用耗时数分钟，快照陈旧）→ set status→updateById；失败分支 L162 重取 fresh 但仍全字段回写。
- `ImitationService.java:147/159`：analyze 成功/失败分支同上。
- `BriefService.java:134/144`：深度简报成功/失败分支同上（持有陈旧快照全量回写）。
- `ClarifyService.java:117/144`：异步清理 last_brief_error/写失败原因时 select fresh→updateById 全字段回写。
- `VersionService.setCurrent`(L332)：select→set currentVersionId→updateById。
- 注意：`VersionService.updateContent/updateTitle` 只动 version 行，不涉及项目行，不在本任务范围。
- 状态机语义（docs/README.md 4.2）：DRAFT→GENERATING_BRIEF→READY→GENERATING_VERSIONS→VERSIONS_READY→PUBLISHED_DRAFT(终态可重发)。陈旧自愈阈值 10min（`STALE_GENERATING_MS`）。

### R3 JWT 不查库

- `JwtAuthenticationFilter.java:36-44`：parse → 从 claims 构造 CurrentUser → 注入 SecurityContext；token 无效静默保持未认证（L45-47）。
- `AuthController.login`(L35-49)：登录时已查库校验 enabled + BCrypt 匹配，签发含 role 的 token。
- `UserEntity`：`role`(L21)、`enabled`(L22) 字段存在；`UserMapper` 为空 BaseMapper。
- 无任何缓存组件依赖（pom 无 caffeine/guava）。
- 前端 `store/user.js` 把 role 存 localStorage 供 `isEditorOrAbove` 显隐使用，token 有效期内不刷新——前端不做同步刷新（改动面过大，见 Out of Scope）。
- logout 为前端丢弃 token，服务端无黑名单（维持现状，见 Key Decisions）。

## Requirements

### R1 超时对齐

- R1.1 nginx `proxy_read_timeout`/`proxy_send_timeout` 从 180s 提升到 ≥ 300s（与前端最长超时一致），消除产线 504 先于前端超时的窗口。
- R1.2 前端 axios 超时注释与 nginx 值交叉引用，后续任一侧调整可发现联动关系。

### R2 消除项目表 updateById 全字段回写

- R2.1 上述缺陷位置全部改为 UpdateWrapper 显式 set 目标列（含 updated_at），不再全字段覆盖。
- R2.2 状态推进类写入（DeepController generate 成功、VersionService 成功/失败、ImitationService 成功/失败、BriefService 成功/失败）改为**条件更新**：WHERE 带状态白名单或 id+防陈旧条件，避免把 PUBLISHED_DRAFT 拉回 VERSIONS_READY 等状态机回退；陈旧自愈语义（10min）与抢占分支保持一致。
- R2.3 非状态类字段写入（setSelectedTitle、setCurrent、ClarifyService 清错/写错）UpdateWrapper 精确 set 单列即可。
- R2.4 PUT /{id} 项目编辑改为白名单列 UpdateWrapper set（topic/keywords/audience/word_count_target/brand_voice_profile_id/extra_info/selected_title/remark + updated_at），不触碰 status/current_*/publish_*/last_*_error 等服务端状态列。
- R2.5 ArticleProjectController.update 内的 carService.replace 关联车型覆盖维持不变（本就带事务）。

### R3 JWT 查库校验

- R3.1 JwtAuthenticationFilter 在 token 解析成功后查库校验：用户存在且 `enabled=true` 才注入 SecurityContext，否则视为未认证（走 401）。
- R3.2 角色**以库为准**：SecurityContext 注入的 role 取库中当前值，token 内 role 仅作参考不参与鉴权。
- R3.3 查库失败（DB 抖动）时降级行为：fail-closed（拒绝认证）还是 fail-open（信任 token）需定——默认 fail-closed，但登录接口和 /api/auth/me 探活不受影响（me 依赖 SecurityContext，探活口径 401 算存活，见 docker-compose healthcheck）。
- R3.4 不引入外部缓存依赖（pom 无缓存组件）；用简单 ConcurrentHashMap 本地缓存 userId→(enabled,role,查询时间戳)，TTL 60s，平衡每请求查库开销；用户量极小（内部系统）。

## Acceptance Criteria

- [ ] AC1 `mvn -q -DskipTests compile` 通过。
- [ ] AC2 `frontend/npm run build` 通过。
- [ ] AC3 产线 nginx 超时 ≥ 前端最长 axios 超时（300s），模板注释交叉引用两处值。
- [ ] AC4 代码检索 `projectMapper.updateById` 在修复清单文件中不再出现（publish/preview-style 等已修先例之外的缺陷位置全部消除）；新写入模式符合「UpdateWrapper 显式 set / 条件更新」。
- AC5（人工验证，不阻塞合入）：生成中项目在编辑页修改 topic 不中断生成，生成完成后 status/current_version_id 不被编辑操作回写覆盖。
- AC6（人工验证）：禁用用户（enabled=false）后其存量 token 请求 /api/** 返回 401（最多 60s 缓存延迟）；角色变更后同口径生效。
- AC7 现有单测 `mvn test` 通过（CarRagServiceTest 等既有 38 个测试类不回归）。

## Out of Scope

- 前端角色显隐的实时刷新（token 有效期内前端 role 不同步，后端已 fail-closed 兜底）。
- logout token 黑名单 / refresh token 机制。
- 登录限流、CORS 白名单收紧、异常信息透传收敛（属评审三.3 安全加固，另行任务）。
- Flyway 迁移、状态机组件收敛、异步化生成链路（属 P1/P2 评审项）。
- dev.sh 本地联调链路（vite proxy 无 180s 限制，无需改动）。

## Key Decisions

- **R1 取 300s 对齐**：nginx 调到 300s（大于前端最长 300s 接口，等于即可满足「nginx 不先断」），不改前端超时值（值本身合理）。
- **R3 fail-closed**：查库失败时拒绝认证（内部系统宁可 401 也不放行陈旧角色）；探活口径不变（/api/auth/me 401 算存活）。
- **R3.4 本地 60s TTL 缓存**：避免每请求查库；牺牲最多 60s 的禁用生效延迟，内部系统可接受。
- **R2 状态推进统一条件更新**：与既有原子抢占范式（claimGenerating）同族，不新增 ProjectStatusService 组件（属 P1，本任务只消灭缺陷点）。

## Open Questions

（无——全部已通过代码证据解决或按 Key Decisions 定案）