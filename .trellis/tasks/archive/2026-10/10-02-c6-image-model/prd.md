# C6 ImageModel（保留 edits 自研）

## Goal

文生图调用改接 Spring AI `ImageModel`（OpenAI 兼容）；**图生图 `/v1/images/edits` 多参考图保留自研
`AiImageClient`**（Spring AI 图模型未必覆盖 edits multipart）。

## Depends On

- **C0**。

## Requirements

- 父 R2 的图片侧。
- `AiImageClient` 的 `/v1/images/generations` 文生图接 `ImageModel`；
  `/v1/images/edits` 图生图**保留自研**并保持现有多参考图语义。
- 契约不变：多参考图上限 1~4、**顺序契约**（先上传后图库 id，不重排）、错误码/提示文案不变。

## Acceptance Criteria

- [ ] `ImageService` 单测通过；多参考图上限与顺序契约不变。
- [ ] 生图/图生图错误提示文案不变。
- [ ] `mvn test` 绿。

## Out of Scope

- 图片向量（C5）、图片入库/标签逻辑。
