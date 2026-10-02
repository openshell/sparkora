package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * 澄清阶段研究计划 DTO（C2）：schema 由本类型单一派生，prompt 不再内联 JSON 字面量。
 *
 * <p>字段与旧 prompt schema 一一对应：{@code keyQuestions/dataNeeds/hypotheses/toolHints/questions}。
 * 反序列化容错沿用旧 Jackson 行为：未知字段忽略、缺失字段为 null（由 ClarifyService 后处理兜底）。
 * 非核心字段标 {@code required=false}，避免 schema 校验对合理缺省过度严格。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClarifyPlanDto {

    @JsonPropertyDescription("需要研究的关键问题(3-7 条,每条具体可查)")
    private List<String> keyQuestions;

    @JsonProperty(required = false)
    @JsonPropertyDescription("需要的数据(如:价格/尺寸/竞品参数)")
    private List<String> dataNeeds;

    @JsonProperty(required = false)
    @JsonPropertyDescription("初步假设(可被研究推翻)")
    private List<String> hypotheses;

    @JsonProperty(required = false)
    @JsonPropertyDescription("与 keyQuestions 一一对应的工具提示")
    private List<HintsItem> toolHints;

    @JsonPropertyDescription("澄清问题列表(3~5 个)")
    private List<Question> questions;

    /** keyQuestions 对应的工具提示：question 与 keyQuestions 一一对应，tools 取值 KB/WEB。 */
    @Data
    public static class HintsItem {
        @JsonPropertyDescription("与 keyQuestions 一一对应的问题文本")
        private String question;

        @JsonProperty(required = false)
        @JsonPropertyDescription("允许的工具:KB、WEB")
        private List<String> tools;
    }

    /** 单条澄清问题。input 型无 options；single/multi 型必有 options。 */
    @Data
    public static class Question {
        @JsonPropertyDescription("澄清问题文本")
        private String q;

        @JsonProperty(required = false)
        @JsonPropertyDescription("input|single|multi")
        private String type;

        @JsonProperty(required = false)
        @JsonPropertyDescription("single/multi 时的选项")
        private List<String> options;

        @JsonProperty(required = false)
        @JsonPropertyDescription("是否必答")
        private Boolean required;
    }
}
