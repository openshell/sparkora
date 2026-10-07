package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tavily 双端点 failover 契约单测(10-05-tavily-endpoint-priority T-R1..T-R6 / AC-T1..T6)。
 *
 * <p>以本地 stub HTTP 服务模拟 relay 与 official 两端点(基址/密钥可注入,生产默认不变);
 * relay 有效命中 → official 零请求;relay 失败/超时/空/全非法/噪声域 → 自动切官方;
 * 未配置中转 → 行为等价现状(官方单端点)。
 */
class TavilySearchToolRelayTest {

    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger relayCalls = new AtomicInteger();
    private final AtomicInteger officialCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 延迟 handler 不得阻塞其他 context 的请求(用守护线程池,避免测试 JVM 被非守护线程挂住)
        executor = Executors.newCachedThreadPool(r -> {
            Thread th = new Thread(r);
            th.setDaemon(true);
            return th;
        });
        server.setExecutor(executor);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** 在给定 context 注册 JSON 响应处理器(记录调用计数)。 */
    private void json(String context, AtomicInteger counter, String body) {
        server.createContext(context, ex -> {
            counter.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    /** 在给定 context 注册延迟 handler(用于模拟中转慢失败/超时)。 */
    private void delayed(String context, AtomicInteger counter, long delayMs, String body) {
        server.createContext(context, ex -> {
            counter.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private String base(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    private static String resultsJson(String url, String title, String content) {
        return "{\"results\":[{\"title\":\"" + title + "\",\"url\":\"" + url + "\",\"content\":\"" + content + "\"}]}";
    }

    /**
     * 构造带中转配置的工具:official base/key 与 relay base/key 可分别注入;超时经 props 覆盖。
     */
    private TavilySearchTool tool(String officialPrefix, String relayPrefix, long relayReadTimeoutMs) {
        DeepProperties props = new DeepProperties() {
            @Override public String effectiveTavilyKey() { return "official-key"; }
            @Override public String effectiveTavilyRelayKey() { return "relay-key"; }
            @Override public String effectiveTavilyRelayBase() { return base(relayPrefix); }
            @Override public long effectiveTavilyRelayReadTimeoutMs() { return relayReadTimeoutMs; }
            @Override public long effectiveTavilyOfficialReadTimeoutMs() { return 30000L; }
            @Override public long effectiveTavilyExtractReadTimeoutMs() { return 15000L; }
            @Override public java.util.List<String> effectiveTavilyDenyDomains() {
                return java.util.List.of("weixin.sogou.com");
            }
        };
        return new TavilySearchTool(props, new ObjectMapper(), base(officialPrefix));
    }

    /** 未配置中转(relay base/key 均空)的工具。 */
    private TavilySearchTool officialOnlyTool(String officialPrefix) {
        DeepProperties props = new DeepProperties() {
            @Override public String effectiveTavilyKey() { return "official-key"; }
            @Override public String effectiveTavilyRelayKey() { return ""; }
            @Override public String effectiveTavilyRelayBase() { return ""; }
        };
        return new TavilySearchTool(props, new ObjectMapper(), base(officialPrefix));
    }

    // ===== AC-T1:中转优先,有效命中不调用官方 =====

    @Test
    void AC_T1_中转有效命中_官方零请求() {
        json("/relay/search", relayCalls, resultsJson("https://relay.example/a", "中转标题", "中转摘要"));
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 8000);

        List<SearchTool.SearchHit> hits = t.search("q", 5);

        assertEquals(1, hits.size());
        assertEquals("https://relay.example/a", hits.get(0).url());
        assertEquals("TAVILY", hits.get(0).modelName(), "provider 身份固定 TAVILY(工具名)");
        assertEquals(1, relayCalls.get(), "中转被调用一次");
        assertEquals(0, officialCalls.get(), "AC-T1:中转命中时官方零请求");
        assertEquals("relay", t.lastUsedEndpoint());
    }

    @Test
    void 中转命中_每端点每轮最多一次调用_不重试() {
        json("/relay/search", relayCalls, resultsJson("https://relay.example/a", "t", "s"));
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "t", "s"));
        TavilySearchTool t = tool("/official", "/relay", 8000);
        t.search("q", 5);
        t.search("q2", 5);
        assertEquals(2, relayCalls.get(), "每轮一次,不重试");
        assertEquals(0, officialCalls.get());
    }

    // ===== AC-T2:中转失败/空/全非法 → 官方兜底 =====

    @Test
    void AC_T2_中转空结果_降级官方并采用() {
        json("/relay/search", relayCalls, "{\"results\":[]}");
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 8000);

        List<SearchTool.SearchHit> hits = t.search("q", 5);

        assertEquals(1, hits.size());
        assertEquals("https://official.example/a", hits.get(0).url(), "采用官方命中");
        assertEquals(1, relayCalls.get());
        assertEquals(1, officialCalls.get(), "AC-T2:中转空 → 调官方");
        assertEquals("official", t.lastUsedEndpoint());
    }

    @Test
    void AC_T2_中转结果全部非法URL_降级官方() {
        json("/relay/search", relayCalls, resultsJson("not-a-url", "无协议", "x"));
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 8000);

        assertEquals("https://official.example/a", t.search("q", 5).get(0).url());
        assertEquals(1, officialCalls.get(), "全非法 URL → 视为无效切官方");
    }

    @Test
    void AC_T2_中转HTTP异常_降级官方不抛() {
        server.createContext("/relay/search", ex -> {
            relayCalls.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] bytes = "server error".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 8000);

        List<SearchTool.SearchHit> hits = t.search("q", 5);
        assertEquals(1, hits.size());
        assertEquals("official", t.lastUsedEndpoint());
        assertTrue(t.lastCallOk(), "官方调用成功 → lastCallOk=true");
    }

    @Test
    void 两端点均不可用_返回空且不抛() {
        json("/relay/search", relayCalls, "{\"results\":[]}");
        json("/official/search", officialCalls, "{\"results\":[]}");
        TavilySearchTool t = tool("/official", "/relay", 8000);

        List<SearchTool.SearchHit> hits = t.search("q", 5);

        assertTrue(hits.isEmpty(), "两都不可用 → 空(交路由继续下一 provider)");
        assertEquals(1, relayCalls.get());
        assertEquals(1, officialCalls.get());
        assertTrue(t.lastCallOk(), "官方 HTTP 调用成功(仅结果空)→ lastCallOk=true");
    }

    // ===== AC-T3:独立短超时 → 快速失败切官方 =====

    @Test
    void AC_T3_中转慢响应_短超时快速失败并切官方() {
        // 中转延迟 1500ms;relay read 超时 200ms → 快速超时;官方立即响应
        delayed("/relay/search", relayCalls, 1500, resultsJson("https://relay.example/a", "慢", "慢"));
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 200);

        long began = System.currentTimeMillis();
        List<SearchTool.SearchHit> hits = t.search("q", 5);
        long cost = System.currentTimeMillis() - began;

        assertEquals("https://official.example/a", hits.get(0).url(), "超时后切官方");
        assertEquals("official", t.lastUsedEndpoint());
        // 中转延迟 1500ms,relay 200ms 超时;若共用 30s 官方超时则须等满 ~1500ms
        assertTrue(cost < 1200, "短超时应快速失败,不应等满中转延迟;实际 " + cost + "ms");
    }

    @Test
    void AC_T3_官方超时独立于中转_不同RestClient实例() {
        TavilySearchTool t = tool("/official", "/relay", 8000);
        assertNotSame(t.relaySearchClient(), t.officialSearchClient(), "两端点不得共用同一 RestClient");
        assertNotSame(t.relaySearchClient(), t.extractClient(), "extract 须独立于 relay");
        assertNotSame(t.officialSearchClient(), t.extractClient(), "extract 须独立于 official search");
        assertInstanceOf(org.springframework.web.client.RestClient.class, t.extractClient());
    }

    // ===== AC-T5:质量门 =====

    @Test
    void AC_T5_中转空title且空content_视为无效切官方() {
        json("/relay/search", relayCalls, "{\"results\":[{\"title\":\"\",\"url\":\"https://relay.example/a\",\"content\":\"\"}]}");
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 8000);

        assertEquals("https://official.example/a", t.search("q", 5).get(0).url());
        assertEquals(1, officialCalls.get(), "空 title+content → 低质,切官方");
    }

