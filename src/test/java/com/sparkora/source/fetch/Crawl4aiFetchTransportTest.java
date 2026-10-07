package com.sparkora.source.fetch;

import com.sparkora.config.Crawl4aiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Crawl4AI 抓取通道契约单测(10-05-crawl4ai-transport,AC-C1/C4/C5)。mock {@link Crawl4aiClient},不连真实服务。
 */
class Crawl4aiFetchTransportTest {

    private Crawl4aiProperties props(String baseUrl) {
        Crawl4aiProperties p = new Crawl4aiProperties();
        p.setBaseUrl(baseUrl);
        p.setApiKey("test-key");
        return p;
    }

    private Crawl4aiFetchTransport transport(Crawl4aiClient client, String baseUrl) {
        return new Crawl4aiFetchTransport(props(baseUrl), client);
    }

    @Test
    void 未配置base_返回UNCONFIGURED_不调用客户端不抛异常() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        Crawl4aiFetchTransport t = transport(client, "");

        assertFalse(t.configured());
        FetchResult r = t.fetch("https://www.cpcaauto.com/", FetchOptions.defaults());

        assertFalse(r.ok());
        assertEquals("UNCONFIGURED", r.error());
        assertFalse(r.limited());
        verify(client, never()).fetch(anyString(), anyBoolean());
    }

    @Test
    void 正常md抓取_返回ok且正文非空() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        when(client.fetch(anyString(), anyBoolean()))
                .thenReturn(new Crawl4aiClient.Result(200, "行业新闻：乘联会月度数据", 7000, null));
        Crawl4aiFetchTransport t = transport(client, "http://localhost:11235");

        FetchResult r = t.fetch("https://www.cpcaauto.com/", FetchOptions.defaults());

        assertTrue(r.ok());
        assertEquals(200, r.status());
        assertEquals("行业新闻：乘联会月度数据", r.content());
        assertEquals(null, r.html());
        assertEquals(null, r.error());
        assertFalse(r.limited());
    }

    @Test
    void wantHtml_调html并回填html字段() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        when(client.fetch(anyString(), anyBoolean()))
                .thenReturn(new Crawl4aiClient.Result(200, "<html>正文</html>", 100, null));
        Crawl4aiFetchTransport t = transport(client, "http://localhost:11235");

        FetchResult r = t.fetch("https://example.com", new FetchOptions("GET", null, true, 0));

        assertTrue(r.ok());
        assertEquals("<html>正文</html>", r.html());
        assertEquals(null, r.content());
    }

    @Test
    void 客户端超时_返回TIMEOUT态_不抛穿() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        when(client.fetch(anyString(), anyBoolean()))
                .thenReturn(Crawl4aiClient.Result.failure(20000, "TIMEOUT"));
        Crawl4aiFetchTransport t = transport(client, "http://localhost:11235");

        FetchResult r = t.fetch("https://www.cpcaauto.com/", FetchOptions.defaults());

        assertFalse(r.ok());
        assertEquals("TIMEOUT", r.error());
        assertFalse(r.limited());
    }

    @Test
    void 空正文_返回EMPTY态() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        when(client.fetch(anyString(), anyBoolean()))
                .thenReturn(Crawl4aiClient.Result.failure(500, "EMPTY"));
        Crawl4aiFetchTransport t = transport(client, "http://localhost:11235");

        FetchResult r = t.fetch("https://www.cpcaauto.com/", FetchOptions.defaults());

        assertFalse(r.ok());
        assertEquals("EMPTY", r.error());
    }

    @Test
    void 非2xx_返回HTTP状态分类() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        when(client.fetch(anyString(), anyBoolean()))
                .thenReturn(new Crawl4aiClient.Result(403, null, 100, "HTTP_403"));
        Crawl4aiFetchTransport t = transport(client, "http://localhost:11235");

        FetchResult r = t.fetch("https://www.cpcaauto.com/", FetchOptions.defaults());

        assertFalse(r.ok());
        assertEquals(403, r.status());
        assertEquals("HTTP_403", r.error());
    }

    @Test
    void 客户端意外抛异常_也只记分类不抛穿() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        when(client.fetch(anyString(), anyBoolean())).thenThrow(new RuntimeException("boom secret-key"));
        Crawl4aiFetchTransport t = transport(client, "http://localhost:11235");

        FetchResult r = t.fetch("https://www.cpcaauto.com/", FetchOptions.defaults());

        assertFalse(r.ok());
        assertEquals("ERROR", r.error());
        assertFalse(r.limited());
    }

    @Test
    void kind与configured语义() {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        Crawl4aiFetchTransport configured = transport(client, "http://localhost:11235");
        Crawl4aiFetchTransport blank = transport(client, "   ");

        assertEquals(FetchTransport.Kind.CRAWL4AI, configured.kind());
        assertTrue(configured.configured());
        assertFalse(blank.configured());
    }
}
