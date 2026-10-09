package com.sparkora.deep.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.SubAgentFactsDto;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SubAgentRunner 纯函数单测(09-25-brief-web-search R7/R9,AC-06/08/14):
 * WEB query 构造(主题+已锁定答案)、sourceId 后验校验、原始条目降级 JSON 转义。
 */
class SubAgentRunnerTest {

    private final SubAgentRunner runner = new SubAgentRunner(null, new ObjectMapper(), null, null);

    /** C2:把原始 facts JSON 转成 structured 的 TypedResult(测试桩共用)。 */
    private static AiClient.TypedResult<SubAgentFactsDto> typed(String raw, String model, int tokens) throws Exception {
        SubAgentFactsDto dto = new ObjectMapper().readValue(raw, SubAgentFactsDto.class);
        return new AiClient.TypedResult<>(dto, new AiClient.ChatResult(raw, model, tokens));
    }

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

    // ===== 10-05-source-web-fusion F-R1/F-R8:SOURCE 白名单扩展与核验 =====

    /** SOURCE 无 url 且无 sourceId(本地信源命中)→ 直接接受(同 KB 路径,不被白名单拒绝)。 */
    @Test
    void SOURCE事实_无url无sourceId_直接接受() {
        String facts = "{\"facts\":[{\"claim\":\"工信部公示新车\",\"source\":{\"type\":\"SOURCE\","
                + "\"sourceType\":\"user-source\",\"docId\":7},\"confidence\":0.7}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of());
        assertTrue(out.contains("工信部公示新车"), "SOURCE 事实不得被白名单拒绝");
        assertFalse(out.contains("已剔除无可靠来源"), "不得转 gap");
        assertTrue(out.contains("\"type\":\"SOURCE\""), "type 仍为 SOURCE(不归一为 KB)");
    }

