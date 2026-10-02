package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * 子代理研究笔记 DTO（C2）：schema 由本类型单一派生，prompt 不再内联 JSON 字面量。
 *
 * <p>字段与旧 prompt schema 一一对应：{@code facts[{claim,value,snippet,source{type,sourceId,url,modelName,docId},confidence}]}、{@code gaps[]}。
 * 产出仍序列化为等价 JSON 字符串供 {@code SubAgentRunner.validateFacts} 后验校验（R1 降级保真字段 snippet/content 不丢）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubAgentFactsDto {

    @JsonPropertyDescription("事实条目列表")
    private List<Fact> facts;

    @JsonProperty(required = false)
    @JsonPropertyDescription("未能从检索结果回答的部分")
    private List<String> gaps;

    /** 单条事实。value 可省略；snippet/content 为原始证据（R1）。 */
    @Data
    public static class Fact {
        @JsonPropertyDescription("事实条目(一句话)")
        private String claim;

        @JsonProperty(required = false)
        @JsonPropertyDescription("数值(如无可省略)")
        private String value;

        @JsonProperty(required = false)
        @JsonPropertyDescription("检索结果中的原始证据片段")
        private String snippet;

        @JsonPropertyDescription("来源")
        private Source source;

        @JsonProperty(required = false)
        @JsonPropertyDescription("置信度:KB 0.9,单一 WEB 0.6")
        private Double confidence;
    }

    /** 来源。KB 来源仅 type/docId；WEB 来源必须带 sourceId 与 url。 */
    @Data
    public static class Source {
        @JsonProperty(required = false)
        @JsonPropertyDescription("KB|WEB")
        private String type;

        @JsonProperty(required = false)
        @JsonPropertyDescription("WEB 来源的检索标识(如 W1)")
        private String sourceId;

        @JsonProperty(required = false)
        @JsonPropertyDescription("WEB 来源 URL")
        private String url;

        @JsonProperty(required = false)
        @JsonPropertyDescription("来源名称")
        private String modelName;

        @JsonProperty(required = false)
        @JsonPropertyDescription("KB 来源文档 id")
        private Long docId;
    }
}
