# Tavily 正文能力与计费（官方 API 文档取证，2026-09-27）

> 目的：为「背景题 WEB 素材深度」方案提供事实依据。来源：Tavily 官方 API Reference（`/search`、`/extract`）与 Pricing 页。

## 1. 端点能力

| 端点 | 能力 | 关键参数 | 返回字段 |
|---|---|---|---|
| `POST /search` | 搜索；可附带整页正文 | `search_depth(basic/advanced/fast/ultra-fast)`、`chunks_per_source(1-3)`、`include_raw_content(boolean/"markdown"/"text")`、`max_results(≤20)` | 每条 `content`（片段）、`raw_content`（整页，仅 include_raw_content=true）、`score`、`published_date` |
| `POST /extract` | 按 URL 抽正文 | `urls(1-20)`、`query`（按语义重排片段）、`chunks_per_source(1-5)`、`extract_depth(basic/advanced)`、`format(markdown/text)` | `raw_content`（整页或重排片段 `<chunk> [...] <chunk>`）、`failed_results[]` |

## 2. 计费（Free = 1,000 credits/月，每月首日重置）

- `search`：credits 由 `search_depth` 决定——`basic`/`fast`/`ultra-fast` = 1，`advanced` = 2。**文档未标示 `include_raw_content`/`chunks_per_source` 额外计费**（仅增大响应体积/延迟）。
- `extract`：`basic` = 1 credit / 5 个成功 URL；`advanced` = 2 credits / 5 个成功 URL。逐 URL 失败进 `failed_results`，HTTP 200 也可能 `results` 为空。
- 文档未提供免费套餐的**速率上限**数值；超限返回 429（带 `Retry-After`）。

## 3. 与现状对照

- 现状：`TavilySearchTool` 固定 `search_depth=basic`、不带 `include_raw_content`，取 `content` 作为命中片段（`snippet`）。
- `content` 在 basic 档默认已含最多 3 段 ×500 字（`chunks_per_source` 默认 3，以 `[...]` 拼接）；但 `SubAgentRunner.snippet()` 把进入 LLM 上下文/降级产物的文本**截到 200 字**。
- 结论：素材薄的成因一半是**自截断**（200），一半是**未取整页**（`content` 仅相关片段、非整页）。

## 4. 方案要点（供设计参考）

- 整页正文走 `include_raw_content: "markdown"`（不额外计费）或按需 `/extract`（精确、额外 credits）。
- 整页体积大（单页可达数十 KB），**必须在工具层截断**（建议 ~2000 字）并把注入 LLM 的用量按问题类型分档（背景题多、参数题少）。
- 抽取有失败面（`failed_results`）；空/失败必须降级回 `content`/`snippet`，不得丢失既有字段。
- `raw_content` 不可直接复用 `snippet` 语义（引用/预览仍用 `content`），需新增独立载体字段。