    @Test
    void AC_T5_中转噪声域_视为无效切官方() {
        json("/relay/search", relayCalls,
                resultsJson("https://weixin.sogou.com/link?url=abc", "跳转", "聚合跳转页"));
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = tool("/official", "/relay", 8000);

        assertEquals("https://official.example/a", t.search("q", 5).get(0).url());
        assertEquals(1, officialCalls.get(), "噪声域命中 → 端点级质量门判无效");
    }

    @Test
    void AC_T5_结果级长度阈值_默认off不减召回_开启后过滤低质() {
        // 默认 off:短正文命中仍保留
        json("/relay/search", relayCalls, resultsJson("https://relay.example/a", "标题", "短"));
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方", "官方"));
        TavilySearchTool off = tool("/official", "/relay", 8000);
        assertEquals(1, off.search("q", 5).size(), "默认阈值 off → 不丢召回零回归");

        // 显式开启阈值 50:relay 短正文命中被结果级门过滤(注意端点级门先通过,采用 relay 后结果级过滤)
        server.removeContext("/relay/search");
        DeepProperties props = new DeepProperties() {
            @Override public String effectiveTavilyKey() { return "official-key"; }
            @Override public String effectiveTavilyRelayKey() { return "relay-key"; }
            @Override public String effectiveTavilyRelayBase() { return base("/relay"); }
            @Override public int effectiveTavilyMinContentChars() { return 50; }
        };
        // relay content 长度 < 50 → 端点级质量门通过(title 非空)但结果级过滤后为空
        json("/relay/search", relayCalls, resultsJson("https://relay.example/a", "标题", "短正文"));
        TavilySearchTool gated = new TavilySearchTool(props, new ObjectMapper(), base("/official"));
        List<SearchTool.SearchHit> gatedHits = gated.search("q", 5);
        assertEquals(0, gatedHits.size(), "结果级阈值生效:低质结果丢弃而非注入");
        assertEquals(0, officialCalls.get(), "结果级门不触发 failover(relay 端点级已通过)");
    }

