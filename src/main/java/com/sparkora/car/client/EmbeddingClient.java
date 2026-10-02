package com.sparkora.car.client;

import com.sparkora.ai.AiException;
import com.sparkora.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化客户端（C5：后端改由 Spring AI {@link EmbeddingModel} 实现，指向 axonhub OpenAI 兼容
 * {@code /v1/embeddings}）。实测模型 Qwen3-Embedding-8B，向量维度 1024。
 *
 * <p>公共 API 保持不变（{@link #embed(String)} / {@link #embedList(String)} /
 * {@link #modelName()} / {@link #toPgVector(List)}），使既有调用点与测试零改动；
 * 只是把「手写 RestClient 直调」换成框架 {@code EmbeddingModel}（含重试/观测等框架能力）。
 * 维度 fail-fast 校验（09-27）保留：不符立即抛 {@link AiException}，避免维度错位脏向量入库。
 */
@Slf4j
@Component
public class EmbeddingClient {

    private final AiProperties props;
    /** Spring AI 自动配置的向量模型（生产注入）；单测/兼容构造可为 null。 */
    private final EmbeddingModel embeddingModel;

    /** 生产装配：注入 Spring AI EmbeddingModel（由 spring-ai-starter-model-openai 自动配置）。 */
    @Autowired
    public EmbeddingClient(AiProperties props, EmbeddingModel embeddingModel) {
        this.props = props;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 兼容构造（单测/仅取 {@link #modelName()} 的场景）：不注入 EmbeddingModel；
     * 若调用 {@link #embedList} 会因未注入而抛 {@link AiException}。
     * 保留以使既有 {@code new EmbeddingClient(props)} 调用点（如对账 runner 测试）零改动。
     */
    public EmbeddingClient(AiProperties props) {
        this(props, null);
    }

    /**
     * 对单个文本生成向量，返回 pgvector 字面量字符串（如 "[0.1,0.2,...]"）。
     */
    public String embed(String text) {
        List<Double> vec = embedList(text);
        return toPgVector(vec);
    }

    /** 对单个文本生成向量，返回 double 列表。 */
    public List<Double> embedList(String text) {
        String model = props.getEmbeddingModel();
        if (model == null || model.isBlank()) throw new AiException("AI_EMBEDDING_MODEL 未配置", null);
        if (embeddingModel == null) throw new AiException("EmbeddingModel 未注入（Spring 上下文缺失）", null);
        try {
            float[] vec = embeddingModel.embed(text);
            if (vec == null || vec.length == 0) {
                throw new AiException("embedding 为空（模型 " + model + "）", null);
            }
            // 09-27 维度校验（fail-fast）：不符立即抛，避免维度错位的脏向量进入 pgvector
            int expected = props.getEmbeddingDim();
            if (vec.length != expected) {
                throw new AiException("embedding 维度不符: 期望 " + expected + ", 实际 " + vec.length
                        + "（模型 " + model + "）", null);
            }
            List<Double> out = new ArrayList<>(vec.length);
            for (float f : vec) out.add((double) f);
            return out;
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("embedding 调用失败: " + e.getMessage(), e);
        }
    }

    /** 当前配置的向量模型名（09-27：写入向量表 embedding_model 列与检索过滤用同一来源）。 */
    public String modelName() {
        return props.getEmbeddingModel();
    }

    /** 把 double 列表转成 pgvector 字面量字符串。 */
    public static String toPgVector(List<Double> vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(vec.get(i));
        }
        return sb.append("]").toString();
    }
}
