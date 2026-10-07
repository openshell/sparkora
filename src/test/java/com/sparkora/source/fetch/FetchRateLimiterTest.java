package com.sparkora.source.fetch;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抓取频控单测(10-05-crawl4ai-transport,AC-C3):Crawl4AI 同 host 日限、换 host 独立、跨日重置;
 * HTTP 仅最小间隔、无日上限。
 */
class FetchRateLimiterTest {

    /** 可变日期 + 可控时钟 + 记录休眠,隔离时间依赖。 */
    static class FakeTime {
        LocalDate date = LocalDate.of(2026, 10, 7);
        final AtomicLong clock = new AtomicLong(0);
        final List<Long> sleeps = new ArrayList<>();
    }

    private FetchRateLimiter limiter(FakeTime time, int dailyLimit, long httpInterval) {
        return new FetchRateLimiter(dailyLimit, httpInterval,
                () -> time.date, time.clock::get, time.sleeps::add);
    }

    @Test
    void Crawl4AI同host第3次被拒_前两次放行() {
        FakeTime time = new FakeTime();
        FetchRateLimiter l = limiter(time, 2, 2000);

        assertTrue(l.acquire("www.cpcaauto.com", FetchTransport.Kind.CRAWL4AI), "第1次放行");
        assertTrue(l.acquire("www.cpcaauto.com", FetchTransport.Kind.CRAWL4AI), "第2次放行");
        assertFalse(l.acquire("www.cpcaauto.com", FetchTransport.Kind.CRAWL4AI), "第3次应被拒(日限2)");
    }

    @Test
    void Crawl4AI不同host互不影响() {
        FakeTime time = new FakeTime();
        FetchRateLimiter l = limiter(time, 2, 2000);

        assertTrue(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI));
        assertTrue(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI));
        assertFalse(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI), "a.com 到限");
        assertTrue(l.acquire("b.com", FetchTransport.Kind.CRAWL4AI), "b.com 独立计数");
        assertTrue(l.acquire("b.com", FetchTransport.Kind.CRAWL4AI));
        assertFalse(l.acquire("b.com", FetchTransport.Kind.CRAWL4AI));
    }

    @Test
    void Crawl4AI跨本地日重置() {
        FakeTime time = new FakeTime();
        FetchRateLimiter l = limiter(time, 2, 2000);

        assertTrue(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI));
        assertTrue(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI));
        assertFalse(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI), "当日到限");

        time.date = time.date.plusDays(1);
        assertTrue(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI), "次日计数重置");
        assertTrue(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI));
        assertFalse(l.acquire("a.com", FetchTransport.Kind.CRAWL4AI));
    }

    @Test
    void HTTP无日上限_远超Crawl4AI日限仍放行() {
        FakeTime time = new FakeTime();
        // 间隔 0:只验证无日上限,不引入休眠
        FetchRateLimiter l = limiter(time, 2, 0);

        for (int i = 0; i < 5; i++) {
            assertTrue(l.acquire("www.miit.gov.cn", FetchTransport.Kind.HTTP), "HTTP 第" + (i + 1) + "次应放行");
        }
        assertTrue(time.sleeps.isEmpty(), "间隔配置为 0 时无需等待");
    }

    @Test
    void HTTP相邻请求间隔不足则等待() {
        FakeTime time = new FakeTime();
        FetchRateLimiter l = limiter(time, 2, 2000);

        assertTrue(l.acquire("www.miit.gov.cn", FetchTransport.Kind.HTTP));
        assertTrue(time.sleeps.isEmpty(), "首次请求无需等待");

        // 立刻再请求(clock=500,间隔 500 < 2000)→ 等待 2000-500=1500ms
        time.clock.set(500);
        assertTrue(l.acquire("www.miit.gov.cn", FetchTransport.Kind.HTTP));
        assertEquals(List.of(1500L), time.sleeps, "间隔不足应等待 2000-500=1500ms");

        // 间隔已足(clock 推进到 5000,距上次记录 0 已远超 2000)→ 不再等待
        time.clock.set(5000);
        assertTrue(l.acquire("www.miit.gov.cn", FetchTransport.Kind.HTTP));
        assertEquals(List.of(1500L), time.sleeps, "间隔充足不应新增等待");
    }

    @Test
    void hostOf解析与非法URL() {
        assertEquals("www.cpcaauto.com", FetchRateLimiter.hostOf("https://www.cpcaauto.com/news.php"));
        assertEquals("example.com", FetchRateLimiter.hostOf("http://EXAMPLE.com"));
        assertEquals("", FetchRateLimiter.hostOf("not a url"));
        assertEquals("", FetchRateLimiter.hostOf(null));
    }
}
