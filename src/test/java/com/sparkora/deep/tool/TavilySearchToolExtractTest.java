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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tavily 正文补抓契约单测(09-27-tavily-extract-kind-hypotheses R1/AC-01)。
 *
 * <p>机制 B:背景题对 top URL 调 {@code POST /extract} 取 {@code raw_content} 作正文,
 * 工具层截断到配置上限;{@code failed_results}/空 {@code results}/异常 → 空列表(降级回摘要,不抛)。
 * 以本地 stub HTTP 服务模拟 Tavily 端点(base 可注入,生产默认不变)。
 */
class TavilySearchToolExtractTest {

    private HttpServer server;
    private final AtomicReference<String> captured = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 成功:raw_content 3000 字(用于验证截断)
        server.createContext("/ok/extract", jsonResponse("{\"results\":[{\"url\":\"https://x.com/a\","
                + "\"raw_content\":\"" + "x".repeat(3000) + "\"}],\"failed_results\":[]}"));
        // 抽取失败:failed_results 非空、results 为空
        server.createContext("/fail/extract", jsonResponse("{\"results\":[],\"failed_results\":["
                + "{\"url\":\"https://x.com/a\",\"error\":\"boom\"}]}"));
        // 有结果但 raw_content 为空
        server.createContext("/empty/extract", jsonResponse("{\"results\":[{\"url\":\"https://x.com/a\","
                + "\"raw_content\":\"\"}],\"failed_results\":[]}"));
        // HTTP 500
        server.createContext("/boom/extract", ex -> {
            calls.incrementAndGet();
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "server error".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    private com.sun.net.httpserver.HttpHandler jsonResponse(String body) {
        return ex -> {
            calls.incrementAndGet();
            captured.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    private TavilySearchTool tool(int maxChars, String apiBase) {
        return new TavilySearchTool(new DeepProperties() {
            @Override public String effectiveTavilyKey() { return "test-key"; }
            @Override public int effectiveWebContentMaxChars() { return maxChars; }
        }, new ObjectMapper(), apiBase);
    }

    @Test
    void 正文非空_工具层截断到上限_请求参数正确() {
        TavilySearchTool t = tool(2000, base("/ok"));

        List<SearchTool.SearchHit> got = t.extract(List.of("https://x.com/a"), "行业背景是什么");

        assertEquals(1, got.size());
        assertEquals("https://x.com/a", got.get(0).url());
        assertEquals(2000, got.get(0).content().length(), "正文必须被工具层截断到配置上限");
        assertEquals("WEB", got.get(0).type());
        // 请求体关键参数:query 语义重排 + chunks_per_source=3 + extract_depth=basic
        String body = captured.get();
        assertTrue(body.contains("行业背景是什么"), "query 应进入请求: " + body);
        assertTrue(body.contains("\"chunks_per_source\":3"), body);
        assertTrue(body.contains("\"extract_depth\":\"basic\""), body);
        assertTrue(body.contains("https://x.com/a"), "urls 应进入请求: " + body);
    }

    @Test
    void 自定义上限_正文截断到该值() {
        List<SearchTool.SearchHit> got = tool(500, base("/ok")).extract(List.of("https://x.com/a"), "q");
        assertEquals(500, got.get(0).content().length());
    }

    @Test
    void failed_results或空raw_降级为空列表不抛出() {
        assertTrue(tool(2000, base("/fail")).extract(List.of("https://x.com/a"), "q").isEmpty(),
                "failed_results 应降级为空列表");
        assertTrue(tool(2000, base("/empty")).extract(List.of("https://x.com/a"), "q").isEmpty(),
                "空 raw_content 应降级为空列表");
    }

    @Test
    void HTTP异常_降级为空列表不抛出() {
        assertTrue(tool(2000, base("/boom")).extract(List.of("https://x.com/a"), "q").isEmpty());
    }

    @Test
    void 未配置密钥或空URLs_不发起请求() {
        TavilySearchTool noKey = new TavilySearchTool(new DeepProperties() {
            @Override public String effectiveTavilyKey() { return ""; }
        }, new ObjectMapper(), base("/ok"));
        assertTrue(noKey.extract(List.of("https://x.com/a"), "q").isEmpty());
        assertTrue(tool(2000, base("/ok")).extract(List.of(), "q").isEmpty());
        assertEquals(0, calls.get(), "未配置/空 urls 不得发起 HTTP 请求");
    }

    /**
     * 10-05-tavily-endpoint-priority T-R3/AC-T6:extract 使用独立 RestClient(默认 read 15000ms),
     * 不被官方 search 的 30s 连带改变。
     */
    @Test
    void extract超时独立_使用专属RestClient() {
        TavilySearchTool t = tool(2000, base("/ok"));
        assertNotSame(t.extractClient(), t.officialSearchClient(), "extract 必须独立于 official search");
        assertNotSame(t.extractClient(), t.relaySearchClient(), "extract 必须独立于 relay search");
        DeepProperties p = new DeepProperties();
        assertEquals(15000L, p.effectiveTavilyExtractReadTimeoutMs(), "extract 默认 15s(零回归)");
    }
}
