package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.deep.search.WebProvider;
import com.sparkora.deep.search.WebProviderOrder;
import com.sparkora.deep.search.WebResultNormalizer;
import com.sparkora.deep.search.WebSearchOutcome;
import com.sparkora.deep.search.WebSearchRouter;
import com.sparkora.deep.search.WebSearchSnapshot;
import com.sparkora.deep.tool.KnowledgeSearchTool;
import com.sparkora.deep.tool.SearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SubAgentRunner 纯函数单测(09-25-brief-web-search R7/R9,AC-06/08/14):
 * WEB query 构造(主题+已锁定答案)、sourceId 后验校验、原始条目降级 JSON 转义。
 */
class SubAgentRunnerTest {

    private final SubAgentRunner runner = new SubAgentRunner(null, new ObjectMapper(), null, null);

    // ===== R7 / AC-06:WEB query 只含主题 + 已锁定答案 =====

    @Test
    void webQuery_含主题与问题与已锁定答案() {
        String locked = "[{\"q\":\"对比车型\",\"a\":\"Model Y\"},{\"q\":\"读者\",\"a\":\"家庭用户\"}]";
        String q = SubAgentRunner.webQuery("海狮08EV", "价格对比", locked);
        assertTrue(q.contains("海狮08EV"), "含主题");
        assertTrue(q.contains("价格对比"), "含问题");
        assertTrue(q.contains("Model Y"), "含已锁定答案");
        assertTrue(q.contains("家庭用户"), "含已锁定答案");
    }

    @Test
    void webQuery_未锁定答案不得进入() {
        // 未锁定(locked=null / 空数组):query 只含主题 + 问题,不得混入任何答案文本
        assertEquals("海狮08EV 价格", SubAgentRunner.webQuery("海狮08EV", "价格", null));
        assertEquals("海狮08EV 价格", SubAgentRunner.webQuery("海狮08EV", "价格", "[]"));
        // 空答案项(用户未作答)同样不得注入
        String q = SubAgentRunner.webQuery("海狮08EV", "价格", "[{\"q\":\"竞品\",\"a\":\"\"}]");
        assertEquals("海狮08EV 价格", q, "空答案项不得注入");
        // 非数组 JSON(未锁定/格式异常)退化,不得注入
        assertEquals("海狮08EV 价格", SubAgentRunner.webQuery("海狮08EV", "价格", "{}"));
    }

    @Test
    void webQuery_答案去重() {
        String locked = "[{\"q\":\"a\",\"a\":\"Model Y\"},{\"q\":\"b\",\"a\":\"Model Y\"}]";
        String q = SubAgentRunner.webQuery("海狮08", "对比", locked);
        assertEquals(1, count(q, "Model Y"));
    }

    @Test
    void webQuery_解析失败_退化为主题与问题() {
        String q = SubAgentRunner.webQuery("主题", "问题", "{not-json");
        assertEquals("主题 问题", q);
    }

    // ===== R9 / AC-08:sourceId 后验校验 =====

    private static WebResultNormalizer.WebHit hit(String id, String url, String provider) {
        return new WebResultNormalizer.WebHit(id, "title", url, "snippet", provider);
    }

    @Test
    void 合法sourceId事实_通过并回填权威url_provider() {
        String facts = "{\"facts\":[{\"claim\":\"价格23万\",\"value\":\"230000\","
                + "\"source\":{\"type\":\"WEB\",\"sourceId\":\"W1\",\"url\":\"https://x.com/a\"},\"confidence\":0.6}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertTrue(out.contains("价格23万"), "合法事实保留");
        assertTrue(out.contains("TAVILY"), "provider 回填");
    }

