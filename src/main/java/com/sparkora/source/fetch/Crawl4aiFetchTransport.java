package com.sparkora.source.fetch;

import com.sparkora.config.Crawl4aiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Crawl4AI 抓取通道(10-05-crawl4ai-transport,design §4)。用于 B 级信源(指纹 WAF/SPA)。
 *
 * <p><b>资源红线</b>:全局并发 ≤ {@code CRAWL4AI_MAX_CONCURRENCY}(默认 2),非阻塞 {@code tryAcquire}
 * ——取不到许可立即返回 {@code limited=true}(不排队);同 host 每本地日 ≤ {@code CRAWL4AI_PER_HOST_DAILY_LIMIT}
 * (默认 2),到限同样返回限流态。未配置 baseUrl 时 {@code configured()=false} → {@code UNCONFIGURED} 降级跳过。
 */
@Slf4j
@Component
public class Crawl4aiFetchTransport implements FetchTransport {

    private final Crawl4aiProperties props;
    private final Crawl4aiClient client;
    private final FetchRateLimiter rateLimiter;
    private final Semaphore semaphore;

    @org.springframework.beans.factory.annotation.Autowired
    public Crawl4aiFetchTransport(Crawl4aiProperties props, Crawl4aiClient client) {
        this(props, client, new FetchRateLimiter(props));
    }

    /** 测试可注入限流器(包级可见,生产默认由 props 构造)。 */
    Crawl4aiFetchTransport(Crawl4aiProperties props, Crawl4aiClient client, FetchRateLimiter rateLimiter) {
        this.props = props;
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.semaphore = new Semaphore(Math.max(1, props.getMaxConcurrency()));
    }

    @Override
    public Kind kind() {
        return Kind.CRAWL4AI;
    }

    @Override
    public boolean configured() {
        return props.getBaseUrl() != null && !props.getBaseUrl().isBlank();
    }

    @Override
    public FetchResult fetch(String url, FetchOptions opts) {
        FetchOptions o = opts == null ? FetchOptions.defaults() : opts;
        if (!configured()) {
            return FetchResult.failure(url, "UNCONFIGURED");
        }
        String host = FetchRateLimiter.hostOf(url);
        if (!rateLimiter.acquire(host, Kind.CRAWL4AI)) {
            return FetchResult.limited(url, "PER_HOST_DAILY_LIMIT");
        }
        if (!semaphore.tryAcquire()) {
            // 非阻塞:并发已满立即返回限流态,交由上层降级跳过(不排队)
            return FetchResult.limited(url, "CONCURRENCY_LIMIT");
        }
        long start = System.currentTimeMillis();
        try {
            Crawl4aiClient.Result r = client.fetch(url, o.wantHtml());
            long latency = r.ok() ? (r.latencyMs() > 0 ? r.latencyMs() : System.currentTimeMillis() - start)
                    : System.currentTimeMillis() - start;
            if (r.ok()) {
                int status = r.status() <= 0 ? 200 : r.status();
                String html = o.wantHtml() ? r.content() : null;
                String content = o.wantHtml() ? null : r.content();
                return new FetchResult(url, status, html, content, latency, null, false);
            }
            return new FetchResult(url, r.status(), null, null, latency, r.error(), false);
        } catch (Exception e) {
            // 双保险:client 契约不抛,意外异常也只记类名,绝不抛穿调用方
            log.warn("Crawl4AI 抓取异常(降级): {}", e.getClass().getSimpleName());
            return new FetchResult(url, 0, null, null, System.currentTimeMillis() - start, "ERROR", false);
        } finally {
            semaphore.release();
        }
    }
}
