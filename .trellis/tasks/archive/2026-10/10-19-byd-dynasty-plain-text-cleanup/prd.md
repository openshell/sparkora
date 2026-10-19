# BYD 王朝小程序草稿纯文本清洗

## Goal

在创建比亚迪王朝小程序推送任务时，把文章 Markdown 安全编译成小程序可接受的纯文本消息和独立图片消息。清洗只影响新生成的推送 payload，不修改文章原文、已有草稿或本机 Agent 协议。

## Background

A 阶段只读检查确认，当前账号的 MMKV 草稿消息类型为 `text`、`img` 和 `video`，现有 `msg_style` 只出现媒体关联字段 `sibling`。文本块支持换行和 emoji，没有发现富文本或排版字段；“MMKV本地测试”草稿证明图片和视频是独立内容块。

当前 `BydDynastyPushService` 使用正则拆分 Markdown 图片，`normalizeText` 只删除标题符号、`**`、反引号和部分空行，因此斜体、删除线、链接、列表、引用、表格、代码围栏和 HTML 仍可能作为字面量进入草稿。

已批准采用 commonmark-java 作为 Markdown 解析和基础文本渲染器，并结合项目已有 Jsoup 处理 HTML。commonmark-java 当前版本为 `0.30.1`，许可证为 BSD-2-Clause；其内置 `TextContentRenderer` 和 GFM 扩展可覆盖段落、列表、表格、删除线等解析工作。

## Requirements

### R1 - 使用成熟开源组件解析 Markdown

- 新增 `org.commonmark:commonmark:0.30.1`。
- 新增 `commonmark-ext-gfm-tables`、`commonmark-ext-gfm-strikethrough`、`commonmark-ext-task-list-items` 和 `commonmark-ext-autolink`，版本同为 `0.30.1`。
- 复用项目已有 Jsoup 处理 HTML 文本，不新增 HTML 工具。
- 不新增或扩展自研 Markdown 解析器，不使用大规模正则替换作为主解析方案。

### R2 - 在服务端生成 payload 前统一清洗

- 清洗发生在 `BydDynastyPushService` 创建任务、编译 payload 的阶段。
- 原始 `ArticleVersionEntity.contentMd` 和 `title` 不得被修改。
- 本地 Agent 继续只解析 `text`、`img` 消息并写入 MMKV，不承担 Markdown 清洗。
- payload 结构保持现有 `payloadVersion = 1`，避免要求本机 Agent 同步升级。

### R3 - 文本必须转换为可读纯文本

- 标题、段落、粗体、斜体、删除线、行内代码、代码块和引用均只保留可读文字。
- Markdown 链接只保留链接文字；裸 URL 和链接目标地址不进入正文。
- 无序列表使用 `•`，有序列表保留编号，嵌套列表保留层级。
- 图片语法转换为独立 `img` 消息，替代文字不混入正文。
- GFM 表格转换为逐行文本：`列名：值；列名：值`。
- `<br>` 转换为换行；HTML 块标签转换为换行；其余标签剥离，HTML 实体解码。
- `script`、`style`、`iframe`、`object`、`embed` 等危险或不可见 HTML 节点不得进入正文。
- 中文、英文、数字、标点、代码内容和 emoji 必须保留。
- 规范化 CRLF、行尾空白和连续空行；正文块之间最多保留一个空行。
- 清理后没有文字但有图片的任务仍可推送；清理后完全没有内容的正文不得创建任务，并返回明确中文错误。

### R4 - 标题使用行内清洗

- 文章标题去除 Markdown 标记、HTML 标签、换行、裸露 URL 和多余空白。
- 标题仍写入 payload 的 `title` 字段，不拼接到正文消息。

### R5 - 保持现有图片和封面协议

- 正文图片按照 Markdown 中出现顺序生成 `BODY-n` 引用。
- 封面图继续使用 `COVER-{imageId}`，存在封面图时仍插入 payload 的第一条消息。
- 图片源 URL、图片上传和 Agent 的 `img` 消息结构保持不变。
- 不新增视频推送能力；A 阶段发现的 `video` 类型不在本次范围。

### R6 - 测试必须覆盖转换规则和跨层契约

- 为纯文本渲染器增加独立单元测试矩阵。
- 更新 `BydDynastyPushServiceTest`，验证 payload 不再包含 Markdown/HTML，图片顺序和封面行为不变。
- 废除与新方案冲突的旧正则清洗断言，不保留两套并行业务规则。

## Out of Scope

- 不进行 C 阶段真实草稿渲染测试。
- 不修改现有 MMKV、已有草稿或文章数据库内容。
- 不实现富文本、字体、颜色、粗细等辅助能力。
- 不实现视频、音频或地理位置消息推送。
- 不修改本机 Agent 的 MMKV 写入逻辑。
- 不改变前端发布页接口和推送任务状态机。

## Acceptance Criteria

| ID | 可观察结果 |
| --- | --- |
| AC1 | 使用标题、粗体、斜体、删除线、行内代码和代码块的示例生成 payload，所有 `text.content` 不含对应 Markdown 标记。 |
| AC2 | `[文字](url)` 只输出 `文字`；裸 URL 不输出；正文中不泄露链接目标。 |
| AC3 | 无序列表输出 `• `，有序列表保留编号，嵌套列表保留层级；引用不输出 `>`。 |
| AC4 | GFM 表格不输出管道符和分隔行，转为 `列名：值；列名：值` 的可读文本。 |
| AC5 | `<br>` 和块级 HTML 产生换行，普通 HTML 标签被剥离，实体被解码，危险节点被移除。 |
| AC6 | emoji、中文标点和代码内容原样保留，连续空行被规范为一个空行。 |
| AC7 | 正文图片仍按顺序生成 `BODY-n` 和管理员可见的 `images` 项，图片 URL 不出现在文本消息中。 |
| AC8 | 封面图仍以 `COVER-{imageId}` 插入第一条消息，现有封面行为不回归。 |
| AC9 | 标题经过行内清洗后写入 `payload.title`，不包含换行、Markdown 或 HTML。 |
| AC10 | 清洗后没有文本且没有图片时，创建任务抛出中文错误；只有图片时可以创建 payload。 |
| AC11 | 原始版本对象、数据库实体和已有 MMKV 内容未被清洗逻辑修改。 |
| AC12 | 聚焦测试和全量后端测试通过，本地 Agent 现有测试不需要因协议变更而修改。 |

## Open Questions

无。技术选型、链接策略、清洗范围、兼容边界和验收方式已通过用户确认。