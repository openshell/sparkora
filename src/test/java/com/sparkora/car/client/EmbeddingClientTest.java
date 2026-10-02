package com.sparkora.car.client;

import com.sparkora.ai.AiException;
import com.sparkora.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EmbeddingClient 维度校验单测（C5：后端改由 Spring AI {@link EmbeddingModel} 实现）。
 * 以 stub EmbeddingModel 返回指定长度向量，断言：
 *  - 维度不等于配置 embeddingDim → 抛 AiException（中文提示含实际/期望维度/模型）；
 *  - 维度相符 → 正常返回 pgvector 字面量；
 *  - modelName() 返回配置模型；
 *  - 未注入 EmbeddingModel（兼容构造）调用 → 抛 AiException。
 */
class EmbeddingClientTest {

    /** 最小 EmbeddingModel stub：仅实现 embed(String) 与两个抽象方法。 */
    static class StubEmbeddingModel implements EmbeddingModel {
        final float[] vec;
        StubEmbeddingModel(float[] vec) { this.vec = vec; }

        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("stub");
        }
        @Override public float[] embed(Document document) { return vec; }
        @Override public float[] embed(String text) { return vec; }
    }

    private static float[] vec(int n) {
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = i * 0.1f;
        return v;
    }

    private static EmbeddingClient client(int dim, float[] v) {
        AiProperties p = new AiProperties();
        p.setApiKey("k");
        p.setEmbeddingModel("test-embed");
        p.setEmbeddingDim(dim);
        return new EmbeddingClient(p, new StubEmbeddingModel(v));
    }

    @Test
    void 维度相符_正常返回pgvector字面量() {
        EmbeddingClient c = client(3, vec(3));
        assertEquals(3, c.embedList("x").size());
        String pg = c.embed("x");
        assertTrue(pg.startsWith("[") && pg.endsWith("]"), pg);
    }

    @Test
    void 维度不符_抛AiException含实际与期望() {
        EmbeddingClient c = client(1024, vec(3));
        AiException ex = assertThrows(AiException.class, () -> c.embedList("x"));
        assertTrue(ex.getMessage().contains("维度不符"), ex.getMessage());
        assertTrue(ex.getMessage().contains("期望 1024"), ex.getMessage());
        assertTrue(ex.getMessage().contains("实际 3"), ex.getMessage());
        assertTrue(ex.getMessage().contains("test-embed"), ex.getMessage());
    }

    @Test
    void 未注入EmbeddingModel_兼容构造调用抛异常() {
        AiProperties p = new AiProperties();
        p.setEmbeddingModel("test-embed");
        p.setEmbeddingDim(1024);
        EmbeddingClient c = new EmbeddingClient(p);
        AiException ex = assertThrows(AiException.class, () -> c.embedList("x"));
        assertTrue(ex.getMessage().contains("EmbeddingModel 未注入"), ex.getMessage());
    }

    @Test
    void 默认维度为1024() {
        assertEquals(1024, new AiProperties().getEmbeddingDim());
    }

    @Test
    void modelName返回配置模型() {
        assertEquals("test-embed", client(1024, vec(1024)).modelName());
    }
}
