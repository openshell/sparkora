# design.md — 澄清阶段 AI 思考过程 + max_tokens 修复 + 附属信息字段重构

> 关联：`prd.md`（R1–R6 / AC1–AC9）。跨后端（AI/Deep/Service/Controller/DB）与前端（表单/列表/StepBrief/组件）+ spec。

## 1. 边界与目标

本任务三块改动，按依赖排序：

1. **AiClient 透出 reasoning**（基础设施，被 2 依赖）。
2. **修复澄清阶段截断 + 落库/透出思考过程 + prompt 结合创建输入**（ClarifyService/DeepController/Flyway）。
3. **附属信息字段重构**：删 `keywords`/`remark`，`extraInfo`→`contentDescription`（存量迁移），全链路注入（ProjectEdit/ProjectList/DTO/entity/控制器/Version/Writer/Brief/Research）。

## 2. 数据契约

### 2.1 AiClient.ChatResult（增量、向后兼容）

```java
public record ChatResult(String content, String model, int totalTokens, String finishReason, String reasoning) {
    public ChatResult(String content, String model, int totalTokens) { this(content, model, totalTokens, null, null); }
    public ChatResult(String content, String model, int totalTokens, String finishReason) { this(content, model, totalTokens, finishReason, null); }
}
```

- `parseChat` 读 `message.reasoning`，缺省回退 `message.reasoning_content`（不同模型字段名不同；实测 axonhub→`deepseek-v4.1-flash` 用 `reasoning`）。
- 仅当 `requireJson`（`chatJson`）时对外暴露 reasoning；`chat`/`chatMessages` 也捕获但业务不消费，无副作用。
- 保留 3 参/4 参构造器，既有调用方与测试零改动。
- 新增静态常量 `REASONING_MAX_CHARS`（建议 20000）用于落库前截断，防超长。

### 2.2 `sparkora_article_project` 字段（Flyway V4）

| 变更 | 列 | 类型 | 说明 |
|---|---|---|---|
| 新增 | `content_description` | TEXT | 内容描述（原 `extra_info` 升级；全链路生效） |
| 删除 | `extra_info` | TEXT | 存量搬移到 `content_description` 后 DROP |
| 删除 | `keywords` | VARCHAR(500) | 无生效点（仅车型识别输入 + Version prompt） |
| 删除 | `remark` | VARCHAR(500) | 纯存储、无生效点 |

### 2.3 `sparkora_article_brief` 字段（Flyway V4）

| 变更 | 列 | 类型 | 说明 |
|---|---|---|---|
| 新增 | `research_reasoning` | TEXT | 澄清阶段 AI 思考过程（reasoning），落库供 `/deep/status` 透出 |

### 2.4 迁移脚本 `V4__content_description_and_brief_reasoning.sql`

严格按 `db/migration/README.md` 约定（不用 `DO $$`；补列→UPDATE 搬数→DROP，单语句）：

```sql
ALTER TABLE sparkora_article_project ADD COLUMN content_description TEXT;
UPDATE sparkora_article_project SET content_description = extra_info WHERE extra_info IS NOT NULL;
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS extra_info;
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS keywords;
ALTER TABLE sparkora_article_project DROP COLUMN IF EXISTS remark;
ALTER TABLE sparkora_article_brief ADD COLUMN research_reasoning TEXT;
```

> 回滚：新增更高版本 `V5` 反向补列/搬回（生产已 DROP 的列无法自动还原，属可接受风险；`keywords`/`remark` 无生效点）。

## 3. 接口契约

| 接口 | 变更 |
|---|---|
| `POST /api/projects` | 请求去掉 `keywords`/`remark`，`extraInfo`→`contentDescription` |
| `PUT /api/projects/{id}` | 同上（白名单列显式 set：`content_description` 替换 `extra_info`） |
| `POST /api/projects/{id}/deep/clarify` | 请求体不再需要 `{topic, extraInfo}`（后端从项目读）；保留 `@RequestBody(required=false)` 兼容；响应不变 `{briefId, stage:"PLANNING"}` |
| `GET /api/projects/{id}/deep/status` | 响应**增量** `planReasoning`（`research_reasoning` 非空时；缺失不出现，旧前端不读不报错） |

## 4. 后端设计

### 4.1 AiClient

- `parseChat`：在取 `content` 后，`reasoning = firstNonBlank(msg.path("reasoning").asText(null), msg.path("reasoning_content").asText(null))`，截断 `REASONING_MAX_CHARS`。
- 不改 `requireJson` 截断异常逻辑（R1 修复在调用方提额，不在此处）。

### 4.2 ClarifyService（R1 + R2 + R4 + R4b 的输入源）

- 签名收敛为 `start(Long projectId)`：读项目实体 `p`（已有），校验 `p.topic` 非空；其余字段从 `p` 取。
  - `runAsync(Long briefId, Long projectId)`：@Async 内重载项目，避免跨线程陈旧实体。
