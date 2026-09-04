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

    /** 车型知识库 RAG:逐块相似度门槛,低于该值的检索块不注入 prompt(S6.1;默认 0.3,待按真实分数分布校准)。 */
    private double ragMinScore = 0.3;
    /** 车型知识库 RAG:整体置信度门槛,全部命中块的最高相似度低于该值时整体抛弃(S6.1;默认 0.5,须 >= ragMinScore,待校准)。 */
    private double ragRejectScore = 0.5;

    /** 通用知识库(KB)生成检索:单次注入通用域块数上限(S7;默认 4,与车型域配额独立互不挤占)。 */
    private int ragKbTopk = 4;
    /** 通用知识库(KB)总开关:关闭即回退 S6.2 纯车型域行为(异常时一键回滚点,S7)。 */
    private boolean ragKbEnabled = true;

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
