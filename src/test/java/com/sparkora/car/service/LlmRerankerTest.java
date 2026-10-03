package com.sparkora.car.service;

import com.sparkora.ai.AiClient;
import com.sparkora.ai.AiException;
import com.sparkora.ai.RerankOrderDto;
import com.sparkora.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A rerank（10-03-a-rerank）单测：LLM 重排的正常路径 + 后验校验（越界/重复/缺项集合不变）+ 失败/超时/空
 * order 一律回退原序。不连真实 AI——用覆写 {@link AiClient#structured} 的假实现。
 */
class LlmRerankerTest {

    private static CarRagService.UnifiedHit hit(String text, double score) {
        return new CarRagService.UnifiedHit(text, "PARAM_GROUP", score, "CAR", 1L, "车型", 1L);
    }

    /** 假 AiClient：structured 返回预设 DTO（或抛异常/睡眠模拟超时）。 */
    static class FakeAiClient extends AiClient {
        RerankOrderDto dto;
        RuntimeException error;
        long sleepMs;

        FakeAiClient() { super(new AiProperties()); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> TypedResult<T> structured(String system, String user, int maxTokens, Class<T> type) {
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (error != null) throw error;
            return new TypedResult<>((T) dto, new ChatResult("{}", "m", 0));
        }
    }

    private LlmReranker newReranker(AiClient ai) {
        AiProperties p = new AiProperties();
        p.setRagRerankTimeoutMs(2000);
        return new LlmReranker(ai, p);
    }

    private static RerankOrderDto order(Integer... idx) {
        RerankOrderDto d = new RerankOrderDto();
        d.setOrder(new ArrayList<>(List.of(idx)));
        return d;
    }

    @Test
    void 正常重排_按order顺序返回() {
        FakeAiClient ai = new FakeAiClient();
        ai.dto = order(2, 0, 1);
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8), hit("c", 0.7));

        List<CarRagService.UnifiedHit> out = newReranker(ai).rerank("q", in, 20);

        assertEquals(List.of("c", "a", "b"), out.stream().map(CarRagService.UnifiedHit::chunkText).toList());
        assertEquals(3, out.size());
    }

    @Test
    void order含越界与重复_过滤后集合不变_缺项按原序补尾() {
        // order 含越界 9、重复 0；缺 2 → 2 补尾。期望 [b(1), a(0), c(2)]
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8), hit("c", 0.7));
        List<CarRagService.UnifiedHit> out = LlmReranker.applyOrder(in, List.of(), List.of(1, 9, 0, 0));

        assertEquals(3, out.size(), "集合元素必须一一对应(仅顺序变)");
        assertEquals(List.of("b", "a", "c"), out.stream().map(CarRagService.UnifiedHit::chunkText).toList());
        assertEquals(0.9, out.get(1).score(), 1e-9, "重排不得改分数");
    }

    @Test
    void order全非法_返回null以示无有效重排() {
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8));
        assertEquals(null, LlmReranker.applyOrder(in, List.of(), List.of(5, 6)));
        assertEquals(null, LlmReranker.applyOrder(in, List.of(), List.of()));
        assertEquals(null, LlmReranker.applyOrder(in, List.of(), null));
    }

    @Test
    void tail未参与重排_原序追加末尾() {
        List<CarRagService.UnifiedHit> head = List.of(hit("a", 0.9), hit("b", 0.8));
        List<CarRagService.UnifiedHit> tail = List.of(hit("x", 0.1), hit("y", 0.05));
        List<CarRagService.UnifiedHit> out = LlmReranker.applyOrder(head, tail, List.of(1, 0));
        assertEquals(List.of("b", "a", "x", "y"), out.stream().map(CarRagService.UnifiedHit::chunkText).toList());
    }

    @Test
    void LLM异常_降级回原序() {
        FakeAiClient ai = new FakeAiClient();
        ai.error = new AiException("boom", null);
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8), hit("c", 0.7));

        List<CarRagService.UnifiedHit> out = newReranker(ai).rerank("q", in, 20);

        assertEquals(in, out, "失败必须原样返回原序,绝不抛出");
    }

    @Test
    void 空order_降级回原序() {
        FakeAiClient ai = new FakeAiClient();
        ai.dto = order();   // 空
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8));

        List<CarRagService.UnifiedHit> out = newReranker(ai).rerank("q", in, 20);

        assertEquals(in, out);
    }

    @Test
    void 返回null_降级回原序() {
        FakeAiClient ai = new FakeAiClient();
        ai.dto = null;
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8));
        assertEquals(in, newReranker(ai).rerank("q", in, 20));
    }

    @Test
    void 超时_降级回原序_不抛出不阻断() {
        FakeAiClient ai = new FakeAiClient();
        ai.dto = order(1, 0);
        ai.sleepMs = 500;   // 超过 10ms 预算
        AiProperties p = new AiProperties();
        p.setRagRerankTimeoutMs(10);
        LlmReranker r = new LlmReranker(ai, p);
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.9), hit("b", 0.8));

        List<CarRagService.UnifiedHit> out = r.rerank("q", in, 20);

        assertEquals(in, out, "超时必须回退原序");
    }

    @Test
    void 候选数不超过1_原样返回不调模型() {
        FakeAiClient ai = new FakeAiClient();
        ai.error = new AiException("不应被调用", null);
        List<CarRagService.UnifiedHit> one = List.of(hit("a", 0.9));
        assertSame(one, newReranker(ai).rerank("q", one, 20));
        assertTrue(newReranker(ai).rerank("q", List.of(), 20).isEmpty());
    }

    @Test
    void keepTopN限制参与集_topIndices取前N() {
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.5), hit("b", 0.9), hit("c", 0.7), hit("d", 0.6));
        List<Integer> top = LlmReranker.topIndices(in, 2);
        assertEquals(List.of(1, 2), top, "按分数降序取前 2 的下标(0.9,0.7)");
    }

    @Test
    void keepTopN_仅前N参与_其余原序追加() {
        FakeAiClient ai = new FakeAiClient();
        // 参与集 top2 = [b(0.9), c(0.7)]；order [1,0] → [c,b]；tail 原序 = [a(0.5), d(0.6)]
        ai.dto = order(1, 0);
        List<CarRagService.UnifiedHit> in = List.of(hit("a", 0.5), hit("b", 0.9), hit("c", 0.7), hit("d", 0.6));

        List<CarRagService.UnifiedHit> out = newReranker(ai).rerank("q", in, 2);

        assertEquals(List.of("c", "b", "a", "d"), out.stream().map(CarRagService.UnifiedHit::chunkText).toList());
        assertEquals(4, out.size(), "集合元素不变");
    }
}
