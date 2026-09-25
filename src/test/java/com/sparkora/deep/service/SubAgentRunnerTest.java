package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.deep.search.WebResultNormalizer;
import com.sparkora.deep.tool.SearchTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }
}
