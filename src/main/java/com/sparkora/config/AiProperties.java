package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * AI 模型配置（axonhub 统一入口）。对应 .env: AI_BASE_URL / AI_API_KEY / AI_MODEL / AI_IMAGE_MODEL(S) 等。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.ai")
public class AiProperties {
    private String baseUrl = "https://axo.caiqz.cn";
    private String apiKey;
    private String model;
    /** 向量化模型(S6 车型知识库 RAG 用;实测 Qwen3-Embedding-8B,1024 维)。 */
    private String embeddingModel;
    private String imageModel;
    /** 逗号分隔的图片模型列表，按序轮询（图片模型不稳定）；为空时回退到 imageModel 单个。 */
    private String imageModels;
    private long timeoutMs = 120000;
    private double temperature = 0.7;

    /** 解析图片模型轮询列表：优先 imageModels，为空回退 imageModel。 */
    public List<String> imageModelList() {
        String raw = (imageModels != null && !imageModels.isBlank()) ? imageModels : imageModel;
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
