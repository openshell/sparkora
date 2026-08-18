package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * AI 返回的 Brief 结构（与 prompt 中要求的 JSON schema 一一对应）。
 * 用于反序列化 AiClient.chatJson 的 content，再序列化为字符串入库。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BriefDto {
    private List<String> titleCandidates;
    private String audienceRefine;
    private List<String> coreViewpoints;
    private List<OutlineItem> outline;
    private List<FactRisk> factRisks;

    @Data
    public static class OutlineItem {
        private String heading;
        private List<String> subPoints;
    }

    @Data
    public static class FactRisk {
        private String claim;
        private String riskLevel;  // low | medium | high
        private String suggestion;
    }
}
