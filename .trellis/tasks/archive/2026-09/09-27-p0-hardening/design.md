# P0 修复 技术设计

> 三个子问题相互独立，可分别实施/验证；无 schema 变更、无新表、无新依赖。

## 1. R1 超时对齐

**改动点**：`frontend/nginx.conf.template` 单文件。

- `proxy_read_timeout 180s` → `300s`；`proxy_send_timeout 180s` → `300s`。
- 注释补一行：值须 ≥ `frontend/src/api/index.js` 中最长 axios 超时（当前 300000ms），调任一侧须联动检查。
- `proxy_connect_timeout 10s` 不动。

**生效方式**：产线需 `docker compose up -d --build frontend` 重建（代码烘焙进镜像）；dev 链路无影响。

**回滚**：模板值改回 180s + 重建，无数据影响。

## 2. R2 消除 updateById 全字段回写

### 模式

全部改为 UpdateWrapper 显式 set。分三类：

**A. 白名单编辑（PUT /{id}）**——用户业务字段显式列清单，绝不触碰服务端状态列：

```java
UpdateWrapper<ArticleProjectEntity> uw = new UpdateWrapper<>();
uw.eq("id", id)
  .set("topic", req.getTopic())
  .set("keywords", req.getKeywords())          // 可空字段照 set(null)——覆盖式编辑语义,同现状
  .set("audience", req.getAudience())
  .set("word_count_target", req.getWordCountTarget())
  .set("brand_voice_profile_id", req.getBrandVoiceProfileId())
  .set("extra_info", req.getExtraInfo())
  .set("selected_title", req.getSelectedTitle())
  .set("remark", req.getRemark())
  .set("updated_at", LocalDateTime.now());
mapper.update(null, uw);
```

语义与现状对齐（原来是全字段覆盖编辑，null 也覆盖）；`gen_source/imitation_*` 不可编辑（现状就不改），status/current_*/publish_*/last_*_error 全部不再被触碰。

**B. 状态推进（条件更新，防状态机回退）**——成功分支带状态白名单，失败分支限定「仅生成中状态回退」：

```java
// DeepController.generate 成功(示例;VersionService/ImitationService/BriefService 同族)
projectMapper.update(null, new UpdateWrapper<ArticleProjectEntity>()
        .eq("id", projectId)
        .in("status", "READY", "DRAFT")              // 白名单:只在允许的源状态上推进
        .set("current_version_id", versionId)        // 注:须保留「首版才设 current」语义,见下
        .set("status", "VERSIONS_READY")
        .set("updated_at", now));
```

- **current_version_id 首版设值语义**：现状 `if (p.getCurrentVersionId() == null) p.setCurrentVersionId(...)`。拆两条条件更新：
  1. `eq("current_version_id", null)`（首版）: set current_version_id + status + updated_at；
  2. `isNull("current_version_id", false)` 兜底——若第一条已生效(claimed=1)则跳过；否则第二条 `eq id` + set status/updated_at（不 set current）。
  顺序执行，任一 claimed=1 即视为推进成功；都 0（如已 PUBLISHED_DRAFT）则不推进（幂等安全：深度追加生成不覆盖用户已选 current，PUBLISHED_DRAFT 不回退）。
- **VersionService.generate 成功分支**：源状态必为 GENERATING_VERSIONS（本方法开头已原子抢占置此态），条件 `eq status=GENERATING_VERSIONS` + set VERSIONS_READY/current_version_id(首版,同上两条拆分)/last_version_error。失败分支：`in status=GENERATING_VERSIONS,GENERATING_BRIEF` → set READY + last_version_error（若状态已被并发改为 VERSIONS_READY 则不动，保守安全）。
- **ImitationService/BriefService 同族**：成功 `in status=GENERATING_BRIEF`（+陈旧宽限不做，抢占方已保证归属）→ READY/current_brief_id/清错；失败 `in status=GENERATING_BRIEF,GENERATING_VERSIONS` → DRAFT + 错误列（ImitationService 失败需重取 fresh 后按 id set，不依赖陈旧快照）。
  - 说明：抢占时 set 过 `last_brief_error=null`，成功分支清错列可省略（本就 null），保留 set 无害。
- **ClarifyService 异步清错/写错**：单列 set（last_brief_error + updated_at），按 id，无需条件。

**C. 非状态单列写入**：setSelectedTitle（selected_title + updated_at）、setCurrent（current_version_id + updated_at），按 id 精确 set。

### 保持不变

- 已修先例（preview-style / publish-meta / PublishService）不动。
- 抢占分支（claimGenerating 等）不动。
- 失败分支不重取整行再 updateById——按 id/状态条件直接 update，天然无快照回写。

### 兼容性

- 无 schema 变更。行为差异：PUBLISHED_DRAFT 项目上深度追加生成的版本落库但不再回写状态（原来会拉回 VERSIONS_READY——这正是要消除的回退 bug，与 docs/README.md 4.2 状态守护「下游已触发禁止回退」语义一致）。
- 失败分支在状态已被并发推进时不覆盖 last_*_error——用户在项目列表看到的错误以最新一次动作为准，可接受（原行为会覆盖 status，危害更大）。

### 回滚

纯代码回退，无数据影响。回退后恢复原缺陷行为。

## 3. R3 JWT 查库校验

**改动点**：`security/JwtAuthenticationFilter.java`（+ 新增内部缓存，无新文件）。

### 数据流

```
Bearer token → JwtUtil.parse(签名+过期校验,失败→未认证,现状不变)
  → subject(userId) → 本地缓存查 userId→{enabled,role,fetchedAt}
      命中且未过期(TTL 60s) → 用缓存值
      未命中/过期 → UserMapper.selectById(userId)
          用户不存在 / enabled != true → 未认证(不注入 SecurityContext)
          查询异常(RuntimeException) → fail-closed,未认证,log.warn 记录
  → CurrentUser(userId, username(库中), role(库中)) → 注入 SecurityContext
```

### 要点

- username/role **以库为准**，token claims 的 username 仅用于日志对照（不一致时 log.warn，防改名后旧 token 借 username 冒用——鉴权只认 role，username 不参与鉴权）。
- 缓存：`ConcurrentHashMap<Long, CachedUser>`，字段 enabled/role/fetchedAt；TTL 60s。负缓存也存（用户不存在缓存 60s，防无效 token 打库——内部系统攻击面小，简单起见统一处理）。
- fail-closed 理由：内部系统，DB 抖动宁可 401 让用户重登（登录接口不受 filter 影响仍可登录），不放行陈旧角色。
- filter 中注入 UserMapper：JwtAuthenticationFilter 是 @Component 构造注入，无循环依赖风险（UserMapper 为纯 mapper）。
- `@Async` 线程无 SecurityContext 的既有注意（CarSyncJobService 注释先例）：本改动不影响——async 任务不经过 filter。

### 兼容性

- 现有 token 全部继续有效（只要用户 enabled）；角色变更/禁用最多 60s 后生效。
- `/api/auth/me` 行为不变（读 SecurityContext）；docker healthcheck 口径（401=存活）不变。
- 前端无需改动（role 显隐陈旧问题已在 PRD Out of Scope）。

### 回滚

代码回退即恢复「token 即真相」行为，无数据影响。

## 4. 验证命令

```bash
mvn -q -DskipTests compile          # 后端编译
mvn test                            # 既有 38 个测试类
cd frontend && npm run build        # 前端构建(本任务仅改 nginx 模板,预期通过)
grep -rn "updateById" src/main/java --include="*.java" | grep -v "// " # 人工核对修复点清零(允许 style/car 等 entity 其他用法)
```