package com.sparkora.source.fetch;

import com.sparkora.config.Crawl4aiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crawl4AI 客户端渲染 HTML 路径单测(10-09-cpca-gasgoo-collection G1,AC-4)。
 *
 * <p>以本地 stub HTTP 服务模拟 Crawl4AI 端点:{@code /crawl} 返回 {@code results[0].cleaned_html}、
 * {@code /html} 返回旧字段;覆盖取 cleaned_html、回退 html、success=false/空 results → EMPTY、
 * /crawl 失败回退 /html、未配置 → UNCONFIGURED。
 */
class Crawl4aiClientTest {

    private HttpServer server;
    private final AtomicReference<String> lastCrawlBody = new AtomicReference<>();
    private final AtomicInteger crawlCalls = new AtomicInteger();
    private final AtomicInteger htmlCalls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/crawl", ex -> {
            crawlCalls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastCrawlBody.set(body);
            String out;
            if (body.contains("mode=empty")) {
                out = "{\"success\":true,\"results\":[]}";
            } else if (body.contains("mode=notsuccess")) {
                out = "{\"success\":true,\"results\":[{\"success\":false,\"error_message\":\"boom\"}]}";
            } else if (body.contains("mode=htmlonly")) {
                out = "{\"success\":true,\"results\":[{\"success\":true,\"html\":\"<html>RENDERED</html>\"}]}";
            } else if (body.contains("mode=topfail")) {
                out = "{\"success\":false}";
            } else {
                out = "{\"success\":true,\"results\":[{\"success\":true,\"cleaned_html\":\"<html>CLEANED</html>\"}]}";
            }
            reply(ex, 200, out);
        });
        server.createContext("/html", ex -> {
            htmlCalls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("legacyok")) {
                reply(ex, 200, "{\"success\":true,\"html\":\"<html>LEGACY</html>\"}");
            } else {
                reply(ex, 500, "{\"success\":false}");
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Crawl4aiClient client(String pathSuffix) {
        Crawl4aiProperties p = new Crawl4aiProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort()
                + (pathSuffix == null ? "" : pathSuffix));
        p.setApiKey("k");
        return new Crawl4aiClient(p);
    }

    @Test
    void crawl取cleaned_html_不回退html字段() {
        Crawl4aiClient c = client(null);
        Crawl4aiClient.Result r = c.fetchHtml("https://x/list?mode=normal");

        assertTrue(r.ok(), r.error());
        assertEquals("<html>CLEANED</html>", r.content());
        assertEquals(1, crawlCalls.get());
        assertEquals(0, htmlCalls.get(), "cleaned_html 命中时不应回退 /html");
        assertTrue(lastCrawlBody.get().contains("\"urls\""), "body 应为 {urls:[...]}: " + lastCrawlBody.get());
    }

    @Test
    void crawl缺cleaned_html_回退html字段() {
        Crawl4aiClient.Result r = client(null).fetchHtml("https://x/list?mode=htmlonly");
        assertTrue(r.ok(), r.error());
        assertEquals("<html>RENDERED</html>", r.content());
        assertEquals(0, htmlCalls.get());
    }

    @Test
    void crawl空results_返回EMPTY不抛() {
        Crawl4aiClient.Result r = client(null).fetchHtml("https://x/list?mode=empty");
        assertFalse(r.ok());
        assertEquals("EMPTY", r.error());
    }

    @Test
    void crawl成功但条目successfalse_返回EMPTY() {
        Crawl4aiClient.Result r = client(null).fetchHtml("https://x/list?mode=notsuccess");
        assertFalse(r.ok());
        assertEquals("EMPTY", r.error());
    }

    @Test
    void crawl顶层successfalse_返回EMPTY() {
        Crawl4aiClient.Result r = client(null).fetchHtml("https://x/list?mode=topfail");
        assertFalse(r.ok());
        assertEquals("EMPTY", r.error());
    }

    @Test
    void crawl失败_回退html旧端点() {
        // stub 的 /html 仅在 mode=legacyok 返回 200;这里 /crawl 用 topfail 但 URL 带 legacyok 会被 /html 看到
        Crawl4aiClient.Result r = client(null).fetchHtml("https://x/list?mode=topfail&legacyok=1");
        // /crawl 收到 mode=topfail → 失败;回退 /html,mode 含 legacyok → 返回 LEGACY
        assertTrue(r.ok(), r.error());
        assertEquals("<html>LEGACY</html>", r.content());
        assertEquals(1, crawlCalls.get());
        assertEquals(1, htmlCalls.get(), "应回退一次 /html");
    }

    @Test
    void crawl与html均失败_返回crawl失败态() {
        Crawl4aiClient.Result r = client(null).fetchHtml("https://x/list?mode=empty");
        assertFalse(r.ok());
        assertEquals("EMPTY", r.error());
        // 空 results 会触发回退 /html;此处 /html 500 → 最终仍是 crawl 的 EMPTY
        assertEquals(1, htmlCalls.get());
    }

    @Test
    void 未配置base_返回UNCONFIGURED不请求() {
        Crawl4aiProperties p = new Crawl4aiProperties();
        p.setBaseUrl("");
        Crawl4aiClient c = new Crawl4aiClient(p);
        assertFalse(c.configured());
        Crawl4aiClient.Result r = c.fetchHtml("https://x/");
        assertFalse(r.ok());
        assertEquals("UNCONFIGURED", r.error());
        assertEquals(0, crawlCalls.get());
        assertEquals(0, htmlCalls.get());
    }

    @Test
    void fetch分派_md走md_crawl走渲染html() {
        Crawl4aiClient c = client(null);
        assertNotNull(c.fetch("https://x/list?mode=normal", true));
        assertEquals(1, crawlCalls.get());
        assertTrue(c.lastCallOk());
    }
}