    @Test
    void 未知sourceId事实_转gap剔除() {
        String facts = "{\"facts\":[{\"claim\":\"编造事实\",\"source\":{\"type\":\"WEB\",\"sourceId\":\"W9\","
                + "\"url\":\"https://fake.com\"},\"confidence\":0.6}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertFalse(out.contains("\"claim\":\"编造事实\""), "未知 sourceId 不得进入事实手册");
        assertTrue(out.contains("已剔除无可靠来源"), "转 gap");
    }

    @Test
    void url与sourceId不匹配_拒绝() {
        String facts = "{\"facts\":[{\"claim\":\"错配URL\",\"source\":{\"type\":\"WEB\",\"sourceId\":\"W1\","
                + "\"url\":\"https://evil.com\"},\"confidence\":0.6}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertFalse(out.contains("\"claim\":\"错配URL\""));
        assertTrue(out.contains("URL 与 sourceId W1 不匹配"));
    }

    @Test
    void provider不匹配_拒绝() {
        String facts = "{\"facts\":[{\"claim\":\"错配provider\",\"source\":{\"type\":\"WEB\",\"sourceId\":\"W1\","
                + "\"url\":\"https://x.com/a\",\"provider\":\"SEARXNG\"},\"confidence\":0.6}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertFalse(out.contains("\"claim\":\"错配provider\""));
        assertTrue(out.contains("provider 与 sourceId W1 不匹配"));
    }

    @Test
    void 漏标type但带URL的事实_按WEB校验_自造URL被拒() {
        // 模型常漏标 source.type 却给出 URL;若放行,FactSheetService 会默认按 KB(0.9)采信其自造 URL。
        String facts = "{\"facts\":[{\"claim\":\"编造价格\",\"source\":{\"url\":\"https://evil.com/fake\"},\"confidence\":0.9}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertFalse(out.contains("evil.com"), "漏标 type 的自造 URL 不得进入事实手册");
        assertTrue(out.contains("已剔除无可靠来源"), "转 gap");
    }

    @Test
    void 误标KB但URL不匹配_被拒且不回填为KB() {
        String facts = "{\"facts\":[{\"claim\":\"冒名KB\",\"source\":{\"type\":\"KB\",\"sourceId\":\"W1\","
                + "\"url\":\"https://evil.com\"},\"confidence\":0.9}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertFalse(out.contains("\"claim\":\"冒名KB\""), "误标 KB 的错误 URL 事实不得进入 facts");
        assertTrue(out.contains("URL 与 sourceId W1 不匹配"));
    }

    @Test
    void 合法sourceId但漏标type_归一为WEB并回填权威URL() {
        String facts = "{\"facts\":[{\"claim\":\"时效价格\",\"source\":{\"sourceId\":\"W1\"},\"confidence\":0.6}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertTrue(out.contains("时效价格"), "可回溯源的事实保留");
        assertTrue(out.contains("\"type\":\"WEB\""), "type 归一为 WEB,避免 FactSheet 按 KB 采信");
        assertTrue(out.contains("https://x.com/a"), "回填权威 URL");
    }

    @Test
    void KB事实_不经sourceId校验原样保留() {
        String facts = "{\"facts\":[{\"claim\":\"车型数据\",\"source\":{\"type\":\"KB\",\"docId\":1},\"confidence\":0.9}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of());
        assertTrue(out.contains("车型数据"));
        assertTrue(out.contains("0.9"));
    }

    @Test
    void 既有gaps保留_新拒项追加() {
        String facts = "{\"facts\":[{\"claim\":\"x\",\"source\":{\"type\":\"WEB\",\"sourceId\":\"W9\"},\"confidence\":0.6}],"
                + "\"gaps\":[\"原缺口\"]}";
        String out = runner.validateFacts(facts, List.of());
        assertTrue(out.contains("原缺口"));
        assertTrue(out.contains("已剔除无可靠来源"));
    }

    @Test
    void rawFallback_特殊字符与换行_产出合法JSON() throws Exception {
        SearchTool.SearchHit h = new SearchTool.SearchHit("WEB", "含\"引号\"与\\反斜杠\n换行\t制表", "https://x.com/a",
                "s", "TAVILY", null, 0, "W1", "TAVILY");
        String raw = SubAgentRunner.rawFallback(List.of(h));
        // 必须能被 JSON 解析器接受(转义完整)
        new ObjectMapper().readTree(raw);
        assertEquals(1, new ObjectMapper().readTree(raw).path("facts").size());
        assertEquals("W1", new ObjectMapper().readTree(raw).path("facts").get(0).path("source").path("sourceId").asText());
    }

    // ===== R1/AC-01:降级保留搜索命中正文 snippet =====

    @Test
    void rawFallback_保留snippet正文() throws Exception {
        // 关键背景(年内2万座)常写在 snippet 而非 title;旧实现只取 title → 素材丢失
        SearchTool.SearchHit h = new SearchTool.SearchHit("WEB", "比亚迪第2000座闪充站落成", "https://x.com/a",
                "比亚迪计划2026年底前建成2万座闪充站,其中包含这2000座高速站", "TAVILY", null, 0, "W1", "TAVILY");
        String raw = SubAgentRunner.rawFallback(List.of(h));
        var fact = new ObjectMapper().readTree(raw).path("facts").get(0);
        assertEquals("比亚迪计划2026年底前建成2万座闪充站,其中包含这2000座高速站", fact.path("snippet").asText(),
                "降级事实必须保留 snippet 正文");
    }

    @Test
    void rawFallback_snippet含引号换行小数_JSON仍合法() throws Exception {
        SearchTool.SearchHit h = new SearchTool.SearchHit("WEB", "标题", "https://x.com/a",
                "售价 \"2.99万\" 起\n续航 12.5km\t含反斜杠\\", "TAVILY", null, 0, "W1", "TAVILY");
        String raw = SubAgentRunner.rawFallback(List.of(h));
        var fact = new ObjectMapper().readTree(raw).path("facts").get(0);
        assertEquals("售价 \"2.99万\" 起\n续航 12.5km\t含反斜杠\\", fact.path("snippet").asText(),
                "含引号/换行/小数/反斜杠的 snippet 必须转义完整且可解析");
    }

    // ===== R4/AC-04:LLM 汇总失败(截断/空/非法 JSON)先提额重试一次 =====

    @Test
    void chat_首次截断_提额重试成功_最终DONE() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        AiClient ai = mock(AiClient.class);
        String valid = "{\"facts\":[{\"claim\":\"事实\",\"source\":{\"type\":\"KB\"},\"confidence\":0.9}],\"gaps\":[]}";
        // 首次模拟 finish_reason=length 截断(AiClient 抛 AiException);第二次 4096 成功
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断", null))
                .thenReturn(new AiClient.ChatResult(valid, "m", 10));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, null);

        SubAgentRunner.Note note = r.research("问题", List.of(), 0, List.of(), "主题", null,
                WebSearchSnapshot.of(WebProviderOrder.defaults(), false, null, 0));

        assertEquals("DONE", note.status(), "截断后提额重试成功应回到 DONE");
        assertTrue(note.factsJson().contains("事实"));
        // 第二次必须用 4096 额度
        org.mockito.Mockito.verify(ai).chatJson(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(4096));
    }

    @Test
    void chat_两次均失败_落FALLBACK() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenThrow(new AiException("截断", null))
                .thenReturn(new AiClient.ChatResult("仍不是合法JSON", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, null);

        SubAgentRunner.Note note = r.research("问题", List.of(), 0, List.of(), "主题", null,
                WebSearchSnapshot.of(WebProviderOrder.defaults(), false, null, 0));

        assertEquals("FALLBACK", note.status(), "两次失败才允许降级");
    }

    // ===== R1/R2/R3(09-27-tavily-extract-kind-hypotheses):正文补抓 + 按问题类型分档注入 =====

    /** 背景题 + WEB 命中带正文 → LLM ctx 注入正文片段。 */
    @Test
    void 背景题_ctx注入正文片段() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        WebResultNormalizer.WebHit hit = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a",
                "snippet摘要", "TAVILY", "行业背景正文:比亚迪计划2026年底前建成2万座闪充站");
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("该车型的行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).chatJson(anyString(), user.capture(), anyInt());
        assertTrue(user.getValue().contains("正文片段:"), "背景题应注入正文片段行");
        assertTrue(user.getValue().contains("比亚迪计划2026年底前建成2万座闪充站"), "正文内容应进入 ctx");
    }

    /** 参数题 + WEB 命中带正文 → 不注入正文(仅摘要),即使命中携带 content。 */
    @Test
    void 参数题_ctx不注入正文() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        // KB 不给权威块(避免跳过 WEB),但问题为参数型
        WebResultNormalizer.WebHit hit = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a",
                "snippet摘要", "TAVILY", "正文片段不应被参数题注入");
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("海狮08的续航是多少?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).chatJson(anyString(), user.capture(), anyInt());
        assertFalse(user.getValue().contains("正文片段:"), "参数题不得注入正文");
        assertFalse(user.getValue().contains("正文片段不应被参数题注入"));
    }

    /** R1:背景题对 top URL 调 extract 补正文;参数题从不触发 extract。 */
    @Test
    void 背景题_触发extract补正文_参数题不触发() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        WebResultNormalizer.WebHit hit = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a", "s", "TAVILY");
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        when(router.extract(anyString(), any())).thenReturn(List.of(
                SearchTool.SearchHit.webContent("TAVILY", "https://x.com/a", "抽取到的正文")));
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        // 背景题 → extract 被调用一次,且抽取正文回填进 ctx
        r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);
        org.mockito.Mockito.verify(router, org.mockito.Mockito.times(1)).extract(anyString(), any());
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).chatJson(anyString(), user.capture(), anyInt());
        assertTrue(user.getValue().contains("抽取到的正文"), "extract 正文应回填并注入 ctx");

        // 参数题 → 不触发 extract
        org.mockito.Mockito.reset(router);
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        r.research("海狮08的续航是多少?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never()).extract(anyString(), any());
    }

    /** R2:rawFallback 携带非空正文 content 且 JSON 转义完整可解析。 */
    @Test
    void rawFallback_携带正文_JSON转义完整() throws Exception {
        SearchTool.SearchHit h = SearchTool.SearchHit.web("TAVILY", "标题", "https://x.com/a",
                "snippet", "正文 \"含引号\"\n换行\\反斜杠\t制表 2.5万");
        String raw = SubAgentRunner.rawFallback(List.of(h));
        var fact = new ObjectMapper().readTree(raw).path("facts").get(0);
        assertEquals("正文 \"含引号\"\n换行\\反斜杠\t制表 2.5万", fact.path("content").asText(),
                "content 必须转义完整且可解析");
    }

    /** R2:content 为空/null → 不出现 content 字段(旧契约零回归)。 */
    @Test
    void rawFallback_无正文_不含content字段() throws Exception {
        SearchTool.SearchHit h = new SearchTool.SearchHit("WEB", "标题", "https://x.com/a",
                "snippet", "TAVILY", null, 0, "W1", "TAVILY");
        var fact = new ObjectMapper().readTree(SubAgentRunner.rawFallback(List.of(h))).path("facts").get(0);
        assertFalse(fact.has("content"), "无正文时不得出现 content 字段");
    }

    /** R1:WEB 开关关闭(webAllowed=false)时既不搜索也不补抓正文(不回归)。 */
    @Test
    void WEB关闭_不搜索也不补抓正文() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 1L, 5);

        r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);

        org.mockito.Mockito.verify(router, org.mockito.Mockito.never()).search(anyString(), anyInt(), any());
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never()).extract(anyString(), any());
    }

    /**
     * R1/design §4「正文上限单点化」:子代理不得二次截断（配置上限 > 默认 2000 时不能被静默截回）。
     * 工具层是唯一截断点；此处传入超长 content，ctx 必须原样注入。
     */
    @Test
    void 正文上限单点化_子代理不二次截断() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        String longContent = "甲".repeat(2500);   // 超过旧硬编码兜底 2000，工具层未截（模拟配置上限调大）
        WebResultNormalizer.WebHit hit = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a",
                "摘要", "TAVILY", longContent);
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        when(router.extract(anyString(), any())).thenReturn(List.of());   // 不覆盖命中已带 content
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).chatJson(anyString(), user.capture(), anyInt());
        assertTrue(user.getValue().contains(longContent), "工具层未截断的正文不得被子代理二次截断（单点化）");
    }

    /** R2:rawFallback 同样不得二次截断工具层已定长的正文。 */
    @Test
    void rawFallback_不二次截断正文() throws Exception {
        String longContent = "乙".repeat(2500);
        SearchTool.SearchHit h = SearchTool.SearchHit.web("TAVILY", "标题", "https://x.com/a", "snippet", longContent);
        var fact = new ObjectMapper().readTree(SubAgentRunner.rawFallback(List.of(h))).path("facts").get(0);
        assertEquals(longContent, fact.path("content").asText(), "rawFallback 不得二次截断（单点化）");
    }

    /** R1:背景题 extract 返回空(抽取失败)→ 降级回摘要,不抛且 ctx 无正文行。 */
    @Test
    void 背景题_extract返回空_降级回摘要不抛() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        WebResultNormalizer.WebHit hit = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a", "摘要", "TAVILY");
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        when(router.extract(anyString(), any())).thenReturn(List.of());   // 抽取失败/空
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        SubAgentRunner.Note note = r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", snap);

        assertEquals("DONE", note.status(), "抽取失败不得影响研究状态");
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).chatJson(anyString(), user.capture(), anyInt());
        assertFalse(user.getValue().contains("正文片段:"), "无正文时应降级回摘要(不注入正文行)");
        assertTrue(user.getValue().contains("摘要"), "摘要仍应在 ctx");
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }

    // ===== R5/AC-05:webQuery 否定答案过滤(09-27-brief-writing-linkage-fix) =====

    @Test
    void webQuery_否定答案被过滤_正常答案保留() {
        String locked = "[{\"q\":\"对比竞品\",\"a\":\"不对比\"},{\"q\":\"读者\",\"a\":\"家庭用户\"}]";
        String q = SubAgentRunner.webQuery("海狮08EV", "价格对比", locked);
        assertFalse(q.contains("不对比"), "否定性答案不得进入 WEB query(制造噪声)");
        assertTrue(q.contains("家庭用户"), "正常答案仍应注入");
        assertTrue(q.contains("海狮08EV") && q.contains("价格对比"));
    }

    @Test
    void webQuery_各类否定值均被过滤() {
        for (String neg : new String[]{"无所谓", "都可以", "不限", "无偏好", "随便", "暂无", "不需要", "无", "跳过"}) {
            String locked = "[{\"q\":\"x\",\"a\":\"" + neg + "\"}]";
            String q = SubAgentRunner.webQuery("主题", "问题", locked);
            assertFalse(q.contains(neg), "否定值「" + neg + "」不得进入 query: " + q);
        }
    }

    @Test
    void isNegativeAnswer_前缀兜底与正例() {
        assertTrue(SubAgentRunner.isNegativeAnswer("不对比其他车型"));
        assertTrue(SubAgentRunner.isNegativeAnswer("不需要对比"));
        assertFalse(SubAgentRunner.isNegativeAnswer("Model Y"), "正常答案不得误判");
        assertFalse(SubAgentRunner.isNegativeAnswer(null));
        // 「无」精确匹配,但「无框车门」这类正常答案不得误伤(非精确/非前缀)
        assertFalse(SubAgentRunner.isNegativeAnswer("无框车门"));
    }

    // ===== R2/AC-02:kbAuthoritative 仅对参数型问题生效(09-27-brief-writing-linkage-fix) =====

    /** KB 命中车型域权威块(MODEL_INFO)。 */
    private static SearchTool.SearchHit modelInfoHit() {
        return SearchTool.SearchHit.kb("海狮08·MODEL_INFO", "海狮08", 1L, "价格区间 12.98-19.98 万", 0.9);
    }

    private static WebSearchOutcome oneHitOutcome() {
        WebResultNormalizer.WebHit h1 = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a", "s", "TAVILY");
        return new WebSearchOutcome(List.of(h1), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true)));
    }

    @Test
    void 背景题_KB命中权威块_仍调用WEB() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        when(kb.search(anyString(), anyInt(), any())).thenReturn(List.of(modelInfoHit()));
        WebSearchRouter router = mock(WebSearchRouter.class);
        when(router.search(anyString(), anyInt(), any())).thenReturn(oneHitOutcome());
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("该车型的行业背景与战略目标是什么?", List.of("KB", "WEB"), 2, List.of(), "海狮08", "[]", snap);

        org.mockito.Mockito.verify(router, org.mockito.Mockito.times(1))
                .search(anyString(), anyInt(), any());
    }

    @Test
    void 参数题_KB命中权威块_跳过WEB() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        when(kb.search(anyString(), anyInt(), any())).thenReturn(List.of(modelInfoHit()));
        WebSearchRouter router = mock(WebSearchRouter.class);
        AiClient ai = mock(AiClient.class);
        when(ai.chatJson(anyString(), anyString(), anyInt()))
                .thenReturn(new AiClient.ChatResult("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("海狮08的价格是多少?", List.of("KB", "WEB"), 2, List.of(), "海狮08", "[]", snap);

        org.mockito.Mockito.verify(router, org.mockito.Mockito.never())
                .search(anyString(), anyInt(), any());
    }

    // ===== R10/AC-11:LLM 汇总失败降级时,搜索结果数口径不归零 =====

    @Test
    void LLM降级_仍上报实际接受的WEB结果数与LLM_FALLBACK原因() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);   // KB 未装配(tools 无 KB)不会调用
        WebSearchRouter router = mock(WebSearchRouter.class);
        WebResultNormalizer.WebHit h1 = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a", "s", "TAVILY");
        WebResultNormalizer.WebHit h2 = new WebResultNormalizer.WebHit("W2", "t2", "https://x.com/b", "s", "TAVILY");
        WebSearchOutcome outcome = new WebSearchOutcome(List.of(h1, h2), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 2, 120L, null, true)));
        when(router.search(anyString(), anyInt(), any())).thenReturn(outcome);
        AiClient ai = mock(AiClient.class);
        // 两次 chatJson 都返回非法 JSON → 走 FALLBACK 原始条目降级
        when(ai.chatJson(anyString(), anyString(), anyInt())).thenReturn(new AiClient.ChatResult("不是JSON", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY,SEARXNG"), true, 7L, 5);

        SubAgentRunner.Note note = r.research("问题", List.of("WEB"), 2, List.of(), "主题", "[]", snap);

        assertEquals("FALLBACK", note.status());
        assertEquals(2, note.webCount(), "降级后仍应上报实际接受的 WEB 结果数");
        assertNotNull(note.search(), "保留搜索元数据");
        assertEquals(2, note.search().resultCount(), "search.resultCount 不得归零");
        assertEquals("LLM_FALLBACK", note.search().fallbackReason(), "LLM 降级原因应与 provider 尝试区分");
        assertEquals("TAVILY", note.search().provider());
        // 原始条目仍带可溯源 sourceId
        assertTrue(note.factsJson().contains("W1"));
    }
}
