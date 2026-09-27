package com.sparkora.config;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.mapper.EmbeddingModelStatsMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 向量模型对账 Runner 单测（09-27 R7）：仅验证不抛异常（异常查询仅 warn），
 * 以及非当前模型行不导致启动失败。
 */
class EmbeddingModelReconcileRunnerTest {

    /** 极简 stats mapper：按表返回可控行。 */
    static class FakeStatsMapper implements EmbeddingModelStatsMapper {
        List<Map<String, Object>> rows = List.of();
        boolean throwOnCar = false;

        @Override public List<Map<String, Object>> carModelStats() {
            if (throwOnCar) throw new RuntimeException("db down");
            return rows;
        }
        @Override public List<Map<String, Object>> kbModelStats() { return List.of(); }
        @Override public List<Map<String, Object>> newsModelStats() { return List.of(); }
        @Override public List<Map<String, Object>> imageModelStats() { return List.of(); }
    }

    private static EmbeddingClient client(String model) {
        AiProperties p = new AiProperties();
        p.setEmbeddingModel(model);
        return new EmbeddingClient(p);
    }

    @Test
    void 存在非当前模型_仅告警不抛() {
        FakeStatsMapper stats = new FakeStatsMapper();
        stats.rows = List.of(Map.of("model", "old-embed", "cnt", 100L),
                Map.of("model", "current-embed", "cnt", 5L));
        EmbeddingModelReconcileRunner runner = new EmbeddingModelReconcileRunner(client("current-embed"), stats);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void 查询异常_不阻断() {
        FakeStatsMapper stats = new FakeStatsMapper();
        stats.throwOnCar = true;
        EmbeddingModelReconcileRunner runner = new EmbeddingModelReconcileRunner(client("current-embed"), stats);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void 全为当前模型_正常() {
        FakeStatsMapper stats = new FakeStatsMapper();
        java.util.Map<String, Object> nullModel = new java.util.HashMap<>();
        nullModel.put("model", null);
        nullModel.put("cnt", 2L);
        stats.rows = List.of(Map.of("model", "current-embed", "cnt", 10L), nullModel);
        EmbeddingModelReconcileRunner runner = new EmbeddingModelReconcileRunner(client("current-embed"), stats);
        assertDoesNotThrow(() -> runner.run(null));
    }
}
