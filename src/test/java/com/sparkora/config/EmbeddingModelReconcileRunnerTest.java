package com.sparkora.config;

import com.sparkora.ai.vector.VectorStoreService;
import com.sparkora.car.client.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 向量模型对账 Runner 单测（09-27 R7；10-03 E6 改查单表 store）：仅验证不抛异常（异常查询仅 warn），
 * 以及非当前模型行不导致启动失败。
 */
class EmbeddingModelReconcileRunnerTest {

    private static EmbeddingClient client(String model) {
        AiProperties p = new AiProperties();
        p.setEmbeddingModel(model);
        return new EmbeddingClient(p);
    }

    @Test
    void 存在非当前模型_仅告警不抛() {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.embeddingModelStats()).thenReturn(List.of(
                Map.of("domain", "CAR", "model", "old-embed", "cnt", 100L),
                Map.of("domain", "KB", "model", "current-embed", "cnt", 5L)));
        EmbeddingModelReconcileRunner runner = new EmbeddingModelReconcileRunner(client("current-embed"), store);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void 查询异常_不阻断() {
        VectorStoreService store = mock(VectorStoreService.class);
        when(store.embeddingModelStats()).thenThrow(new RuntimeException("db down"));
        EmbeddingModelReconcileRunner runner = new EmbeddingModelReconcileRunner(client("current-embed"), store);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void 全为当前模型_正常() {
        VectorStoreService store = mock(VectorStoreService.class);
        java.util.Map<String, Object> nullModel = new java.util.HashMap<>();
        nullModel.put("domain", "IMAGE");
        nullModel.put("model", null);
        nullModel.put("cnt", 2L);
        when(store.embeddingModelStats()).thenReturn(List.of(
                Map.of("domain", "NEWS", "model", "current-embed", "cnt", 10L), nullModel));
        EmbeddingModelReconcileRunner runner = new EmbeddingModelReconcileRunner(client("current-embed"), store);
        assertDoesNotThrow(() -> runner.run(null));
    }
}
