# axonhub 能力探针报告（C0）

> 任务：`.trellis/tasks/10-02-c0-upgrade-probe`。目的：为 C2（结构化输出）与 C3（Tool Calling）
> 的实现深度定档。探针日期 2026-10-03，端点 `$AI_BASE_URL`（axonhub，OpenAI 兼容单 key），
> 模型 `AI_MODEL=deepseek-v4-pro-cus`（请求体标识）；axonhub 实际路由/serving 模型响应字段为
> `deepseek-v4.1-flash`（reasoning 模型）。选型以实测 serving 模型为准。
> **本报告不含任何密钥/token 值。**

## 0. 结论速览

| 能力 | 结论 | 对 C2/C3 的影响 |
|---|---|---|
| (a) `response_format.json_schema`（strict） | **退化（被忽略，2xx 但不遵守）** | C2 不能用原生 provider 结构化约束；`useProviderStructuredOutput()` 会失效 → **必须依赖 `validateSchema()` 响应侧自纠错** |
| (b) Tool Calling（`tools`/`tool_choice`） | **支持** | C3 可按标准 Tool Calling 实现（`SubAgentRunner` 循环可交给框架驱动） |
| (c) reasoning 字段透传 | **支持**（字段名 `reasoning`） | `AiClient.parseChat` 现有「先 `reasoning` 后回退 `reasoning_content`」策略正确；无需改字段 |
| (d) 基线 `response_format.json_object` | **支持** | 现有 `AiClient.chatJson` 语义不变，C0/C1 期间零回归 |
| (e) `/v1/embeddings` | **支持**（model=`Qwen3-Embedding-8B`，维度 **1024**） | C5 `PgVectorStore` 配置 `dimensions=1024`；与现向量表 DDL 一致 |

**硬结论**：axonhub **不透传 `json_schema` strict**（静默忽略），故 **C2 必须走「prompt 内 schema + `validateSchema()` 自纠错」路线**，
`useProviderStructuredOutput()` 只可作为「未来 provider 升级后的免费增强」保留开关，不能作为正确性依赖。
**C3 可全量使用 Tool Calling。**

---

## (a) `response_format.json_schema`（strict）—— 退化

### 请求要点

```
POST $AI_BASE_URL/v1/chat/completions
{
  "model": "<AI_MODEL>",
  "messages": [ {"role":"system"...}, {"role":"user":"给出城市北京的信息"} ],
  "response_format": {
    "type": "json_schema",
    "json_schema": {
      "name": "CityInfo",
      "strict": true,
      "schema": {
        "type":"object",
        "properties": {"city":{"type":"string"},"population":{"type":"integer"}},
        "required": ["city","population"],
        "additionalProperties": false
      }
    }
  },
  "max_tokens": 16384
}
```

### 响应特征（脱敏）

- **HTTP 200**，无 `error`；`choices[0].finish_reason=stop`。
- 返回内容**为合法 JSON**，但**完全不遵守 schema**：
  - 用**中文字段名**（`城市`、`人口`、`英文名`…），而 schema 要求 `city`/`population`；
  - 缺失 `required` 字段（`city`/`population` 均不存在）；
  - 含大量 `additionalProperties:false` 禁止的额外字段（20+ 个）。
- 关键观察：请求侧**未报错**——不是「拒绝不支持」，而是**静默忽略 schema**，退化为普通 JSON 输出。

### 首次小额度实验（旁证 reasoning 预算陷阱）

- 同一请求 `max_tokens=1024` 时：`finish_reason=length`，`reasoning` 段很长、`content=""`（空）。
- 说明该模型为 reasoning 模型，`max_tokens` 为**含推理的总预算**；额度不足时推理吃光预算、正文为空。
  → 与 `.trellis/spec/backend/ai-rag-guidelines.md`「reasoning 模型的额度陷阱」一致；C2 的 JSON 调用仍须保留
  「截断 → 提额重试」路径。

### 结论

**退化（被忽略但可跑）**。`strict:true` 未获 provider 层强制；C2 不得依赖 `useProviderStructuredOutput()`，
必须由 `entity(X.class, spec -> spec.validateSchema())` 在响应侧校验并按错误回填 prompt 自纠错。

---

## (b) Tool Calling —— 支持

### 请求要点

```
POST $AI_BASE_URL/v1/chat/completions
{
  "model": "<AI_MODEL>",
  "messages": [ {"role":"user","content":"北京现在的天气怎么样?"} ],
  "tools": [ {"type":"function","function":{
      "name":"get_weather","description":"查询指定城市的当前天气",
      "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
  }} ],
  "tool_choice": "auto",
  "max_tokens": 8192
}
```

### 响应特征（脱敏）

