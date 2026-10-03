package com.sparkora.car.client;

import com.sparkora.config.AiProperties;
import com.sparkora.mapper.EmbeddingCacheMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容寻址嵌入缓存单测（10-03 E5）。
 *
 * <p>覆盖 AC：相同文本重复入库只 1 次网络调用；换模型缓存 miss 不复用；缓存未注入时退化直调。
 * 用计数 stub + 内存假 mapper，不连 PG、不发起 HTTP。
 */
class EmbeddingClientCacheTest {

    /** 内存假缓存 mapper（不连库）。 */
    static class FakeCacheMapper implements EmbeddingCacheMapper {
        final Map<String, String> store = new HashMap<>();
        int inserts = 0;

        @Override public String selectByHashModel(String contentHash, String embeddingModel) {
            return store.get(contentHash + "|" + embeddingModel);
        }

        @Override public int insertIgnore(String contentHash, String embeddingModel, String embedding) {
            inserts++;
            store.putIfAbsent(contentHash + "|" + embeddingModel, embedding);
            return 1;
        }
    }

    /** 计数 embedding 客户端：embed 计数，不发起 HTTP。 */
    static class CountingEmbeddingClient extends EmbeddingClient {
        int calls = 0;

        CountingEmbeddingClient(AiProperties props, float[] vec) {
            super(props, new StubModel(vec));
        }

        @Override public String embed(String text) {
            calls++;
            return "[" + text + "]";
        }
    }

    static class StubModel implements EmbeddingModel {
        final float[] vec;
        StubModel(float[] v) { this.vec = v; }
        @Override public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
        @Override public float[] embed(Document document) { return vec; }
        @Override public float[] embed(String text) { return vec; }
    }

    private static AiProperties props(String model) {
        AiProperties p = new AiProperties();
        p.setEmbeddingModel(model);
        p.setEmbeddingDim(3);
        return p;
    }

    @Test
    void 相同文本重复_只网络调用一次_第二次命中缓存() {
        AiProperties p = props("m1");
        CountingEmbeddingClient client = new CountingEmbeddingClient(p, new float[]{1, 2, 3});
        client.setEmbeddingCache(new EmbeddingCacheService(new FakeCacheMapper()));

        String first = client.embedForIndex("同一段文本");
        String second = client.embedForIndex("同一段文本");

        assertEquals("[同一段文本]", first);
        assertEquals(first, second);
        assertEquals(1, client.calls, "相同文本第二次必须命中缓存，不再网络调用");
    }

    @Test
    void 换模型_缓存miss_不复用旧模型向量() {
        AiProperties p = props("m1");
        CountingEmbeddingClient client = new CountingEmbeddingClient(p, new float[]{1, 2, 3});
        client.setEmbeddingCache(new EmbeddingCacheService(new FakeCacheMapper()));

        client.embedForIndex("同一段文本");
        p.setEmbeddingModel("m2");                 // 运行时换模型
        client.embedForIndex("同一段文本");

        assertEquals(2, client.calls, "键含 embedding_model，换模型必须 miss 重算");
    }

    @Test
    void 缓存未注入_退化直调embed_行为不变() {
        CountingEmbeddingClient client = new CountingEmbeddingClient(props("m1"), new float[]{1, 2, 3});
        // 不注入缓存
        String v = client.embedForIndex("x");
        assertEquals("[x]", v);
        assertEquals(1, client.calls);
    }

    @Test
    void 不同文本_各自网络调用并写缓存() {
        FakeCacheMapper mapper = new FakeCacheMapper();
        CountingEmbeddingClient client = new CountingEmbeddingClient(props("m1"), new float[]{1, 2, 3});
        client.setEmbeddingCache(new EmbeddingCacheService(mapper));

        client.embedForIndex("aaa");
        client.embedForIndex("bbb");

        assertEquals(2, client.calls);
        assertEquals(2, mapper.inserts);
    }

    @Test
    void 查询用embed_无缓存_每次网络调用() {
        AiProperties p = props("m1");
        CountingEmbeddingClient client = new CountingEmbeddingClient(p, new float[]{1, 2, 3});
        client.setEmbeddingCache(new EmbeddingCacheService(new FakeCacheMapper()));

        client.embed("同一段文本");
        client.embed("同一段文本");

        assertEquals(2, client.calls, "查询路径 embed 保持无缓存");
    }

    @Test
    void sha256_稳定且长度64() {
        String h = EmbeddingClient.sha256("hello");
        assertEquals(h, EmbeddingClient.sha256("hello"));
        assertEquals(64, h.length());
        assertNotEquals(h, EmbeddingClient.sha256("hello!"));
        assertTrue(h.matches("[0-9a-f]{64}"));
    }
}
