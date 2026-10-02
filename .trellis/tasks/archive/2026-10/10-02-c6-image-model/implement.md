# Implement — C6 ImageModel

## 关键决策（已由主会话实测确认）

- **text2image → Spring AI ImageModel**：C0 已引入 `spring-ai-starter-model-openai`，自动配置提供
  `OpenAiImageModel` bean（`spring.ai.openai.image.options.model`）。
- **octet-stream 容忍性已实测通过**：Spring AI 2.0 的 OpenAI SDK（`com.openai` 官方 SDK）对
  `application/octet-stream` 包裹的 JSON 响应**能正常解析**（旧 `RestClient` 的 String 转换器不能，
  这正是旧代码用 `byte[]` 收响应的原因）。故 text2image 可安全改走 ImageModel，不再需要 byte[] hack。
- **edits 端点无 Spring AI 支持**：`OpenAiImageModel` 内部只调 `images/generations`，**不支持**
  `/v1/images/edits` multipart。故 `generateImage2Image`（多参考图）**必须保留自研 RestClient**。

## 实现要点

### 1. `AiImageClient`（改）
- 注入 Spring 自动配置的 `ImageModel`（可选，`ObjectProvider<ImageModel>` 或 `@Autowired(required=false)`），
  兼容旧单测构造 `AiImageClient(AiProperties)`（imageModel=null → fallback 自建）。
- `generateText2Image`：按序轮询 `props.imageModelList()`，每个模型构造
  `OpenAiImageOptions.builder().model(model).n(1).size(size).build()`，调
  `imageModel.call(new ImagePrompt(prompt, opts))`，取 `getResult().getOutput()`：
  - `getUrl()` 非空 → 返回；
  - 否则 `getB64Json()` 非空 → 返回 `"data:image/png;base64," + b64`；
  - 否则视为该模型失败，轮询下一个。
  - 失败语义（`AiException("所有图片模型均失败: " + errs)`）与日志文案**保持不变**。
- `generateImage2Image`：**完全保留现状**（自研 RestClient + multipart 保序重复 image part）。
- 保留私有 `rest` 仅供 edits 使用；text2image 不再用 `postForJsonText`/`parseFirstUrl`（可删仅 text2image 专用者，
  但 `parseFirstUrl` 若 edits 不用则删；edits 响应仍走 `postMultipartForJsonText` + `parseFirstUrl`）。
  → **注意**：edits 也复用 `parseFirstUrl`，故 `parseFirstUrl` 保留。

### 2. 契约不变（硬约束）
- 多参考图上限 1~4、**顺序契约**（先上传后图库 id，不重排）：仅 edits 涉及，不动。
- 错误提示文案：`AI_IMAGE_MODELS / AI_IMAGE_MODEL 均未配置`、`图生图参考图为空`、
  `图生图参考图与文件名数量不匹配`、`所有图片模型均失败`、`所有图片模型均失败(图生图): …`
  逐字不变。
- `GenResult(url, model)` 不变；`ImageService`/`ImageController` 零改动。

### 3. 配置
- `application.yml` 增加 `spring.ai.openai.image.options.model: ${AI_IMAGE_MODEL:}`（可选）；
  实际模型仍由 per-call `OpenAiImageOptions.model(...)` 按 `imageModelList()` 覆盖。

## 验证
- `mvn -q -DskipTests compile`
- `mvn test`（保留 `AiImageClientMultiRefTest` 对 edits 的断言；新增 text2image 经 ImageModel 的用例：
  本地 stub 返回 url 与 b64_json 两路、octet-stream 容忍、多模型轮询、全失败文案）
- `ImageServiceMultiRefTest`/`ImageServiceBodyImageTest` 等零回归。

## 回退点
- 单文件 `AiImageClient` + 测试 + 配置；`git revert` 即回退。