- **HTTP 200**，无 `error`。
- `choices[0].finish_reason = "tool_calls"`。
- `choices[0].message` 含 `tool_calls` 数组：
  ```
  [ {"id":"call_...","index":0,"type":"function",
     "function":{"name":"get_weather","arguments":"{\"city\":\"北京\"}"}} ]
  ```
- `message.content` 为空（符合 tool_calls 语义）。

### 结论

**支持**。模型正确识别工具、返回标准 `tool_calls`（含 `id`/`name`/JSON `arguments`）。
C3 可按标准 `ToolCallback` + Tool Calling 循环实现，无需自研 function-call 解析兜底。

---

## (c) reasoning 字段透传 —— 支持

### 观察

在 (a)/(b)/(d) 三次成功响应中，`choices[0].message` 的 keys 均为：

```
["content", "reasoning", "role"]          # (a)(d)
["content", "reasoning", "role", "tool_calls"]   # (b)
```

- 响应**含 `reasoning` 字段**（非 OpenAI 标准的 `reasoning_content`）。
- `reasoning` 长度可观（实测 (a) 约 7218 字符），`content` 为业务正文。
- `message` 中**不含 `reasoning_content`**（`has_reasoning_content=false`）。

### 结论

**支持**。字段名确认为 **`reasoning`**。
现有 `AiClient.parseChat`（10-02-brief-reasoning-maxtokens）「读 `reasoning`，缺省回退 `reasoning_content`」的策略与实测吻合，
无需调整；C1 迁移到 Spring AI `ChatResponse` 时须确认框架能否透出非标准 `reasoning` 字段（若被框架丢弃，
需在 C1 保留自定义解析或经 Advisor/原生响应获取）。

---

## (d) 基线 `response_format.json_object` —— 支持（对照组）

- 请求 `response_format:{"type":"json_object"}` + system「只输出 JSON」，`max_tokens=16384`。
- **HTTP 200**，`finish_reason=stop`，`content` 为合法 JSON（`{"city":"Beijing","population":21540000}`），
  `usage.total_tokens=462`。
- 说明现有 `AiClient.chatJson` 依赖的 `json_object` 模式在 axonhub 上稳定；C0/C1 期间行为不变。

---

## (e) `/v1/embeddings` —— 支持（C5 前置事实）

- **HTTP 200**，`object=list`，`model=Qwen3-Embedding-8B`，`data[0].embedding` 长度 **1024**，`usage.prompt_tokens=3`。
- 与 `AI_EMBEDDING_DIM=1024`、现有 4 张向量表 DDL 一致。
- C5 建 `PgVectorStore` 时 `dimensions=1024`、COSINE、HNSW；`embedding_model` metadata 需承载模型名（硬验收）。

---

## 对 C2/C3 实现深度的定档建议

1. **C2**：
   - `useProviderStructuredOutput()` **保留调用**（对将来 provider 升级无害、自动退化），但**正确性只能依赖 `validateSchema()`**。
   - schema 单一来源仍取 DTO 类型；不能因 provider 忽略而在 prompt 里省掉 schema（prompt 内 schema 是当前唯一「请求侧」提示）。
   - `sanitizeAiJson` **保留**（provider 非原生、返回可能带围栏/裸控制字符；探针未证明其稳定到可删）。
   - 截断（`finish_reason=length`）与空 content（reasoning 吃光预算）仍须独立「提额重试」，不得与 schema 自纠错混一。
2. **C3**：
   - 走标准 Tool Calling；保留 `SearchTool.available()`/`toolHealth` 契约（见 ai-rag-guidelines.md「搜索工具可用性契约」），
     工具异常/失败降级路径不变。
   - `rawFallback` 降级路径保留（探针未验证 tool 循环下汇总失败行为，按保守设计）。
3. **C1 注意**：Spring AI 2.0 的 `ChatResponse` 对非标准 `reasoning` 字段的透传能力需在 C1 验证；
   本探针确认的是 **HTTP wire 层** 有 `reasoning`，框架层是否保留待 C1 实测。

---

## 回退评估：若 Boot 4 不兼容（Boot 3.5 + Spring AI 1.1）

> 本子任务实测 Boot 4.0.1 + Spring AI 2.0.1 **编译/测试/启动三绿**（见下），故**未触发回退**。
> 按要求仍记录回退方案评估，以备后续子任务遇到阻塞。

- **版本组合**：`spring-boot-starter-parent:3.5.x` + `spring-ai-bom:1.1.2`（本地仓库已缓存 1.1.2）+ `mybatis-plus-spring-boot3-starter`。
- **兼容性评估**：
  - Jackson：Boot 3.5 仍是 Jackson 2（`com.fasterxml`）原生，**现有 37 个 `ObjectMapper` 注入点零改动**，
    无需 `spring-boot-jackson2` 兼容模块——回退反而**减少**改动面。
  - Spring AI 1.1 的 API 与 2.0 有差异（如 `ChatClient` advisor/spec、starter 命名），C1~C7 需按 1.1 文档调整。
  - Boot 4 引入的 `spring-boot-http-client`（`HttpClientSettings`/`ClientHttpRequestFactoryBuilder`）
    在 3.5 下对应旧 `org.springframework.boot.web.client.*`，本次已改的 9 处 RestClient 构造需回改。
