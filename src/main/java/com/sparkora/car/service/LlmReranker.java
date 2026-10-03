package com.sparkora.car.service;

import com.sparkora.ai.AiClient;
import com.sparkora.ai.PromptTemplateLoader;
import com.sparkora.ai.RerankOrderDto;
import com.sparkora.config.AiProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 重排实现（10-03-a-rerank，design §3.1）。
 *
 * <p>用 {@link AiClient#structured}（DTO schema 单一派生 + 结构化低温，复用 axonhub 同 key）让模型
 * 回吐「候选序号的相关性降序」。**只改顺序、不改分数**——调用方后续的分数门槛与四态判定不受影响。
 *
 * <p><b>参与集选择</b>：取候选**按原始分数降序的前 {@code keepTopN} 个**参与重排（简单可测策略），
 * 其余候选保持原序追加到结果末尾。NEWS 域无独立窗口——统一走该全局 top-N 策略（design §3.1 允许的
 * 两种简单策略之一），避免 token 爆炸。调用方（{@code CarRagService}）会据返回列表的**顺序**驱动
 * 配额选择与最终排序（见其 rank 映射），故本方法只需保证「集合元素一一对应、仅顺序变化」。
 *
 * <p><b>best-effort 硬约束</b>：order 为空 / 越界·重复·缺项经后验校验修正 / 超时 / 调用异常 →
 * 一律**原序返回 + warn**，绝不抛出、绝不阻断生成。超时用独立 {@code ragRerankTimeoutMs}
 * （默认 10s，比全局 {@code AI_TIMEOUT_MS} 短）在独立虚拟线程上兜底，避免可选优化拖垮同步生成链路。
 */
@Slf4j
@Component
public class LlmReranker implements Reranker {

    /**
     * 重排输出仅需候选序号，但 reasoning 模型会把预算先烧在推理上，故给足（4096）；
     * 仍截断 → {@code structured} 抛 AiException → 回退原序（best-effort，不重试）。
     */
    private static final int MAX_TOKENS = 4096;

    /** 单条候选注入 prompt 的文本摘要上限（控制 token；重排只需判别相关性，不需全文）。 */
    private static final int CANDIDATE_TEXT_MAX = 300;

    private final AiClient aiClient;
    private final AiProperties props;

    /** 超时兜底线程池（Java 21 虚拟线程，创建成本低；随 bean 生命周期）。 */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LlmReranker(AiClient aiClient, AiProperties props) {
        this.aiClient = aiClient;
        this.props = props;
    }

    @Override
    public List<CarRagService.UnifiedHit> rerank(String query, List<CarRagService.UnifiedHit> candidates, int keepTopN) {
        if (candidates == null || candidates.size() <= 1) return candidates == null ? List.of() : candidates;
        int n = Math.min(Math.max(keepTopN, 0), candidates.size());
        if (n <= 1) return candidates;
        // 参与集 = 按原始分数降序的前 n 个(下标定位,保证与输入一一对应——即便文本重复也不丢/不重);
        // 其余候选保持原序追加到末尾。
        List<Integer> headIdx = topIndices(candidates, n);
        boolean[] inHead = new boolean[candidates.size()];
        for (int i : headIdx) inHead[i] = true;
        List<CarRagService.UnifiedHit> head = new ArrayList<>(n);
        for (int i : headIdx) head.add(candidates.get(i));
        List<CarRagService.UnifiedHit> tail = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!inHead[i]) tail.add(candidates.get(i));
        }
        try {
            long timeoutMs = Math.max(1, props.getRagRerankTimeoutMs());
            Future<RerankOrderDto> f = executor.submit(() -> callModel(query, head));
            RerankOrderDto dto;
            try {
                dto = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                f.cancel(true);
                log.warn("LLM 重排超时({}ms),回退原序 keepTopN={} candidates={}", timeoutMs, n, candidates.size());
                return candidates;
            }
            List<CarRagService.UnifiedHit> reordered = applyOrder(head, tail, dto == null ? null : dto.getOrder());
            return reordered == null ? candidates : reordered;
        } catch (Exception e) {
            // 任何失败(异常/中断/超时/不可解析)一律降级回原序:warn 不含异常原文(可能含密钥/URL)
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();   // 复位中断位,勿吞
            log.warn("LLM 重排失败,回退原序 candidates={}: {}", candidates.size(), e.getClass().getSimpleName());
            return candidates;
        }
    }

    /** 按原始分数降序取前 n 的**下标**(稳定:同分保持原相对顺序)。 */
    static List<Integer> topIndices(List<CarRagService.UnifiedHit> candidates, int n) {
        List<Integer> idx = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) idx.add(i);
        idx.sort((a, b) -> Double.compare(candidates.get(b).score(), candidates.get(a).score()));
        return new ArrayList<>(idx.subList(0, Math.min(n, idx.size())));
    }

    /** 调模型：DTO schema 派生 + 结构化低温；由调用方在超时线程内执行。 */
    private RerankOrderDto callModel(String query, List<CarRagService.UnifiedHit> head) {
        StringBuilder cand = new StringBuilder();
        for (int i = 0; i < head.size(); i++) {
            CarRagService.UnifiedHit h = head.get(i);
            cand.append('[').append(i).append("] ").append("来源=").append(h.source()).append(' ')
                .append("文本=").append(abbreviate(h.chunkText())).append('\n');
        }
        String system = PromptTemplateLoader.render("rag/rerank-system.st", Map.of(
                "schema", AiClient.jsonSchema(RerankOrderDto.class),
                "query", query == null ? "" : query,
                "candidates", cand.toString()));
        return aiClient.structured(system, "请按上述规则输出重排后的 JSON 对象。", MAX_TOKENS, RerankOrderDto.class).entity();
    }

    /**
     * 后验校验 LLM 返回的 order：
     * <ul>
     *   <li>越界下标/重复下标丢弃；</li>
     *   <li>未出现的 head 下标按原序补到末尾；</li>
     *   <li>tail（未参与重排的候选）保持原序追加在最后。</li>
     * </ul>
     * 保证返回集合与输入集合元素一一对应，仅顺序变化。order 为空/全非法 → 返回 null（调用方回退原序）。
     */
    static List<CarRagService.UnifiedHit> applyOrder(List<CarRagService.UnifiedHit> head,
                                                     List<CarRagService.UnifiedHit> tail,
                                                     List<Integer> order) {
        if (order == null || order.isEmpty()) return null;
        int n = head.size();
        boolean[] used = new boolean[n];
        List<CarRagService.UnifiedHit> out = new ArrayList<>(n + tail.size());
        for (Integer idx : order) {
            if (idx == null || idx < 0 || idx >= n || used[idx]) continue;
            used[idx] = true;
            out.add(head.get(idx));
        }
        if (out.isEmpty()) return null;   // 全部越界/重复 → 无有效重排信息
        // 未出现的 head 按原序补到末尾（保证「集合不变、仅顺序变」）
        for (int i = 0; i < n; i++) {
            if (!used[i]) out.add(head.get(i));
        }
        out.addAll(tail);
        return out;
    }

    /** 单条候选文本摘要：压空白 + 截断（防注入 prompt 超长）。 */
    private static String abbreviate(String s) {
        if (s == null) return "";
        String v = s.replaceAll("\\s+", " ").trim();
        return v.length() > CANDIDATE_TEXT_MAX ? v.substring(0, CANDIDATE_TEXT_MAX) + "…" : v;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
