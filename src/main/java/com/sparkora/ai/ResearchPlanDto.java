package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * C2 纯事实研究规划 DTO（10-03-gen-cognitive-redesign）:schema 由本类型单一派生,prompt 不内联 JSON 字面量。
 *
 * <p>由 {@code ResearchPlannerService} 基于 C1 产出的 TaskBrief 生成。字段收敛为**纯事实子问题**:
 * {@code keyQuestions}（仅事实/世界维度）、{@code dataNeeds}、{@code hypotheses}、{@code toolHints}。
 * <b>刻意不含任何意图/澄清问题字段</b>——意图澄清是 C1 职责,不得混入研究规划。
 *
 * <p>反序列化容错沿用旧 Jackson 行为:未知字段忽略、缺失字段为 null（由服务层后处理兜底）。
 * 非核心字段标 {@code required=false},避免 schema 校验对合理缺省过度严格。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResearchPlanDto {

    @JsonPropertyDescription("需要研究的关键事实问题(3-7 条,仅事实/世界维度,具体可查;严禁意图/澄清类问题)")
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

    /** keyQuestions 对应的工具提示:question 与 keyQuestions 一一对应,tools 取值 KB/WEB。 */
    @Data
    public static class HintsItem {
        @JsonPropertyDescription("与 keyQuestions 一一对应的问题文本")
        private String question;

        @JsonProperty(required = false)
        @JsonPropertyDescription("允许的工具:KB、WEB")
        private List<String> tools;
    }
}