    /** SOURCE 带自造 URL / 未知 sourceId → 按 WEB 严格核验拒绝(杜绝借 SOURCE 绕校验)。 */
    @Test
    void SOURCE带自造URL_按WEB校验拒绝() {
        String facts = "{\"facts\":[{\"claim\":\"编造SOURCE\",\"source\":{\"type\":\"SOURCE\","
                + "\"sourceId\":\"W9\",\"url\":\"https://fake.com\"},\"confidence\":0.9}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertFalse(out.contains("\"claim\":\"编造SOURCE\""), "自造 URL 的 SOURCE 不得进入 facts");
        assertTrue(out.contains("已剔除无可靠来源"), "转 gap");
    }

    /** SOURCE 带合法 sourceId + URL → 通过并归一为 WEB(回填权威 URL)。 */
    @Test
    void SOURCE带合法sourceId_归一为WEB() {
        String facts = "{\"facts\":[{\"claim\":\"可溯源SOURCE\",\"source\":{\"type\":\"SOURCE\","
                + "\"sourceId\":\"W1\",\"url\":\"https://x.com/a\"},\"confidence\":0.9}],\"gaps\":[]}";
        String out = runner.validateFacts(facts, List.of(hit("W1", "https://x.com/a", "TAVILY")));
        assertTrue(out.contains("可溯源SOURCE"));
        assertTrue(out.contains("\"type\":\"WEB\""), "带 URL 的 SOURCE 归一为 WEB,避免按本地权威采信");
        assertTrue(out.contains("TAVILY"), "provider 回填");
    }

    /** rawFallback 透传 SOURCE 的 sourceType/authorityTier/crossCounted 供降级路径融合(F-R8)。 */
    @Test
    void rawFallback_透传SOURCE字段() throws Exception {
        SearchTool.SearchHit h = SearchTool.SearchHit.source("工信部公示", "信源", 7L, "snippet", 0.7,
                "gasgoo-ranking", "industry", Boolean.FALSE);
        var fact = new ObjectMapper().readTree(SubAgentRunner.rawFallback(List.of(h))).path("facts").get(0);
        assertEquals("SOURCE", fact.path("source").path("type").asText());
        assertEquals("gasgoo-ranking", fact.path("source").path("sourceType").asText());
        assertEquals("industry", fact.path("source").path("authorityTier").asText());
        assertFalse(fact.path("source").path("crossCounted").asBoolean(true), "crossCounted 应透传 false");
    }

    /**
     * F-R4/F-R8 主路径:本地信源(SOURCE)命中经 KB 工具注入研究 ctx 时,须把
     * sourceType/authorityTier/crossCounted 一并透出,供 LLM 回填到 fact.source——
     * 这是除 rawFallback 降级外的唯一通路。KB/WEB 命中不得出现该元数据(零回归)。
     */
    @Test
    void SOURCE命中_ctx透出来源元数据供LLM回填() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        when(kb.search(anyString(), anyInt(), any())).thenReturn(List.of(
                SearchTool.SearchHit.source("盖世排行", "信源", 9L, "排行摘要", 0.7,
                        "gasgoo-ranking", "industry", Boolean.FALSE),
                SearchTool.SearchHit.kb("车型数据", "海狮08", 1L, "kb 摘要", 0.9)));
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, null);

        r.research("问题", List.of("KB"), 0, List.of(), "海狮08", null, null,
                WebSearchSnapshot.of(WebProviderOrder.defaults(), false, null, 0));

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
        String ctx = user.getValue();
        assertTrue(ctx.contains("sourceType=gasgoo-ranking"), "SOURCE 命中须透出 sourceType 供回填");
        assertTrue(ctx.contains("authorityTier=industry"), "SOURCE 命中须透出 authorityTier 供回填");
        assertTrue(ctx.contains("crossCounted=false"), "SOURCE 命中须透出 crossCounted 供回填");
        // 仅 SOURCE 行带元数据:KB 行不追加(零回归)
        assertEquals(1, count(ctx, "sourceType="), "仅 SOURCE 命中透出元数据");
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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenThrow(new AiException("AI 输出被 max_tokens 截断", null))
                .thenReturn(typed(valid, "m", 10));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, null);

        SubAgentRunner.Note note = r.research("问题", List.of(), 0, List.of(), "主题", null, null,
                WebSearchSnapshot.of(WebProviderOrder.defaults(), false, null, 0));

        assertEquals("DONE", note.status(), "截断后提额重试成功应回到 DONE");
        assertTrue(note.factsJson().contains("事实"));
        // 第二次必须用 4096 额度
        org.mockito.Mockito.verify(ai).structured(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(4096), eq(SubAgentFactsDto.class));
    }

    @Test
    void chat_两次均失败_落FALLBACK() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenThrow(new AiException("截断", null))
                .thenThrow(new AiException("子代理汇总输出仍非法", null));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, null);

        SubAgentRunner.Note note = r.research("问题", List.of(), 0, List.of(), "主题", null, null,
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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("该车型的行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("海狮08的续航是多少?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        // 背景题 → extract 被调用一次,且抽取正文回填进 ctx
        r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);
        org.mockito.Mockito.verify(router, org.mockito.Mockito.times(1)).extract(anyString(), any());
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
        assertTrue(user.getValue().contains("抽取到的正文"), "extract 正文应回填并注入 ctx");

        // 参数题 → 不触发 extract
        org.mockito.Mockito.reset(router);
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        r.research("海狮08的续航是多少?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);
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

    // ===== 10-04-web-followup-budget C-R2:Round 2 补检索强制 PRIMARY_FANOUT =====

    /** Round 2 补检索固定走多源交叉:即使部署级快照为 FIRST_HIT,也以 PRIMARY_FANOUT 调用路由。 */
    @Test
    void Round2补检索_强制PRIMARY_FANOUT() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        WebResultNormalizer.WebHit hit = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a", "s", "TAVILY");
        when(router.search(anyString(), anyInt(), any(), any())).thenReturn(new WebSearchOutcome(
                List.of(hit), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        // 部署级快照为默认 FIRST_HIT(与生产默认一致)
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.researchFollowup("价格 23 万", "主题 价格 23 万", List.of(), "海狮08", null, snap, null);

        org.mockito.ArgumentCaptor<WebSearchSnapshot> cap =
                org.mockito.ArgumentCaptor.forClass(WebSearchSnapshot.class);
        org.mockito.Mockito.verify(router).search(anyString(), anyInt(), cap.capture(), any());
        assertEquals(com.sparkora.deep.search.SearchStrategy.PRIMARY_FANOUT, cap.getValue().strategy(),
                "Round 2 补检索必须强制 PRIMARY_FANOUT(单源无交叉价值)");
    }

    /** webAllowed=false → Round 2 不发起任何搜索。 */
    @Test
    void Round2补检索_webAllowed关闭_不发起() {
        WebSearchRouter router = mock(WebSearchRouter.class);
        SubAgentRunner r = new SubAgentRunner(null, new ObjectMapper(), null, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 1L, 5);
        assertEquals(null, r.researchFollowup("claim", "q", List.of(), "主题", null, snap, null));
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never())
                .search(anyString(), anyInt(), any(), any());
    }

    /** R1:WEB 开关关闭(webAllowed=false)时既不搜索也不补抓正文(不回归)。 */
    @Test
    void WEB关闭_不搜索也不补抓正文() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), false, 1L, 5);

        r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class), router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        SubAgentRunner.Note note = r.research("行业背景与战略目标是什么?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        assertEquals("DONE", note.status(), "抽取失败不得影响研究状态");
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
        assertFalse(user.getValue().contains("正文片段:"), "无正文时应降级回摘要(不注入正文行)");
        assertTrue(user.getValue().contains("摘要"), "摘要仍应在 ctx");
    }

    private static int count(String s, String sub) {
        int c = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) { c++; i += sub.length(); }
        return c;
    }

    // ===== 10-02-brief-reasoning-maxtokens R4b/AC9(Q4=A):内容描述只进汇总上下文,不改检索 query =====

    @Test
    void 内容描述_进汇总上下文_但不进KB与WEB检索query() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        WebSearchRouter router = mock(WebSearchRouter.class);
        when(router.search(anyString(), anyInt(), any())).thenReturn(new WebSearchOutcome(
                List.of(new WebResultNormalizer.WebHit("W1", "t", "https://x.com/a", "s", "TAVILY")),
                WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true))));
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("销量如何?", List.of("KB", "WEB"), 2, List.of(), "海狮08", "[]",
                "围绕第2000座闪充站落成写一篇", snap);

        // 汇总上下文必须注入内容描述(写作意图)
        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
        assertTrue(user.getValue().contains("写作意图/内容描述:围绕第2000座闪充站落成写一篇"),
                "内容描述应进 LLM 汇总上下文");

        // KB 复合 query / WEB query 不得含内容描述(检索语料纯净,Q4=A)
        org.mockito.ArgumentCaptor<String> kbQuery = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(kb).search(kbQuery.capture(), anyInt(), any());
        assertFalse(kbQuery.getValue().contains("第2000座"), "KB 复合 query 不得注入内容描述");
        org.mockito.ArgumentCaptor<String> webQuery = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(router).search(webQuery.capture(), anyInt(), any());
        assertFalse(webQuery.getValue().contains("第2000座"), "WEB query 不得注入内容描述");
    }

    @Test
    void 内容描述为空_ctx不出现写作意图行() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, null);

        r.research("问题", List.of(), 0, List.of(), "主题", null, "  ",
                WebSearchSnapshot.of(WebProviderOrder.defaults(), false, null, 0));

        org.mockito.ArgumentCaptor<String> user = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(ai).structured(anyString(), user.capture(), anyInt(), eq(SubAgentFactsDto.class));
        assertFalse(user.getValue().contains("写作意图/内容描述:"), "空内容描述不得出现该行");
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

    // ===== 10-04-serper-provider A-R3:时效题垂直路由 =====

    private static com.sparkora.config.DeepProperties deepProps(boolean newsEnabled) {
        com.sparkora.config.DeepProperties p = new com.sparkora.config.DeepProperties();
        p.setWebVerticalNewsEnabled(newsEnabled);
        return p;
    }

    /** 时效题 + 开关默认开 → 走 searchVertical(news),不走 search。 */
    @Test
    void 时效题_开关开_走news垂直() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        when(router.searchVertical(anyString(), anyInt(), any(), eq("news")))
                .thenReturn(oneHitOutcome());
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class),
                router, deepProps(true));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("最近的销量动态如何?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.Mockito.verify(router).searchVertical(anyString(), anyInt(), any(), eq("news"));
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never()).search(anyString(), anyInt(), any());
    }

    /** 非时效题 → 走既有 search(web),不调 searchVertical。 */
    @Test
    void 非时效题_走web() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        when(router.search(anyString(), anyInt(), any())).thenReturn(oneHitOutcome());
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class),
                router, deepProps(true));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("海狮08的价格是多少?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.Mockito.verify(router).search(anyString(), anyInt(), any());
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never())
                .searchVertical(anyString(), anyInt(), any(), anyString());
    }

    /** 开关关闭 → 即使时效题也走 web(零回归)。 */
    @Test
    void 时效题_开关关闭_仍走web() throws Exception {
        WebSearchRouter router = mock(WebSearchRouter.class);
        when(router.search(anyString(), anyInt(), any())).thenReturn(oneHitOutcome());
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), mock(KnowledgeSearchTool.class),
                router, deepProps(false));
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("最新的销量动态如何?", List.of("WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.Mockito.verify(router).search(anyString(), anyInt(), any());
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never())
                .searchVertical(anyString(), anyInt(), any(), anyString());
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
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("该车型的行业背景与战略目标是什么?", List.of("KB", "WEB"), 2, List.of(), "海狮08", "[]", null, snap);

        org.mockito.Mockito.verify(router, org.mockito.Mockito.times(1))
                .search(anyString(), anyInt(), any());
    }

    @Test
    void 参数题_KB命中权威块_跳过WEB() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        when(kb.search(anyString(), anyInt(), any())).thenReturn(List.of(modelInfoHit()));
        WebSearchRouter router = mock(WebSearchRouter.class);
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.defaults(), true, 1L, 5);

        r.research("海狮08的价格是多少?", List.of("KB", "WEB"), 2, List.of(), "海狮08", "[]", null, snap);

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
        // 两次 structured 均失败(非法 JSON 在结构化反序列化时抛错) → 走 FALLBACK 原始条目降级
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class))).thenThrow(new AiException("子代理汇总输出非法 JSON", null));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY,SEARXNG"), true, 7L, 5);

        SubAgentRunner.Note note = r.research("问题", List.of("WEB"), 2, List.of(), "主题", "[]", null, snap);

        assertEquals("FALLBACK", note.status());
        assertEquals(2, note.webCount(), "降级后仍应上报实际接受的 WEB 结果数");
        assertNotNull(note.search(), "保留搜索元数据");
        assertEquals(2, note.search().resultCount(), "search.resultCount 不得归零");
        assertEquals("LLM_FALLBACK", note.search().fallbackReason(), "LLM 降级原因应与 provider 尝试区分");
        assertEquals("TAVILY", note.search().provider());
        // 原始条目仍带可溯源 sourceId
        assertTrue(note.factsJson().contains("W1"));
    }

    // ===== B-R4/AC-B6(10-04-web-fanout-merge):SearchMeta.providers 为增量字段,旧字段语义不变 =====

    /** FANOUT 场景:usedProviders 多元素 → search.providers 完整列出;旧 provider 仍为首个。 */
    @Test
    void SearchMeta_providers增量透出_旧provider字段语义不变() throws Exception {
        KnowledgeSearchTool kb = mock(KnowledgeSearchTool.class);
        WebSearchRouter router = mock(WebSearchRouter.class);
        WebResultNormalizer.WebHit h1 = new WebResultNormalizer.WebHit("W1", "t1", "https://x.com/a", "s", "TAVILY");
        WebResultNormalizer.WebHit h2 = new WebResultNormalizer.WebHit("W2", "t2", "https://s.com/a", "s", "SEARXNG");
        WebSearchOutcome outcome = new WebSearchOutcome(List.of(h1, h2), WebProvider.TAVILY,
                List.of(new WebSearchOutcome.Attempt(WebProvider.TAVILY, 1, 10L, null, true, 0),
                        new WebSearchOutcome.Attempt(WebProvider.SEARXNG, 1, 12L, null, true, 0)),
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG));
        when(router.search(anyString(), anyInt(), any())).thenReturn(outcome);
        AiClient ai = mock(AiClient.class);
        when(ai.structured(anyString(), anyString(), anyInt(), eq(SubAgentFactsDto.class)))
                .thenReturn(typed("{\"facts\":[],\"gaps\":[]}", "m", 1));
        SubAgentRunner r = new SubAgentRunner(ai, new ObjectMapper(), kb, router);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY,SEARXNG"), true, 7L, 5,
                com.sparkora.deep.search.SearchStrategy.PRIMARY_FANOUT,
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), List.of(), List.of());

        SubAgentRunner.Note note = r.research("问题", List.of("WEB"), 2, List.of(), "主题", "[]", null, snap);

        assertNotNull(note.search());
        assertEquals("TAVILY", note.search().provider(), "旧 provider 字段语义不变(首个命中)");
        assertEquals(List.of("TAVILY", "SEARXNG"), note.search().providers(), "新增 providers 完整列出参与源");
    }

    /** 旧 7 参 SearchMeta 构造器:providers 由 provider 派生(向后兼容)。 */
    @Test
    void SearchMeta_旧7参构造器_providers派生自provider() {
        SubAgentRunner.SearchMeta meta = new SubAgentRunner.SearchMeta(
                "TAVILY_FIRST", "TAVILY", "q", 1, 10L, null, List.of());
        assertEquals(List.of("TAVILY"), meta.providers());
    }

    // ===== B-R3/AC-B4(10-04-web-fanout-merge):fanout 合并后 sourceId 可经 validateFacts 严格比对 =====

    /**
     * 反例/正例:两个 provider 各产出结果,经真实 merge 统一分配 sourceId 后,
     * LLM 引用任一 sourceId(含第二个 provider 的 W2)+ URL + provider 均能通过后验校验。
     * 若各 provider 各从 W1 起号,W2 的引用会被误剔——本用例锁死该正确性。
     */
    @Test
    void fanout合并后_引用任意sourceId均通过validateFacts() throws Exception {
        com.sparkora.deep.tool.TavilySearchTool tavily = mock(com.sparkora.deep.tool.TavilySearchTool.class);
        com.sparkora.deep.tool.SearxngSearchTool searxng = mock(com.sparkora.deep.tool.SearxngSearchTool.class);
        com.sparkora.deep.tool.SerperSearchTool serper = mock(com.sparkora.deep.tool.SerperSearchTool.class);
        when(tavily.available()).thenReturn(true);
        when(searxng.available()).thenReturn(true);
        when(tavily.search(anyString(), anyInt())).thenReturn(List.of(
                SearchTool.SearchHit.web("TAVILY", "t", "https://t.com/1", "s")));
        when(searxng.search(anyString(), anyInt())).thenReturn(List.of(
                SearchTool.SearchHit.web("SEARXNG", "s", "https://s.com/1", "s")));
        WebSearchRouter router = new WebSearchRouter(tavily, searxng, serper);
        WebSearchSnapshot snap = WebSearchSnapshot.of(WebProviderOrder.parse("TAVILY,SEARXNG"), true, 1L, 5,
                com.sparkora.deep.search.SearchStrategy.PRIMARY_FANOUT,
                List.of(WebProvider.TAVILY, WebProvider.SEARXNG), List.of(), List.of());

        WebSearchOutcome out = router.search("q", 5, snap);
        assertEquals(2, out.hits().size());
        // 找第二个 provider 的命中(SEARXNG),其 sourceId 必须全局唯一(非 W1)
        WebResultNormalizer.WebHit sx = out.hits().stream()
                .filter(h -> "SEARXNG".equals(h.provider())).findFirst().orElseThrow();
        assertFalse("W1".equals(sx.sourceId()), "第二 provider 的 sourceId 不得与第一 provider 冲突");

        SubAgentRunner r = new SubAgentRunner(null, new ObjectMapper(), null, null);
        String factsJson = "{\"facts\":[" + fact(sx.sourceId(), sx.url(), sx.provider())
                + ",{\"claim\":\"c2\",\"value\":\"v\",\"source\":{\"type\":\"WEB\",\"sourceId\":\"W999\","
                + "\"url\":\"https://evil.com/x\"}}],\"gaps\":[]}";
        String validated = r.validateFacts(factsJson, out.hits());

        ObjectMapper m = new ObjectMapper();
        assertEquals(1, m.readTree(validated).path("facts").size(), "合法引用保留、未知 sourceId 转 gap");
        assertTrue(validated.contains("https://s.com/1"), "第二个 provider 的 URL 被权威回填");
        assertEquals(1, m.readTree(validated).path("gaps").size(), "未知 sourceId 计入 gap");
    }

    private static String fact(String sourceId, String url, String provider) {
        return "{\"claim\":\"c1\",\"value\":\"v\",\"source\":{\"type\":\"WEB\",\"sourceId\":\"" + sourceId
                + "\",\"url\":\"" + url + "\",\"provider\":\"" + provider + "\"}}";
    }
}
