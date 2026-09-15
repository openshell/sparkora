# Implement — 简报研究链路修复

## 执行顺序

### 1. 配置绑定（D1）
- [ ] `src/main/java/com/sparkora/config/DeepProperties.java:12`：`prefix = "sparkora.ai.deep"` → `"sparkora.deep"`。
- [ ] 确认 `application.yml:57-61` 的 `deep:` 四个键全部可绑定（`search-web-enabled` / `tavily-api-key` / `research-timeout-ms` / `max-agents`）。

### 2. 工具健康契约与去闩锁（D2）
- [ ] `SearchTool.java`：新增默认方法 `configured()`（默认 true）、`lastCallOk()`（默认 true）。
- [ ] `TavilySearchTool.java`：`available()` 改为仅判 `configured()`（key 非空）；`configured()` 覆盖；`lastOk` 重命名/映射为 `lastCallOk()`，仅作展示；`search()` 成功置 true、异常置 false（逻辑保留，但不再影响 available）。
- [ ] `SearxngSearchTool.java`：同构改造（`lastCallHadResults` → `lastCallOk()` 语义）。
- [ ] 确认 `SubAgentRunner.java:75-84` 调用点无需改动，且瞬态失败后仍会重试。

### 3. toolHealth 契约（D3）
- [ ] `DeepController.java`：注入 `SettingService`；`toolHealth` 值改状态码字符串 `OK|DISABLED|UNCONFIGURED|FAILED`。
  - KB：`settingService.isKbEnabled() ? OK : DISABLED`
  - WEB 工具：`webAllowed = deepProps.isSearchWebEnabled() && settingService.isWebSearchEnabled()`；`!webAllowed → DISABLED`；再按 `configured()/lastCallOk()` 定 `UNCONFIGURED/FAILED/OK`。
- [ ] 更新 `status` 的 `Map<String,Object>` 无需改类型（值为 String）。

### 4. 前端（D4）
- [ ] `ResearchProgress.vue:5`：新增 `doneCount` computed（口径 `DONE|FALLBACK|FAILED`）。
- [ ] `ResearchProgress.vue:22-31`：工具健康恒渲染三标签；新增状态码→文案/颜色映射；删除 `webEnabled`（:44）及其 `v-if`。
- [ ] `ResearchProgress.vue:85`：改 `useRoute()`，去 `window.location.pathname`。
- [ ] 校验 `http.js` 拆包惯例（`res.data` 直接是 `R<T>`）。

### 5. 文档同步（R6）
- [ ] `.env.example:185-189`：变量名与实际一致（`.env` 用 `TAVILY_API_KEY`；`DEEP_TAVILY_API_KEY` 可覆盖）；补注绑定路径。
- [ ] `docs/s0-spec.md`：`toolHealth` 契约改状态码（`:663`）；密钥链去掉未绑定的 `sparkora.ai.deep.tavily-api-key`（`:681`）；修正不存在的 `DEEP_SEARCH_WEB_ENABLED`（`:268`）；工具表 TAVILY 降级语义（`:672`）。

## 验证命令

```bash
mvn -q -DskipTests compile          # 后端编译
cd frontend && npm run build        # 前端构建
```

联调（可选，用 `./dev.sh restart backend` 后）：
- 登录拿 token，`GET /api/projects/{id}/deep/status?briefId=<done>` 检查 `toolHealth` 为状态码。
- 触发一次深度研究，观察 `ResearchProgress` 数字 `X/Y` 递增、工具标签如实。

## 风险点 / 回滚

- `SEARCH_WEB_ENABLED` 修复后真实生效；上线前确认部署环境无 `SEARCH_WEB_ENABLED=false` 遗留预期。
- `toolHealth` 契约布尔→字符串：唯一消费方 `ResearchProgress.vue`，两端必须同批提交。
- 回滚：逐文件 git revert；无 DB 变更。

## 提交前检查

- [ ] 无新增硬编码密钥；`.env` 未改动。
- [ ] 中文注释/文案。
- [ ] `docs/s0-spec.md` 与实现一致。
- [ ] 零回归：深度研究/简报/澄清主链路未受影响。
