package com.sparkora.car.service;

import com.sparkora.car.client.EmbeddingClient;
import com.sparkora.config.AiProperties;
import com.sparkora.mapper.CarDocEmbeddingMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6.1「必查+降级可见」门槛逻辑单测(纯 Mockito,不连 PG/embedding)。
 * 覆盖:OK / LOW_CONFIDENCE(整体抛弃,context 必须为空) / FAILED(异常不抛出) / NO_KNOWLEDGE(无对象)。
 */
class CarRagServiceTest {

    private CarRagService newService(FakeMapper mapper) {
        AiProperties props = new AiProperties(); // 默认 ragMinScore=0.3 / ragRejectScore=0.5
        return new CarRagService(mapper, new FakeEmbeddingClient(), props);
    }

    /** 手写 embedding 客户端假实现(固定返回合法向量字符串,不发起 HTTP)。 */
    static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() { super(new AiProperties()); }
        @Override
        public String embed(String query) { return "[0.1,0.2]"; }
    }

    /**
     * 手写 mapper 假实现(替代 Mockito mock:JDK21 动态代理下 stub 匹配不稳定)。
     * byModelId: modelId → 返回的检索行;byThrow: modelId → 抛出的异常。
     */
    static class FakeMapper implements CarDocEmbeddingMapper {
        final Map<Long, List<Map<String, Object>>> byModelId = new HashMap<>();
        final Map<Long, RuntimeException> byThrow = new HashMap<>();

        @Override
        public int insert(Long docId, Long modelId, String embedding) { return 0; }

        @Override
        public int deleteByDocId(Long docId) { return 0; }

        @Override
        public List<Map<String, Object>> searchTopK(Long modelId, String queryVec, int limit) {
            RuntimeException e = byThrow.get(modelId);
            if (e != null) throw e;
            return byModelId.getOrDefault(modelId, List.of());
        }
    }

    private static Map<String, Object> row(String text, double score) {
        Map<String, Object> m = new HashMap<>();
        m.put("chunkText", text);
        m.put("score", score);
        return m;
    }

    @Test
    void 命中且最高分过整体门槛_状态OK_上下文完整() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("大唐EV 续航 600km", 0.82), row("大唐EV 权益", 0.55)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "大唐EV 续航", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.82, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("600km"));
    }

    @Test
    void 有命中但最高分低于整体门槛_状态LOW_CONFIDENCE_上下文必须为空() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("完全无关内容", 0.45), row("也很无关", 0.38)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertEquals(CarRagService.RagStatus.LOW_CONFIDENCE, r.status());
        assertEquals("", r.context(), "低置信抛弃后不得把知识块注入 prompt");
        assertEquals(2, r.hitCount(), "hitCount 保留观测值");
        assertEquals(0.45, r.maxScore(), 1e-9);
    }

    @Test
    void 检索异常_不抛出_状态FAILED_上下文为空() {
        FakeMapper mapper = new FakeMapper();
        mapper.byThrow.put(1L, new RuntimeException("embedding down"));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L), "query", 8);
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
        assertEquals("", r.context());
    }

    @Test
    void 无车型对象_状态NO_KNOWLEDGE() {
        CarRagService svc = newService(new FakeMapper());
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(List.of(), "query", 8));
        assertEquals(CarRagService.RagResult.EMPTY, svc.retrieveForGeneration(null, "query", 8));
    }

    @Test
    void 多车型_其一失败_整体标FAILED_不得部分注入() {
        FakeMapper mapper = new FakeMapper();
        mapper.byThrow.put(1L, new RuntimeException("down"));
        mapper.byModelId.put(2L, List.of(row("高相关", 0.9)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L, 2L), "query", 8);
        assertEquals(CarRagService.RagStatus.FAILED, r.status());
        assertEquals("", r.context(), "任一车型失败即整体降级,不得部分注入");
    }

    @Test
    void 跨车型合并_分数与块数正确() {
        FakeMapper mapper = new FakeMapper();
        mapper.byModelId.put(1L, List.of(row("块一", 0.9)));
        mapper.byModelId.put(2L, List.of(row("块二", 0.7)));
        CarRagService svc = newService(mapper);

        CarRagService.RagResult r = svc.retrieveForGeneration(List.of(1L, 2L), "query", 8);
        assertEquals(CarRagService.RagStatus.OK, r.status());
        assertEquals(2, r.hitCount());
        assertEquals(0.9, r.maxScore(), 1e-9);
        assertTrue(r.context().contains("块一") && r.context().contains("块二"));
    }
}
