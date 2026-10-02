package com.sparkora.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AiObservabilityAdvisor} 指标落点单测（C1 AC-观测）。
 *
 * <p>用真实 {@link SimpleMeterRegistry} 断言 advisor 确实产生 token/时延/失败指标（此前只落日志、无断言）。
 */
class AiObservabilityAdvisorTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(registry);
        return p;
    }

    private static ChatClientResponse okResponse(int totalTokens) {
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("ok"))))
                .metadata(ChatResponseMetadata.builder()
                        .model("test-model")
                        .usage(new DefaultUsage(totalTokens, totalTokens, totalTokens))
                        .build())
                .build();
        return ChatClientResponse.builder().chatResponse(chatResponse).build();
    }

    private static CallAdvisorChain chainReturning(ChatClientResponse response) {
        return new CallAdvisorChain() {
            @Override public ChatClientResponse nextCall(ChatClientRequest request) { return response; }
            @Override public List<CallAdvisor> getCallAdvisors() { return List.of(); }
            @Override public CallAdvisorChain copy(CallAdvisor advisor) { return this; }
        };
    }

    private static CallAdvisorChain chainThrowing(RuntimeException e) {
        return new CallAdvisorChain() {
            @Override public ChatClientResponse nextCall(ChatClientRequest request) { throw e; }
            @Override public List<CallAdvisor> getCallAdvisors() { return List.of(); }
            @Override public CallAdvisorChain copy(CallAdvisor advisor) { return this; }
        };
    }

    @Test
    void 成功调用记录时延与token指标() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiObservabilityAdvisor advisor = new AiObservabilityAdvisor(provider(registry));

        advisor.adviseCall(null, chainReturning(okResponse(37)));

        assertEquals(1L, registry.find("ai.chat.latency").timers().size(),
                "必须登记 ai.chat.latency 计时器");
        assertEquals(37.0, registry.counter("ai.chat.tokens", "kind", "total").count(),
                "token 计数须等于 usage.totalTokens");
    }

    @Test
    void 失败调用记录失败指标并向上抛() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiObservabilityAdvisor advisor = new AiObservabilityAdvisor(provider(registry));

        assertThrows(RuntimeException.class,
                () -> advisor.adviseCall(null, chainThrowing(new IllegalStateException("boom"))));

        assertEquals(1.0, registry.counter("ai.chat.failures").count(), "必须登记 ai.chat.failures");
    }

    @Test
    void 无MeterRegistry时只记日志不抛() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        AiObservabilityAdvisor advisor = new AiObservabilityAdvisor(none);

        advisor.adviseCall(null, chainReturning(okResponse(5)));
    }

    @Test
    void 名称与顺序契约() {
        AiObservabilityAdvisor advisor = new AiObservabilityAdvisor(provider(new SimpleMeterRegistry()));
        assertEquals("aiObservability", advisor.getName());
        assertEquals(Ordered.LOWEST_PRECEDENCE, advisor.getOrder());
    }
}