- `generatePlan(ArticleProjectEntity p)`：
  - user prompt 组装改为：`主题:` + `内容描述:`（非空才加）+ `目标读者:`（非空才加）+ `目标字数:`（非空才加，默认口径对齐其它链路 1500）。
  - `ensureBackgroundQuestion(plan, topic, contentDescription)`：probe 用 topic + contentDescription。
  - **R1**：`chatJson(system, user, 8192)`；`catch` 任何失败（含 `finishReason=length` 抛出的 AiException）→ 提额 `16384` 重试一次（附纠错话术，范式抄 `BriefService.java:96-108`）；仅两次均失败才抛，由 `runAsync` 落 `last_brief_error`（现有逻辑）。
  - 成功后 `b.setResearchReasoning(cr.reasoning())`（与 plan/questions 同一次 update）。
- `start` 的 `self.runAsync(b.getId(), projectId)`。

### 4.3 DeepController

- `clarify`：不再解析 body 的 topic/extraInfo，直接 `clarifyService.start(projectId)`（保留 `@RequestBody(required=false)` 以免旧前端 400）。
- `status`：`if (b.getResearchReasoning() != null) out.put("planReasoning", b.getResearchReasoning());`。

### 4.4 ArticleProjectController

- create/update：删 `keywords`/`remark` 的 set；`extraInfo`→`content_description`；`matcherService.match(req.getTopic(), req.getContentDescription())`。

### 4.5 CarModelMatcherService

- `match(String topic, String keywords)` → `match(String topic, String contentDescription)`；user prompt 行改为「内容描述：…」。调用点 `ArticleProjectController.java:114`、`DeepResearchService.java:447` 同步。

### 4.6 DeepResearchService（R4b-A）

- 新增 `resolveContentDescription(projectId)`（仿 `resolveTopic`），在 `doRunAsync` 解析一次并传入子代理。
- `subAgent.research(..., contentDescription, snapshot)` 新增第 8 参（签名变更，测试同步）。

### 4.7 SubAgentRunner（R4b-A：只进汇总上下文，不改检索 query）

- `research(..., String contentDescription, ...)`；`ctx` 开头加「写作意图/内容描述: …」(非空才加)，置于「研究问题:」之前。
- **不改** `compositeQuery`（KB）与 `webQuery`（WEB）——保留检索语料纯净（Q4=A）。

### 4.8 BriefService（④简报注入）

- `buildDeepBriefUserPrompt`：在 `主题:` 后加 `内容描述:`（非空）、`目标读者:`（非空）、`目标字数:`（非空，默认 1500），再接现有 锁定需求/假设/事实手册。

### 4.9 DeepWriterService（⑤深度写作注入）

- user prompt：现有 `目标字数：N` 后追加 `目标读者:`/`内容描述:`（非空才加）。

### 4.10 VersionService（⑥多版本注入）

- `buildUserPrompt` 头部：`主题/关键词/目标读者/目标字数` → `主题/内容描述/目标读者/目标字数`（`nv(p.getContentDescription())`）。
- 删除原 309-311 的独立「用户补充信息」块（其内容即内容描述，已并入头部）。

## 5. 前端设计

- `ProjectEdit.vue`：删「关键词」（区块02）、「备注」（区块03）；「补充信息」→「内容描述」（label + placeholder 描述期望内容/背景素材，maxlength 5000）；`form` 响应式字段删 `keywords`/`remark`、`extraInfo`→`contentDescription`；`startDeep` 调用改无参。
- `ProjectList.vue`：删「关键词」列。
- `api/index.js`：`startDeep: (id) => http.post(.../deep/clarify, {}, {...})`。
- `StepBrief.vue`：`startDeep` 调用简化；`applyDeepStatus` 增 `deepReasoning`；`DeepPlanCard` 传 `:reasoning`。
- `DeepPlanCard.vue`：新增可选 prop `reasoning`，在计划 collapse 后追加「AI 思考过程」`el-collapse-item`（默认折叠，非空才渲染）。
- `tests/fixtures/data.js`：去掉 fixture 的 `keywords`（或改 `contentDescription`）。

## 6. 兼容性与风险

| 风险 | 处理 |
|---|---|
| 存量 `extra_info` 丢失 | V4 UPDATE 搬数到 `content_description`，不可逆（DROP 后无回滚），已获 Q3=A 批准 |
| `keywords` 删除影响车型识别 | 识别改 `topic + content_description`，语义等价于旧 topic+extraInfo |
| `SubAgentRunner.research` 8 参改签名破坏测试 | 机械补参；`DeepResearchServiceProgressTest` mock stub 补第 8 个 `any()` |
| reasoning 超长撑库 | `REASONING_MAX_CHARS` 截断后落库 |
| 非推理模型无 reasoning | 前端 `v-if` 隐藏；`/deep/status` 不出现该字段 |
| 旧前端未升级仍发 `{topic, extraInfo}` | `clarify` 忽略 body，兼容 |
| prompt 增行影响既有断言测试 | 逐一核对 `BriefServiceTest`/`DeepWriterServicePromptTest`/`VersionServiceAsyncTest` 的捕获断言 |

## 7. 关键决策记录

- **Q1=A**：思考过程事后一次性展示（复用异步+轮询）。
- **Q3=A**：`extraInfo` 改名 `contentDescription` + 存量迁移（非删除概念）。
- **Q4=A**：内容描述只进子代理汇总上下文，不改 KB/WEB 检索 query。
- **注入点**：内容描述/目标读者/目标字数统一注入 ②澄清+④简报+⑤深度写作+⑥多版本；研究阶段③按 Q4=A 进汇总上下文。