    // ===== AC-T6:未配置中转零回归 =====

    @Test
    void AC_T6_未配置中转_直接走官方_身份与行为等价现状() {
        server.createContext("/relay/search", ex -> relayCalls.incrementAndGet());
        json("/official/search", officialCalls, resultsJson("https://official.example/a", "官方标题", "官方摘要"));
        TavilySearchTool t = officialOnlyTool("/official");

        List<SearchTool.SearchHit> hits = t.search("q", 5);

        assertEquals(1, hits.size());
        assertEquals("https://official.example/a", hits.get(0).url());
        assertEquals("TAVILY", hits.get(0).modelName());
        assertEquals(0, relayCalls.get(), "未配置中转不得发起中转请求(零回归)");
        assertEquals(1, officialCalls.get());
        assertEquals("official", t.lastUsedEndpoint());
        assertTrue(t.configured());
        assertTrue(t.officialConfigured());
        assertTrue(!t.relayConfigured());
    }

    @Test
    void 仅配置中转亦可使用_configured为真() {
        json("/relay/search", relayCalls, resultsJson("https://relay.example/a", "t", "s"));
        DeepProperties props = new DeepProperties() {
            @Override public String effectiveTavilyKey() { return ""; }            // 官方未配置
            @Override public String effectiveTavilyRelayKey() { return "relay-key"; }
            @Override public String effectiveTavilyRelayBase() { return base("/relay"); }
        };
        TavilySearchTool t = new TavilySearchTool(props, new ObjectMapper(), base("/official"));
        assertTrue(t.configured(), "仅中转配置也算就绪");
        assertEquals(1, t.search("q", 5).size());
        assertEquals("relay", t.lastUsedEndpoint());
    }
}
