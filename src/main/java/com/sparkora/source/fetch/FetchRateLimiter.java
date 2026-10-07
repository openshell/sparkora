package com.sparkora.source.fetch;

import com.sparkora.config.Crawl4aiProperties;

import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 抓取频控(按通道区分,父任务 design §3,评审 2026-10-05)。进程内计数,不跨实例。
 *
 * <ul>
 *   <li><b>Crawl4AI</b>:同 host <b>每本地日 ≤ perHostDailyLimit(默认 2)</b>,按本地日期键重置 → 到限返回 false(限流)。</li>
 *   <li><b>HTTP</b>:<b>无硬日上限</b>,仅同 host 相邻请求间隔 ≥ httpMinIntervalMs(默认 2000,不足则等待)→ 恒 true。</li>
 * </ul>
 */
public class FetchRateLimiter {

    private final int perHostDailyLimit;
    private final long httpMinIntervalMs;
    private final Supplier<LocalDate> dateSupplier;
    private final LongSupplier clockMs;
    private final LongConsumer sleeper;

    /** Crawl4AI:host -> (本地日期 -> 当日计数)。 */
    private final Map<String, Map<LocalDate, Integer>> dailyCounts = new ConcurrentHashMap<>();
    /** HTTP:host -> 上次请求时刻(毫秒)。 */
    private final Map<String, Long> lastHttpCallMs = new ConcurrentHashMap<>();
    /** HTTP:host -> 串行锁(保证间隔检查与记录原子)。 */
    private final Map<String, Object> httpLocks = new ConcurrentHashMap<>();

    public FetchRateLimiter(Crawl4aiProperties props) {
        this(props.getPerHostDailyLimit(), props.getSourceHttpMinIntervalMs(),
                LocalDate::now, System::currentTimeMillis, FetchRateLimiter::sleepQuietly);
    }

    /** 测试可注入日期/时钟/休眠,隔离时间依赖(包级可见)。 */
    FetchRateLimiter(int perHostDailyLimit, long httpMinIntervalMs,
                     Supplier<LocalDate> dateSupplier, LongSupplier clockMs, LongConsumer sleeper) {
        this.perHostDailyLimit = perHostDailyLimit;
        this.httpMinIntervalMs = httpMinIntervalMs;
        this.dateSupplier = dateSupplier;
        this.clockMs = clockMs;
        this.sleeper = sleeper;
    }

    /**
     * 申请一次抓取许可。
     *
     * @param host 目标主机(见 {@link #hostOf(String)})
     * @param kind 通道类型
     * @return Crawl4AI:未达当日上限 true / 达上限 false(限流);HTTP:恒 true(不足间隔会阻塞等待)
     */
    public boolean acquire(String host, FetchTransport.Kind kind) {
        if (kind == FetchTransport.Kind.CRAWL4AI) {
            return acquireDaily(host);
        }
        awaitHttpInterval(host);
        return true;
    }

    private boolean acquireDaily(String host) {
        Map<LocalDate, Integer> perHost = dailyCounts.computeIfAbsent(host, k -> new ConcurrentHashMap<>());
        LocalDate today = dateSupplier.get();
        boolean[] allowed = {true};
        perHost.compute(today, (date, count) -> {
            if (count != null && count >= perHostDailyLimit) {
                allowed[0] = false;
                return count;
            }
            return count == null ? 1 : count + 1;
        });
        // 清理非当日键,避免 map 无界增长
        perHost.keySet().removeIf(d -> !d.equals(today));
        return allowed[0];
    }

    private void awaitHttpInterval(String host) {
        if (httpMinIntervalMs <= 0) {
            lastHttpCallMs.put(host, clockMs.getAsLong());
            return;
        }
        Object lock = httpLocks.computeIfAbsent(host, k -> new Object());
        synchronized (lock) {
            long now = clockMs.getAsLong();
            Long last = lastHttpCallMs.get(host);
            if (last != null) {
                long wait = httpMinIntervalMs - (now - last);
                if (wait > 0) {
                    sleeper.accept(wait);
                    now = clockMs.getAsLong();
                }
            }
            lastHttpCallMs.put(host, now);
        }
    }

    /** 从 URL 解析 host(小写);解析失败返回空串(不抛)。 */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
