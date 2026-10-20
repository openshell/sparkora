# BYD 王朝小程序推送契约

> Scope: `BydDynastyPushService` 编译 payload、`BydDynastyPlainTextRenderer` 清洗 Markdown、Windows Local Agent 消费 payload。

## 1. Scope / Trigger

触发条件：修改小程序推送正文或标题清洗、payload 结构、图片引用（`BODY-n` / `COVER-{imageId}`）或 Local Agent 消息协议时，必须先读本文件。

## 2. Signatures

```java
static List<Block> BydDynastyPlainTextRenderer.compile(String markdown)
static String BydDynastyPlainTextRenderer.renderInline(String markdown)

sealed interface Block permits TextBlock, ImageBlock
record TextBlock(String text)
record ImageBlock(String url)
```

payload v1：

```json
{
  "payloadVersion": 1,
  "title": "清洗后的标题",
  "messages": [
    {"type": "text", "content": "纯文本正文"},
    {"type": "img", "imageRef": "BODY-1"}
  ],
  "images": [
    {"id": "BODY-1", "sourceType": "URL", "url": "https://img.example/a.png", "role": "BODY"}
  ]
}
```

## 3. Contracts

- 小程序 `text` 消息只允许纯文本、换行和 emoji；不得出现 Markdown/HTML 标记、链接目标或裸 URL。
- `img` 消息与 `text` 消息并列，按 Markdown 中出现顺序排列。
- 封面图始终是第一条 `img` 消息，ref 为 `COVER-{imageId}`；正文图 ref 为 `BODY-n`。
- `payloadVersion` 保持 `1`；Local Agent 只消费 `text` / `img`，无需同步升级。
- 原始 `ArticleVersionEntity.contentMd` 和 `title` 只读，清洗逻辑不得修改实体。
- 依赖固定为 commonmark-java `0.30.1`（core + gfm-tables + gfm-strikethrough + task-list-items + autolink）与项目已有 Jsoup `1.18.1`。

## 4. Validation & Error Matrix

| 场景 | 行为 |
| --- | --- |
| `contentMd` 为空或全空白 | `requireVersion` 抛「当前版本正文为空，无法创建 BYD 推送任务」 |
| 清洗后既无文本也无图片 | `compileBody` 抛「正文内容为空，无法编译 BYD 王朝草稿」，且不插入 job |
| 清洗后只有图片 | 允许创建任务 |
| 图片 URL 为空 | `imageSource` 抛「图片未转存图床，无法交给本机 Agent 上传」 |
| HTML 含 `script` / `style` / `iframe` / `object` / `embed` / `noscript` / `template` | 整体删除，不进入正文 |

## 5. Good / Base / Bad Cases

- Good：`**粗体** [链接](https://example.com)` → `粗体 链接`
- Base：`![图](https://img.example/a.png)` → `ImageBlock("https://img.example/a.png")`，替代文字不进入正文
- Bad：在 Service 里用 `replace("**", "")` 之类正则做二次清洗，会漏掉表格、引用、删除线和链接目标

## 6. Tests Required

- `BydDynastyPlainTextRendererTest`：标题、段落、粗体、斜体、删除线、行内代码、代码块、链接、裸 URL、列表、引用、表格、HTML、emoji、图片顺序、空输入、任务列表。
- `BydDynastyPushServiceTest`：payload v1、标题清洗、图片顺序、封面第一条、空内容不落库、原始实体不变。
- 断言点：`text.content` 不含 `#` / `**` / `~~` / `|` / `<` / `https://`；`images` 与 `BODY-n` 一一对应。

## 7. Wrong vs Correct

Wrong：

```java
String markdown = contentMd.replaceAll("!\\[[^\\]]*]\\(([^)]+)\\)", "");
value = value.replace("**", "").replace("`", "");
```

Correct：

```java
for (BydDynastyPlainTextRenderer.Block block : BydDynastyPlainTextRenderer.compile(contentMd)) {
    if (block instanceof BydDynastyPlainTextRenderer.TextBlock text) {
        body.messages.add(Map.of("type", "text", "content", text.text()));
    } else if (block instanceof BydDynastyPlainTextRenderer.ImageBlock image) {
        // 分配 BODY-n 并登记 payload.images
    }
}
```
