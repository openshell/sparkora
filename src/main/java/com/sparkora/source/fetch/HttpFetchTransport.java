package com.sparkora.source.fetch;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP 抓取通道(10-05-crawl4ai-transport,design §5)。普通 GET,复用 RestClient/jsoup。
 *
 * <p>同 host 相邻请求最小间隔由 {@link FetchRateLimiter} 保证(<b>无硬日上限</b>,工信部一次公示含多条子页可一轮采完);
 * 无配置门槛,{@code configured()} 恒 true。永不抛出,失败经 {@link FetchResult#error()} 表达。
 */
@Slf4j
@Component
public class HttpFetchTransport implements FetchTransport {

    private static final long CONNECT_TIMEOUT_MS = 5000;
    private static final long DEFAULT_READ_TIMEOUT_MS = 30000;

    private final FetchRateLimiter rateLimiter;
    /** 按读超时缓存 RestClient(超时相同则复用;不同源可各自指定)。 */
    private final Map<Long, RestClient> clients = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public HttpFetchTransport(com.sparkora.config.Crawl4aiProperties props) {
        this(new FetchRateLimiter(props));
    }

    /** 测试可注入限流器(包级可见)。 */
    HttpFetchTransport(FetchRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Kind kind() {
        return Kind.HTTP;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public FetchResult fetch(String url, FetchOptions opts) {
        FetchOptions o = opts == null ? FetchOptions.defaults() : opts;
        String host = FetchRateLimiter.hostOf(url);
        rateLimiter.acquire(host, Kind.HTTP);
        long start = System.currentTimeMillis();
        try {
            RestClient rest = clientFor(o.timeoutMs());
            return rest.method(methodOf(o.httpMethod()))
                    .uri(url)
                    .headers(h -> applyHeaders(h, o.headers()))
                    .exchange((req, resp) -> {
                        int status = resp.getStatusCode().value();
                        String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        long latency = System.currentTimeMillis() - start;
                        if (status < 200 || status >= 300) {
                            return new FetchResult(url, status, body, null, latency, "HTTP_" + status, false);
                        }
                        String html = body;
                        String content = o.wantHtml() ? null : toText(body);
                        return new FetchResult(url, status, html, content, latency, null, false);
                    });
        } catch (Exception e) {
            // 只记类名,不记 message(可能含 URL/凭据)
            log.warn("HTTP 抓取失败(降级): {}", e.getClass().getSimpleName());
            return new FetchResult(url, 0, null, null, System.currentTimeMillis() - start, classify(e), false);
        }
    }

    private RestClient clientFor(long timeoutMs) {
        long read = timeoutMs > 0 ? timeoutMs : DEFAULT_READ_TIMEOUT_MS;
        return clients.computeIfAbsent(read, t -> RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(
                        HttpClientSettings.defaults()
                                .withConnectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                                .withReadTimeout(Duration.ofMillis(t))))
                .build());
    }

    private static org.springframework.http.HttpMethod methodOf(String m) {
        return "POST".equalsIgnoreCase(m) ? org.springframework.http.HttpMethod.POST
                : org.springframework.http.HttpMethod.GET;
    }

    private static void applyHeaders(org.springframework.http.HttpHeaders headers, Map<String, String> extra) {
        if (extra != null) extra.forEach(headers::set);
    }

    /** HTML 转纯文本(jsoup;解析失败回退原文,不抛)。 */
    private static String toText(String html) {
        if (html == null || html.isBlank()) return html;
        try {
            return Jsoup.parse(html).text();
        } catch (Exception e) {
            return html;
        }
    }

    /** 异常归因:超时优先(遍历 cause 链),否则只记类名。 */
    private static String classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().contains("Timeout")) return "TIMEOUT";
        }
        return "ERROR";
    }
}
