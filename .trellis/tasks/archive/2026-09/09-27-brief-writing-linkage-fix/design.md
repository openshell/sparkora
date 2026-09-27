# 简报到写作断链修复与背景题检索补齐 — 技术设计

> 5 项均不改 schema、不改 `/deep/*` 响应主结构、不改 `research_notes` 字段集。基线测试数：**424**（PRD 的 308 为写作时旧值）。

## 0. 涉及文件

| 文件 | 改动 |
|---|---|
| `deep/service/DeepWriterService.java` | R1 注入简报字段；R4 提额重试 |
| `deep/service/SubAgentRunner.java` | R2 kbAuthoritative 限定参数型；R5 否定答案过滤 |
| `deep/service/ClarifyService.java` | 新增包级静态 `isBackgroundQuestion`（复用既有词表） |
| `deep/service/DeepResearchService.java` | R3 研究窗口保背景题（`run` + `doRunAsync` 共用选择器） |
| `ai/AiClient.java` | `ChatResult` 增 `finishReason`（向后兼容构造器）+ parseChat 始终捕获 |

## 1. R1 写作消费简报产物

在 `write()` 构造 user prompt 处（现 `DeepWriterService.java:223-227`），`fact_sheet`/`clarify_answers` 之后、末尾指引句之前，追加简报字段块：

```java
appendBriefSection(user, "标题候选", b.getTitleCandidates(), true);
appendBriefSection(user, "核心观点", b.getCoreViewpoints(), true);
appendBriefSection(user, "大纲",     b.getOutline(),        false);
appendBriefSection(user, "事实风险", b.getFactRisks(),      true);
user.append("主题与大纲参考 brief(...),直接写正文 Markdown。");
```

`appendBriefSection(sb,label,json,asArray)` 规则：
- 空/null/`"[]"`/`"{}"` → 跳过（**历史 brief 兼容**：无字段时 prompt 与旧行为等价）。
- `asArray=true`：`json.readTree` 为数组则逐项 `- ` 列出（元素为对象时 `toString`）；解析失败或非数组 → 原样追加。
- `asArray=false`（outline）：解析成功 → `toString` 追加（结构未知，不强解）；失败 → 原样。
- 全程 try/catch，异常仅 `log.warn` 不阻断（兼容降级）。

## 2. R2 kbAuthoritative 仅对参数型问题生效

现状（`SubAgentRunner.java:98-101`）：`kbAuthoritative = 命中( title 含 MODEL_INFO 或 snippet 含「价格区间」)`，随后 `!kbAuthoritative` 才走 WEB。

改为：

```java
boolean kbParamAuthoritative = !ClarifyService.isBackgroundQuestion(question)
        && hits.stream().anyMatch(h -> "KB".equals(h.type())
            && (h.title()!=null && h.title().contains("MODEL_INFO")
                || (h.snippet()!=null && h.snippet().contains("价格区间"))));
if (toolsAllowed.contains("WEB") && ... && !kbParamAuthoritative) { ... }
```

**背景型问题永不因 KB 命中 MODEL_INFO 而跳过 WEB**（KB 是车型库，不含行业战略背景）。

`ClarifyService` 新增包级静态（`SubAgentRunner`/`DeepResearchService` 同包可直接调用）：

```java
/** 背景/来龙去脉型问题判定：命中 BACKGROUND_TERMS ∪ BACKGROUND_SIGNALS 任一。 */
static boolean isBackgroundQuestion(String question) {
    if (question == null || question.isBlank()) return false;
    return Arrays.stream(BACKGROUND_TERMS).anyMatch(question::contains)
        || Arrays.stream(BACKGROUND_SIGNALS).anyMatch(question::contains);
}
```
> 设计取舍：PRD 写「复用 BACKGROUND_SIGNALS」，但兜底背景题文案含「背景/战略/目标」等 **BACKGROUND_TERMS** 词（同类的既有词表）；取并集才能同时覆盖 LLM 生成题与兜底题。两词表均属 R2 既有信号体系，不新增词汇。

## 3. R3 研究窗口保背景题

`run()`（`:119`）与 `doRunAsync()`（`:205`）各自 `n = min(questions.size(), maxAgents)` 且按 `questions.get(i)`/`toolHints` 同索引取用。抽出**唯一**私有无副作用选择器，两处共用，保证「问题 ↔ toolHints」索引对齐：

```java
/** 在 maxAgents 预算内优先保背景型问题，其余按原序补足；返回按原序稳定的索引列表。 */
static List<Integer> selectResearchWindow(List<String> questions, int maxAgents) {
    int n = Math.min(questions.size(), maxAgents);
    List<Integer> bg = new ArrayList<>();
    for (int i = 0; i < questions.size(); i++) if (ClarifyService.isBackgroundQuestion(questions.get(i))) bg.add(i);
    List<Integer> idx = new ArrayList<>();
    int remaining = n - bg.size();
    if (remaining <= 0) {
        idx.addAll(bg.subList(0, Math.min(n, bg.size())));   // 背景题多于预算：仍取原序前 n 条背景题
    } else {
        idx.addAll(bg);
        for (int i = 0; i < questions.size() && remaining > 0; i++) {
            if (!ClarifyService.isBackgroundQuestion(questions.get(i))) { idx.add(i); remaining--; }
        }
    }
    Collections.sort(idx);   // 归位原序：agent 列表顺序自然、与 toolHints 索引一致
    return idx;
}
```

