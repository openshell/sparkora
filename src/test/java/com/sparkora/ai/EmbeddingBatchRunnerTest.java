package com.sparkora.ai;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddingBatchRunner 单测（09-27 知识域写入侧统一）。
 * 覆盖：单条失败重试 1 次成功 / 两次失败计 failed / maxRetries=0 不重试 /
 * maxParallel=1 顺序执行 / 计数正确 / 失败不调用持久化。
 */
class EmbeddingBatchRunnerTest {

    /** 可注入失败的 embedding 客户端假件（不发起 HTTP）。 */
    static class FlakyEmbeddingClient extends EmbeddingClient {
        final Map<String, Integer> calls = new ConcurrentHashMap<>();
        final Map<String, Integer> failFirstN = new ConcurrentHashMap<>();

        FlakyEmbeddingClient() { super(new AiProperties()); }

        @Override
        public String embed(String text) {
            int n = calls.merge(text, 1, Integer::sum);
            if (n <= failFirstN.getOrDefault(text, 0)) throw new RuntimeException("boom");
            return "[" + text + "]";
        }
    }

    @Test
    void 首次失败重试成功_计成功且重试调用两次() {
        FlakyEmbeddingClient client = new FlakyEmbeddingClient();
        client.failFirstN.put("a", 1);   // a 首次失败、重试成功
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(client);
        List<String> persisted = new CopyOnWriteArrayList<>();

        EmbedStats st = runner.run(List.of("a", "b", "c"),
                t -> t, (t, vec) -> persisted.add(t + vec), "test", 4, 1);

        assertEquals(3, st.total());
        assertEquals(3, st.success());
        assertEquals(0, st.failed());
        assertEquals(2, client.calls.get("a"), "a 应被调用两次(首次失败+重试)");
        assertTrue(persisted.contains("a[a]"), "重试成功后应持久化为向量 [a]: " + persisted);
    }

    @Test
    void 两次均失败_计failed且不持久化该条() {
        FlakyEmbeddingClient client = new FlakyEmbeddingClient();
        client.failFirstN.put("a", 2);   // a 首次+重试均失败
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(client);
        List<String> persisted = new CopyOnWriteArrayList<>();

        EmbedStats st = runner.run(List.of("a", "b"),
                t -> t, (t, vec) -> persisted.add(t), "test", 4, 1);

        assertEquals(2, st.total());
        assertEquals(1, st.success());
        assertEquals(1, st.failed());
        assertEquals(2, client.calls.get("a"));
        assertTrue(!persisted.contains("a"), "失败条不得持久化: " + persisted);
    }

    @Test
    void maxRetries为0_失败不重试() {
        FlakyEmbeddingClient client = new FlakyEmbeddingClient();
        client.failFirstN.put("a", 1);   // 无重试 → 直接失败
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(client);

        EmbedStats st = runner.run(List.of("a"), t -> t, (t, vec) -> {}, "test", 1, 0);

        assertEquals(1, st.failed());
        assertEquals(1, client.calls.get("a"), "maxRetries=0 时只调用一次");
    }

    @Test
    void maxParallel为1_顺序执行且持久化顺序与输入一致() {
        FlakyEmbeddingClient client = new FlakyEmbeddingClient();
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(client);
        List<String> order = new ArrayList<>();

        EmbedStats st = runner.run(List.of("a", "b", "c", "d"),
                t -> t, (t, vec) -> order.add(t), "test", 1, 0);

        assertEquals(4, st.success());
        assertEquals(List.of("a", "b", "c", "d"), order, "串行路径须按输入顺序持久化");
    }

    @Test
    void 空列表_零计数() {
        EmbeddingBatchRunner runner = new EmbeddingBatchRunner(new FlakyEmbeddingClient());
        EmbedStats st = runner.run(List.<String>of(),
                (String t) -> t, (String t, String vec) -> {}, "test", 4, 1);
        assertEquals(0, st.total());
        assertEquals(0, st.success());
        assertEquals(0, st.failed());
    }
}
