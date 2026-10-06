package com.sparkora.deep.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.DeepProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serper 搜索工具契约单测(10-04-serper-provider A,AC-A2/A4/A5/A6/A7/A9)。
 *
 * <p>以本地 stub HTTP 服务模拟 Serper 端点(apiBase 可注入),覆盖:
 * Header {@code X-API-KEY} 认证、apiBase 路径段保留、末尾斜杠归一、gl/hl 默认与空值不下发、
 * num clamp 10、organic/news 字段映射、news 的 date/source 保留、异常降级不抛。
 */
class SerperSearchToolTest {

    private HttpServer server;
    private final AtomicReference<String> capturedBody = new AtomicReference<>();
    private final AtomicReference<String> capturedApiKey = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/serper/search", json(organicBody()));
        server.createContext("/serper/news", json(newsBody()));
        server.createContext("/boom/search", ex -> {
            capture(ex);
            byte[] bytes = "server error".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(500, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/bad/search", json("{not-json"));
        server.start();
    }

    private static String organicBody() {
        return "{\"credits\":1,\"organic\":["
                + "{\"title\":\"标题一\",\"link\":\"https://a.com/x\",\"snippet\":\"摘要一\",\"position\":1},"
                + "{\"title\":\"标题二\",\"link\":\"https://b.com/y\",\"snippet\":\"摘要二\",\"position\":2}]}";
    }

    private static String newsBody() {
        return "{\"credits\":1,\"news\":["
                + "{\"title\":\"新闻一\",\"link\":\"https://n.com/1\",\"snippet\":\"正文一\","
                + "\"date\":\"2小时前\",\"source\":\"新浪网\",\"imageUrl\":\"https://img/1\"}]}";
    }

    private HttpHandler json(String body) {
        return ex -> {
            capture(ex);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        };
    }

    private void capture(HttpExchange ex) {
        calls.incrementAndGet();
        try {
            capturedBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            capturedBody.set("");
        }
        capturedApiKey.set(ex.getRequestHeaders().getFirst("X-API-KEY"));
        capturedPath.set(ex.getRequestURI().getPath());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DeepProperties props() {
        return new DeepProperties() {
            @Override public String effectiveSerperKey() { return "test-key"; }
        };
    }

    /** 注入指定 apiBase(指向本地 stub)。 */
    private SerperSearchTool tool(String apiBase) {
        return new SerperSearchTool(props(), new ObjectMapper(), apiBase);
    }

    private String base(String prefix) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
    }

    // ===== A-A7 认证隔离:Header X-API-KEY(不是 body api_key) =====

    @Test
    void 请求头含X_API_KEY_且body不含api_key() {
        SerperSearchTool t = tool(base("/serper"));
        t.search("比亚迪", 5);

        assertEquals("test-key", capturedApiKey.get(), "认证必须走 Header X-API-KEY");
        assertFalse(capturedBody.get().contains("api_key"), "Serper 不得用 body api_key(与 Tavily 不同): " + capturedBody.get());
    }

    // ===== A-R2/apiBase 路径段 =====

    @Test
    void apiBase含路径段serper_最终路径为serper_search() {
        tool(base("/serper")).search("q", 5);
        assertEquals("/serper/search", capturedPath.get(), "apiBase 的路径段 /serper 不得被丢弃");
    }

    @Test
    void apiBase结尾斜杠_不产生双斜杠() {
        tool(base("/serper") + "/").search("q", 5);
        assertEquals("/serper/search", capturedPath.get(), "末尾斜杠必须归一,不得拼出 //search");
    }

    // ===== A-R4 地域参数 =====

    @Test
    void 默认body含gl_cn与hl_zh_cn() {
        tool(base("/serper")).search("q", 5);
        assertTrue(capturedBody.get().contains("\"gl\":\"cn\""), capturedBody.get());
        assertTrue(capturedBody.get().contains("\"hl\":\"zh-cn\""), capturedBody.get());
    }

    @Test
    void gl_hl为空_不下发该键() {
        DeepProperties p = new DeepProperties() {
            @Override public String effectiveSerperKey() { return "test-key"; }
            @Override public String effectiveSerperGl() { return ""; }
            @Override public String effectiveSerperHl() { return "  "; }
        };
        new SerperSearchTool(p, new ObjectMapper(), base("/serper")).search("q", 5);
        assertFalse(capturedBody.get().contains("\"gl\""), "空 gl 不得下发: " + capturedBody.get());
        assertFalse(capturedBody.get().contains("\"hl\""), "空 hl 不得下发: " + capturedBody.get());
    }

    // ===== A-R5 条数 clamp =====

    @Test
    void num请求20_clamp到10() {
        tool(base("/serper")).search("q", 20);
        assertTrue(capturedBody.get().contains("\"num\":10"), "Serper 请求 num 必须 clamp 到 10: " + capturedBody.get());
    }

    @Test
    void num请求3_保持3() {
        tool(base("/serper")).search("q", 3);
        assertTrue(capturedBody.get().contains("\"num\":3"), capturedBody.get());
    }

    // ===== A-A4 字段映射 =====

    @Test
    void web垂直_解析organic字段() {
        List<SearchTool.SearchHit> hits = tool(base("/serper")).search("q", 5);
        assertEquals(2, hits.size());
        SearchTool.SearchHit h = hits.get(0);
        assertEquals("标题一", h.title());
        assertEquals("https://a.com/x", h.url());
        assertEquals("摘要一", h.snippet());
        assertEquals("WEB", h.type());
        // 工具层把来源工具名记入 modelName(provider 由 WebResultNormalizer 治理时回填)
        assertEquals("SERPER", h.modelName());
    }

    @Test
    void news垂直_解析news并保留date与source() {
        List<SearchTool.SearchHit> hits = tool(base("/serper")).searchVertical("q", "news", 5);
        assertEquals(1, hits.size());
        SearchTool.SearchHit h = hits.get(0);
        assertEquals("新闻一", h.title());
        assertEquals("https://n.com/1", h.url());
        assertEquals("正文一", h.snippet());
        assertTrue(capturedPath.get().endsWith("/news"), "news 垂直应走 /news 路径");
        // date/source 保留进 content(JSON 载体),供 R4b 消费
        assertTrue(h.content() != null && h.content().contains("2小时前"), "date 应保留: " + h.content());
        assertTrue(h.content().contains("新浪网"), "source 应保留: " + h.content());
    }

    @Test
    void news无date_source_content为null() {
        server.createContext("/nometa/news", json("{\"news\":[{\"title\":\"t\",\"link\":\"https://x.com/a\",\"snippet\":\"s\"}]}"));
        List<SearchTool.SearchHit> hits = tool(base("/nometa")).searchVertical("q", "news", 5);
        assertNull(hits.get(0).content(), "无 date/source 时 content 应为 null(与既有命中一致)");
    }

    @Test
    void search委托web垂直_searchVertical走search路径() {
        tool(base("/serper")).search("q", 5);
        assertEquals("/serper/search", capturedPath.get());
    }

    @Test
    void 未知vertical_回落web不抛() {
        List<SearchTool.SearchHit> hits = tool(base("/serper")).searchVertical("q", "video", 5);
        assertEquals(2, hits.size(), "未知 vertical 回落 web,不抛异常");
        assertEquals("/serper/search", capturedPath.get());
    }

    // ===== 异常/降级 =====

    @Test
    void HTTP500_空列表且lastOk为false_不抛() {
        SerperSearchTool t = tool(base("/boom"));
        assertTrue(t.search("q", 5).isEmpty());
        assertFalse(t.lastCallOk(), "调用失败仅记健康态");
        assertTrue(t.available(), "可用性只判配置就绪,失败可自恢复");
    }

    @Test
    void 非法JSON_空列表且lastOk为false_不抛() {
        SerperSearchTool t = tool(base("/bad"));
        assertTrue(t.search("q", 5).isEmpty());
        assertFalse(t.lastCallOk());
    }

    @Test
    void 未配置密钥_不可用且不发请求() {
        SerperSearchTool noKey = new SerperSearchTool(new DeepProperties() {
            @Override public String effectiveSerperKey() { return ""; }
        }, new ObjectMapper(), base("/serper"));
        assertFalse(noKey.configured());
        assertFalse(noKey.available());
        assertTrue(noKey.search("q", 5).isEmpty());
        assertEquals(0, calls.get(), "未配置密钥不得发起 HTTP 请求");
    }

    @Test
    void 初始lastOk乐观为真() {
        assertTrue(tool(base("/serper")).lastCallOk(), "未调用过乐观为真");
    }

    /** AC-A2:仅改配置(property)即可切换端点——生产构造器经 effectiveSerperApiBase 读取。 */
    @Test
    void 生产构造器_读生效apiBase_仅改配置切换端点() {
        System.setProperty("DEEP_SERPER_API_BASE_URL", base("/serper"));
        try {
            // 2 参生产构造器(不注入 apiBase),应走 DEEP_SERPER_API_BASE_URL 生效值
            SerperSearchTool t = new SerperSearchTool(props(), new ObjectMapper());
            List<SearchTool.SearchHit> hits = t.search("q", 5);
            assertEquals(2, hits.size(), "生产构造器应使用配置的端点");
            assertEquals("/serper/search", capturedPath.get());
            assertEquals("test-key", capturedApiKey.get());
        } finally {
            System.clearProperty("DEEP_SERPER_API_BASE_URL");
        }
    }
}
