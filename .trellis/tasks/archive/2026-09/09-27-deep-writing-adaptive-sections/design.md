# 设计：深度写作注入目标字数 + 自适应小标题分节

## 1. 边界与改动面

| 文件 | 改动 |
|---|---|
| `deep/service/DeepWriterService.java` | ① `write` 读 `p.getWordCountTarget()` 注入 user prompt；② 新增纯静态分档函数 + 动态排版铁律文案（替代写死「2~4」） |
| `src/test/java/com/sparkora/deep/service/DeepWriterServicePromptTest.java` | 重构「无 kind → system prompt 逐字等价」断言；新增目标字数注入与分档断言 |
| 新增测试（或并入上文件） | 分档纯函数边界单测 |
| `docs/spec/brief-generation.md` | §6 深度写作契约补「目标字数注入 + 自适应分节」 |

无 schema / Flyway / 前端 / 配置项变更。

## 2. 分档纯函数（确定性、可单测）

```java
/** 分节档位:小标题数区间 + 每节段数区间(纯字符串,直接进 prompt)。 */
record SectionSpec(String headings, String parasPerSection) {}

private static SectionSpec sectionSpec(Integer targetWords) {
    int n = (targetWords == null || targetWords <= 0) ? 1500 : targetWords;
    if (n <= 800)  return new SectionSpec("2~3",  "2~3");
    if (n <= 1800) return new SectionSpec("3~5",  "2~3");
    if (n <= 3000) return new SectionSpec("5~8",  "2~3");
    return new SectionSpec("8~12", "2~4");
}

/** 排版铁律第二行(节数/段数随目标字数自适应):其余铁律保留不变。 */
private static String layoutRule(Integer targetWords) {
    SectionSpec s = sectionSpec(targetWords);
    return "排版铁律(公众号正文可读性,必须遵守):全文用 " + s.headings()
         + " 个「## 小标题」分节,每节 " + s.parasPerSection()
         + " 段,禁止整篇无分节;";
}
```

- **静态纯函数**：无 Spring 依赖、无条件副作用，测试可直接调用（也可经 `write` 捕获 system prompt 断言）。
- **边界语义**：`null`/`≤0` 归 1500 档；`800`→2~3；`801`→3~5；`1800`→3~5；`1801`→5~8；`3000`→5~8；`3001`→8~12；`10000`→8~12。
- 常量集中在此两个方法，分档表若要调，只改一处。

## 3. system prompt 改造

现状（`DeepWriterService.java:200-212`）为「铁律 1/2/3 + （hasKind 时）铁律 4 + 排版铁律两行」。改造点仅**排版铁律第一行**（节数行）动态化，其余逐字保留：

```java
String system = """
        你是资深汽车内容作者。基于【事实手册】与用户锁定需求撰写文章正文。
        铁律:
        1. …（不变）
        2. …（不变）
        3. 结构清晰,用 Markdown;长度按用户需求。
        """ + (hasKind ? """
        4. …（不变）
        """ : "") + layoutRule(p.getWordCountTarget()) + """
        关键数据、核心结论用 **加粗** 突出,每节至少一处;单段不超过 5 行,长段拆分。
        """;
```

- `wordCountTarget` 经 `p`（`write` 已查 project 吗？——**注意**：`write` 当前不查 `projectMapper` 取 project；`extractH1` 内部才查）。实现需在 `write` 开头 `projectMapper.selectById(projectId)` 取一次（可复用给 `extractH1`，或各自取；倾向取一次传参，减少一次查询）。

> **实现提示**：`extractH1(projectId, content)` 内部自带 `selectById` 且吞异常。可改为在 `write` 中取一次 `ArticleProjectEntity p`（判空回退），把 topic/wordCountTarget 一起用；`extractH1` 保持签名或改收 topic 字符串。二选一，实现者定夺，但**不得新增查询放大**。

## 4. user prompt 注入

在 `write` 的 user 拼接中（`DeepWriterService.java:217` 起），事实手册之前或「用户锁定需求」之后追加：

```java
int target = (p.getWordCountTarget() == null || p.getWordCountTarget() <= 0) ? 1500 : p.getWordCountTarget();
user.insert(0, "目标字数:" + target + "\n");   // 或 append 到合适位置
```

- 口径与 `VersionService.java:298` 的 `目标字数：%s` 一致（null→1500）。
- 位置建议放在 user 开头（主题之前）或紧跟「用户锁定需求」，确保模型先见长度约束。

## 5. 测试重构（R4/AC-04）

`DeepWriterServicePromptTest:198-213` 当前用整段 `legacy` 字符串 `assertEquals`。改造：

```java
@Test
void 全无kind_systemPrompt不含分组铁律() throws Exception {
    when(briefMapper.selectById(BRIEF_ID)).thenReturn(brief());
    service.write(PROJECT_ID, BRIEF_ID, "", "深度");
    ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
    verify(aiClient).chat(system.capture(), anyString(), eq(4096));
    assertFalse(system.getValue().contains("参数事实"), "无 kind 不得出现分组铁律");
    assertFalse(system.getValue().contains("背景素材"), "无 kind 不得出现分组铁律");
}
```

新增断言：注入字数（AC-01）、默认档 `3~5`（AC-02）、>3000 档 `8~12`/`2~4`（AC-02）、纯函数边界表（AC-03）。

## 6. 兼容与回滚

- 无数据/schema 变更，纯 prompt 文案与一处 DB 读取（project 复用）。
- 回滚：还原 `DeepWriterService` 与测试即可。
- 历史 brief 不受影响（分档只依赖项目 `wordCountTarget`）。

## 7. 风险

| 风险 | 缓解 |
|---|---|
| 分档与实际输出长度不匹配（模型不完全守） | 只做区间引导，不做硬校验；后续任务可加覆盖自检 |
| 多一次 project 查询 | 复用同一快照供 `extractH1`，不放大查询 |
| 既有逐字断言被误改导致契约丢失 | 重构为「不含分组铁律」实质断言，保留 kind 相关全部用例 |
| 仿写链路文案不一致 | 明列 Out of Scope，避免范围蔓延 |
