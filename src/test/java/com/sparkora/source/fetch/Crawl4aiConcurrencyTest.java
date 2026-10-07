package com.sparkora.source.fetch;

import com.sparkora.config.Crawl4aiProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Crawl4AI 并发红线单测(10-05-crawl4ai-transport,AC-C2):并发 {@code > maxConcurrency} 时在途 ≤ 上限,
 * 超出部分快速返回 {@code limited}(不排队)、错误码 {@code CONCURRENCY_LIMIT}。
 */
class Crawl4aiConcurrencyTest {

    @Test
    void 并发超上限_在途不超过上限_超出快速限流不排队() throws Exception {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(client.fetch(anyString(), anyBoolean())).thenAnswer(inv -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            inFlight.decrementAndGet();
            return new Crawl4aiClient.Result(200, "行业新闻正文", 10, null);
        });

        Crawl4aiProperties props = new Crawl4aiProperties();
        props.setBaseUrl("http://localhost:11235");
        props.setMaxConcurrency(2);
        props.setPerHostDailyLimit(100);   // 隔离日限,专测并发
        Crawl4aiFetchTransport t = new Crawl4aiFetchTransport(props, client);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<FetchResult>> futures = new ArrayList<>();
            // 不同 host,避免日限干扰;三个 URL
            String[] urls = {"https://a.example.com/1", "https://b.example.com/2", "https://c.example.com/3"};
            for (String u : urls) {
                futures.add(pool.submit(() -> t.fetch(u, FetchOptions.defaults())));
            }

            assertTrue(entered.await(5, TimeUnit.SECONDS), "应有 2 个抓取进入在途");

            // 并发已满:第 3 个必须快速返回限流态(不排队等待 release),按「谁先完成」判定而非提交序
            FetchResult limited = awaitOneDone(futures, 3000);
            assertNotNull(limited, "并发满时应有 1 个快速返回(不排队)");
            assertFalse(limited.ok());
            assertTrue(limited.limited(), "并发满应返回 limited=true");
            assertEquals("CONCURRENCY_LIMIT", limited.error());

            release.countDown();
            int okCount = 0;
            for (Future<FetchResult> fu : futures) {
                if (fu.get(5, TimeUnit.SECONDS).ok()) okCount++;
            }
            assertEquals(2, okCount, "持有许可的 2 个应成功、第 3 个限流");
            assertEquals(2, maxInFlight.get(), "实际在途不得超过 maxConcurrency=2");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 轮询等待任一 future 完成(并发限流者应快速返回)。 */
    private static FetchResult awaitOneDone(List<Future<FetchResult>> futures, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Future<FetchResult> fu : futures) {
                if (fu.isDone()) return fu.get(0, TimeUnit.MILLISECONDS);
            }
            Thread.sleep(20);
        }
        return null;
    }

    @Test
    void 并发上限为1时可配_第二个快速限流() throws Exception {
        Crawl4aiClient client = mock(Crawl4aiClient.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        when(client.fetch(anyString(), anyBoolean())).thenAnswer(inv -> {
            firstEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new Crawl4aiClient.Result(200, "正文", 10, null);
        });
        Crawl4aiProperties props = new Crawl4aiProperties();
        props.setBaseUrl("http://localhost:11235");
        props.setMaxConcurrency(1);
        props.setPerHostDailyLimit(100);
        Crawl4aiFetchTransport t = new Crawl4aiFetchTransport(props, client);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<FetchResult> f1 = pool.submit(() -> t.fetch("https://a.com/1", FetchOptions.defaults()));
            // 确定性等待第一个真正进入在途(持锁),而非 sleep 猜测
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "第一个应进入在途");
            FetchResult second = pool.submit(() -> t.fetch("https://b.com/2", FetchOptions.defaults()))
                    .get(3, TimeUnit.SECONDS);
            assertTrue(second.limited());
            assertEquals("CONCURRENCY_LIMIT", second.error());
            release.countDown();
            assertTrue(f1.get(5, TimeUnit.SECONDS).ok());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