调用方：`n = idx.size(); questions = questions.get(idx.get(k)); toolHints = hints.get(idx.get(k))`。

`maxAgents` 保持默认 6（见 §7 Q1 决策）。`research_notes`/agent 结构不变；仅「哪些问题进入研究窗口」改变。

## 4. R4 写作提额重试

### 4.1 AiClient 暴露 finishReason（向后兼容）

`ChatResult` 增第 4 分量为 `finishReason`，并保留 3 参构造器（默认 null）——**9 处既有测试 `new ChatResult(content,model,tokens)` 编译不受影响**：

```java
public record ChatResult(String content, String model, int totalTokens, String finishReason) {
    public ChatResult(String content, String model, int totalTokens) {
        this(content, model, totalTokens, null);
    }
}
```
`parseChat`（`:184-191`）始终读出 `choices.get(0).path("finish_reason").asText(null)` 传入。

### 4.2 write 提额重试

```java
AiClient.ChatResult cr;
try {
    cr = aiClient.chat(system, user.toString(), 4096);
    if ("length".equals(cr.finishReason()))
        throw new AiException("AI 输出被 max_tokens 截断（正文）", null);
} catch (Exception first) {
    log.warn("深度写作首次失败,提额重试(8192): {}", first.getMessage());
    cr = aiClient.chat(system, user.toString() + "\n注意:上次输出被截断,请输出完整正文。", 8192);
    if ("length".equals(cr.finishReason()))
        throw new AiException("AI 输出两次均被 max_tokens 截断（正文）", first);
}
String content = cr.content();
```
- 首次成功（含 finishReason≠length）不重试（AC-04）。
- 两次均失败/截断 → 抛 `AiException` → `runBatch` catch 计入该版本失败（`failVersionsToReady`/partial）——与既有失败语义一致，无需改 runBatch。
- 重试仅包裹 AI 调用，**版本 insert 仍只执行一次**（无重复落库）。

## 5. R5 webQuery 否定答案过滤

`SubAgentRunner` 新增：

```java
/** 语义为「放弃/无偏好」的否定性答案值：不得进入 WEB query（制造噪声）。 */
private static final List<String> NEGATIVE_ANSWER_VALUES =
        List.of("不对比","不比较","无所谓","都可以","都行","不限","无偏好","随便","暂无","不需要","无","没有","不涉及","跳过");
static boolean isNegativeAnswer(String a) {
    if (a == null) return false;
    String s = a.trim();
    return NEGATIVE_ANSWER_VALUES.stream().anyMatch(s::equals) || s.startsWith("不对比") || s.startsWith("不需要");
}
```
`webQuery`（`:192-194`）注入循环加 `if (isNegativeAnswer(a)) continue;`。正常答案不变。注意 `lockedAnswerValues` 仍返回全部（仅供 webQuery 使用，语义收敛在过滤处）。

## 6. 测试设计

| 用例 | 断言 |
|---|---|
| `DeepWriterServicePromptTest`（新）| prompt 含标题候选/核心观点/大纲/风险正文；空字段 brief 不抛异常且不含对应块（R1/AC-01）|
| `DeepWriterServiceRetryTest`（新）| 首次 `finishReason="length"` → 断言 `chat` 第二次 maxTokens=8192 且落版本；首次成功仅调 1 次；两次截断 → 抛（R4/AC-04）|
| `SubAgentRunnerTest`（扩展）| 背景题 + KB 命中 MODEL_INFO → `webRouter.search` 被调用；参数题同条件 → 不调用（R2/AC-02）|
| `SubAgentRunnerTest`（扩展）| `webQuery` 含「不对比」答案 → 结果不含；正常答案仍注入（R5/AC-05）|
| `DeepResearchServiceWindowTest`（新）| 8 题（7 LLM + 1 背景兜底）maxAgents=6 → 窗口含背景题；背景题多于预算 → 仍按原序取背景；toolHints 索引对齐（R3/AC-03）|
| `ClarifyServiceTest`（扩展）| `isBackgroundQuestion` 正/负例 |

既有 `DeepWriterServiceBatchTest` 需回归（ChatResult 3 参构造兼容 → 不改）。

## 7. 决策与开放项

- **Q1（R3 预算）已定**：**Option A——maxAgents 保持 6，优先保背景题**（用户 2026-09-27 确认）。零额外成本；被牺牲的是最不关键的 1~2 条尾部参数题的可能。
- **R2 词表并集**（BACKGROUND_TERMS ∪ BACKGROUND_SIGNALS）：见 §2 取舍说明。
- **AiClient 改动**：为满足 AC-04 的「截断」语义（纯文本截断不抛异常），这是必要的最小扩展，且向后兼容。

## 8. 回滚

纯代码回退；无 schema、无数据迁移。`maxAgents` 若选 B 改回配置即可。
