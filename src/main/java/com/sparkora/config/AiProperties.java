package com.sparkora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 模型配置（axonhub 统一入口）。对应 .env: AI_BASE_URL / AI_API_KEY / AI_MODEL / AI_IMAGE_MODEL 等。
 * S0 仅预留配置读取位，不真正调用。
 */
@Data
@ConfigurationProperties(prefix = "sparkora.ai")
public class AiProperties {
    private String baseUrl = "https://axo.caiqz.cn";
    private String apiKey;
    private String model;
    private String imageModel;
    private long timeoutMs = 120000;
    private double temperature = 0.7;
}