- **结论**：回退方案可行且改动可控，但会放弃「一次到位」目标；当前无触发必要。

---

## 附：C0 升级实测结果（供验收引用）

- `mvn -q -DskipTests compile`：**通过**。
- `mvn test`：**Tests run: 571, Failures: 0, Errors: 0, Skipped: 0**（仓库现有用例数已从基线 510 增长到 571）。
- Boot 4.0.1 启动：`Started SparkoraApplication`，Tomcat 5699（临时端口，因 5661 被产线 docker 容器占用）。
- 登录冒烟：`POST /api/auth/login`（admin/admin123）→ 返回 JWT（长度 214）→ `GET /api/auth/me` **HTTP 200**
  `{"code":0,"data":{"username":"admin","role":"ADMIN","editorOrAbove":true},"msg":"ok"}`。
- `GET /api/auth/me` 无 token → **HTTP 401**（安全链正常）。
- Spring AI PgVectorStore 自动配置已激活（日志 `Initializing PGVectorStore schema for table: vector_store`），
  但 `initialize-schema` 默认关闭，**未实际建表**（`vector_store` 在库中不存在，无 Flyway 之外的 DDL 副作用）。

### Boot 4 依赖破坏面（C0 实测补充）

1. **`org.springframework.boot.web.client` 包移除**：`ClientHttpRequestFactories` /
   `ClientHttpRequestFactorySettings` 在 Boot 4 迁至 `org.springframework.boot.http.client` 并重构 API
   （`HttpClientSettings` + `ClientHttpRequestFactoryBuilder.detect().build(settings)`）。
   已最小化改 9 处 RestClient 构造（AiClient/AiImageClient/EmbeddingClient/BydCmsClient/BydNewsClient/
   TavilySearchTool/SearxngSearchTool/WenyanServerService/QiniuService），语义（连接/读超时）保持不变。
2. **MyBatis-Plus 分页插件拆模块**：3.5.9+ 起 `PaginationInnerInterceptor` 需显式引入
   `mybatis-plus-jsqlparser`（不引则 `MybatisPlusConfig` 编译失败）。已按 starter 同版本添加。
3. **Jackson 2 → Jackson 3**：Boot 4 默认 Jackson 3（`tools.jackson`），**不再提供
   `com.fasterxml.jackson.databind.ObjectMapper` Bean**（`spring-boot-starter-web` 已改走
   `spring-boot-starter-jackson`）。本项目业务层有 37 个文件注入 Jackson 2 `ObjectMapper`。
   C0 采用 Boot 4 官方兼容模块 `org.springframework.boot:spring-boot-jackson2`（4.0.1）恢复该 Bean，
   **业务代码零改动**；Jackson 3 迁移留待后续子任务。
   注意：兼容模块配置前缀为 **`spring.jackson2.*`**（非 `spring.jackson.*`），已在 YAML 镜像
   `default-property-inclusion: non_null` 以保持内部 JSON 拼装行为与 Boot 3.3 等价。
4. **Flyway**：随 Boot 4 BOM 从 10.10.0 抬到 **11.14.1**，迁移/checksum 正常（启动无 Flyway 报错）。

### 配置与 `.env` 说明

- `application.yml` 新增 `spring.ai.openai.{base-url,api-key,chat.options.model,embedding.options.model}`，
  全部复用既有 `AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL`/`AI_EMBEDDING_MODEL`。
- **`.env.example` 未改动**：所有键均已存在，无新增环境变量（符合「仅在确有新增键时同步」）。
- **真实 `.env` 未改动**（`.gitignore` 覆盖，git 无差异）；自定义 `sparkora.ai.*`（AiProperties）配置保持不动，
  `spring.ai.*` 与其前缀不冲突。

### 版本锁定

| 组件 | 版本 |
|---|---|
| `spring-boot-starter-parent` | 4.0.1 |
| `spring-ai-bom` | 2.0.1 |
| `spring-ai-starter-model-openai` | 2.0.1 |
| `spring-ai-starter-vector-store-pgvector` | 2.0.1 |
| `mybatis-plus-spring-boot4-starter` | 3.5.17 |
| `mybatis-plus-jsqlparser`（分页插件独立模块） | 3.5.17 |
| `spring-boot-jackson2`（兼容模块） | 4.0.1 |
| Flyway（随 Boot BOM） | 11.14.1 |
| PostgreSQL 驱动 / JJWT | 随 BOM / 0.12.6 |
