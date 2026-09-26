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

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
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
