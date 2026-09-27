package com.sparkora.ai;

import com.sparkora.car.client.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 并发批量嵌入执行器（泛型，09-27 知识域写入侧统一）。
 *
 * <p>CAR（车型）与 NEWS（新闻）原本各写一份「固定小线程池 + 单块重试 1 次 + 失败收集 + 计数日志」模板；
 * 本类收敛为唯一实现。调用方只提供：
 * <ul>
 *   <li>{@code textFn}：条目 → 嵌入文本（网络调用前的纯计算）；</li>
 *   <li>{@code persistFn}：条目 + 向量 → 持久化（**应为 REQUIRES_NEW 独立事务方法**，
 *       由调用方的自注入代理捕获，见各域 persistXxx）。</li>
 * </ul>
 * embedding 网络调用在事务之外（{@link EmbeddingClient#embed} 在 persistFn 之前执行），
 * 持久化失败回滚不产生孤儿块、也不污染调用方事务。
 *
 * <p>各域失败策略通过参数保留：CAR/NEWS = {@code maxParallel=4,maxRetries=1}；
 * KB = {@code maxParallel=1,maxRetries=0}（串行、无重试，语义不变）。
 */
@Slf4j
@Component
public class EmbeddingBatchRunner {

    private final EmbeddingClient embeddingClient;

    public EmbeddingBatchRunner(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    /**
     * 批量嵌入并持久化。
     *
     * @param items       待处理条目（可空）
     * @param textFn      条目 → 嵌入文本
     * @param persistFn   条目 + 向量 → 独立事务持久化（调用方绑定 self 代理）
     * @param label       日志标签（如 "model=39" / "newsId=1688" / "docId=5"）
     * @param maxParallel 最大并发（1 = 顺序执行，省线程池）
     * @param maxRetries  单条失败重试次数（0 = 不重试）
     * @return total/success/failed 计数
     */
    public <T> EmbedStats run(List<T> items, Function<T, String> textFn,
                              BiConsumer<T, String> persistFn,
                              String label, int maxParallel, int maxRetries) {
        List<T> list = items == null ? List.of() : items;
        int total = list.size();
        if (total == 0) return new EmbedStats(0, 0, 0);

        AtomicInteger okCount = new AtomicInteger();
        List<Integer> failedIdx = Collections.synchronizedList(new ArrayList<>());

        if (maxParallel <= 1) {
            // 串行路径（KB 语义；省去线程池开销，且顺序与条目一致）
            for (int i = 0; i < total; i++) {
                if (processOne(list.get(i), i, textFn, persistFn, label, maxRetries)) okCount.incrementAndGet();
                else failedIdx.add(i);
            }
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(maxParallel, Math.max(1, total)));
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int idx = i;
                tasks.add(() -> processOne(list.get(idx), idx, textFn, persistFn, label, maxRetries));
            }
            List<java.util.concurrent.Future<Boolean>> futures;
            try {
                futures = pool.invokeAll(tasks);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures = List.of();
            } finally {
                pool.shutdown();
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    if (Boolean.TRUE.equals(futures.get(i).get())) okCount.incrementAndGet();
                    else failedIdx.add(i);
                } catch (Exception e) {
                    failedIdx.add(i);
                }
            }
            // 中断导致未取到结果时，剩余条目计失败（不静默漏计）
            if (futures.size() < total) {
                for (int i = futures.size(); i < total; i++) failedIdx.add(i);
            }
        }

        int ok = okCount.get();
        int failed = failedIdx.size();
        if (failed > 0) {
            log.warn("{} 向量重建完成(有缺失) 成功 {}/{} 失败 idx={}", label, ok, total, failedIdx);
        } else {
            log.info("{} 向量重建完成 成功 {}/{}", label, ok, total);
        }
        return new EmbedStats(total, ok, failed);
    }

    /** 处理单条（含重试）：embed（事务外）→ persistFn（REQUIRES_NEW）。失败返回 false。 */
    private <T> boolean processOne(T item, int idx, Function<T, String> textFn,
                                   BiConsumer<T, String> persistFn, String label, int maxRetries) {
        int retries = Math.max(0, maxRetries);
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                String vec = embeddingClient.embed(textFn.apply(item));   // 网络调用放在事务外
                persistFn.accept(item, vec);
                return true;
            } catch (Exception e) {
                if (attempt < retries) {
                    log.warn("{} 向量化失败将重试 idx={} err={}", label, idx, e.getMessage());
                } else {
                    // 已无重试额度：maxRetries=0 时措辞不带「已重试」，避免误导排查
                    log.warn("{} 向量化失败{}(最终) idx={} err={}", label,
                            retries > 0 ? "(已重试 " + retries + " 次)" : "", idx, e.getMessage());
                }
            }
        }
        return false;
    }
}
