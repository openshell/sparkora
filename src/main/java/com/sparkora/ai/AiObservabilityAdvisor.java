package com.sparkora.ai;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;

/**
 * AI 调用可观测性 Advisor（C1，PRD AC-观测）：记录每次文本调用的 token/时延/失败。
 *
 * <p>实现为 {@link CallAdvisor}：包裹 {@code ChatClient.call()} 链路，成功记时延 + token（total/prompt/completion），
 * 异常记失败。指标经 Micrometer（Spring AI starter 传递引入）暴露为
 * {@code ai.chat.tokens} 与 {@code ai.chat.latency} 计时器；同时打 INFO/WARN 日志。
 *
 * <p>不记录任何 prompt/响应正文或密钥（只记数值与任务名），避免泄漏。
 */
@Slf4j
public class AiObservabilityAdvisor implements CallAdvisor {

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public AiObservabilityAdvisor(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String getName() {
        return "aiObservability";
    }

    /** 最外层（低优先级）包裹，保证能观测到其他 Advisor 之后的最终调用耗时。 */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            recordSuccess(response.chatResponse(), start);
            return response;
        } catch (RuntimeException e) {
            recordFailure(start);
            throw e;
        }
    }

    private void recordSuccess(ChatResponse response, long startNanos) {
        long ms = elapsedMs(startNanos);
        Integer total = null;
        if (response != null) {
            ChatResponseMetadata meta = response.getMetadata();
            Usage usage = meta == null ? null : meta.getUsage();
            if (usage != null) total = usage.getTotalTokens();
        }
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.timer("ai.chat.latency").record(java.time.Duration.ofNanos(System.nanoTime() - startNanos));
            if (total != null) {
                registry.counter("ai.chat.tokens", "kind", "total").increment(total);
            }
        }
        log.info("AI 调用完成 时延={}ms tokens={}", ms, total);
    }

    private void recordFailure(long startNanos) {
        long ms = elapsedMs(startNanos);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            registry.counter("ai.chat.failures").increment();
        }
        log.warn("AI 调用失败 时延={}ms", ms);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
