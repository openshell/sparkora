package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * C1 意图澄清「每轮下一步」DTO（10-03-gen-cognitive-redesign）:schema 由本类型单一派生,
 * prompt 不内联 JSON 字面量（对齐 {@link ClarifyPlanDto}）。
 *
 * <p>一轮 {@code structured(...)} 调用的产物:要么给出下一问({@code nextQuestion}),
 * 要么判定信息已充分({@code converged=true})。{@code slotUpdates} 是本轮从用户回答中
 * 抽出的槽位增量(带 source/confidence);{@code reasoning} 可选,透出落 session。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClarifyNextDto {

    @JsonPropertyDescription("信息是否已充分：true 表示可以收敛产出意图契约，不再需要追问")
    private boolean converged;

    @JsonProperty(required = false)
    @JsonPropertyDescription("下一轮要问的问题(converged=false 时给出;converged=true 时为 null)")
    private Question nextQuestion;

    @JsonProperty(required = false)
    @JsonPropertyDescription("本轮从用户回答中抽取到的槽位增量")
    private List<SlotUpdate> slotUpdates;

    @JsonProperty(required = false)
    @JsonPropertyDescription("简短说明你为什么问这个问题/为什么收敛(可选)")
    private String reasoning;

    /** 下一问题:{@code type=input|single|multi};single/multi 必须给 options;slotId 指向缺口槽位。 */
    @Data
    public static class Question {
        @JsonPropertyDescription("问题标识(稳定短 id,如 purpose/audience/competitor)")
        private String id;

        @JsonPropertyDescription("问题文本")
        private String text;

        @JsonProperty(required = false)
        @JsonPropertyDescription("input|single|multi")
        private String type;

        @JsonProperty(required = false)
        @JsonPropertyDescription("single/multi 的选项")
        private List<String> options;

        @JsonProperty(required = false)
        @JsonPropertyDescription("是否必须回答")
        private Boolean required;

        @JsonProperty(required = false)
        @JsonPropertyDescription("该问题对应的槽位 id")
        private String slotId;
    }

    /** 槽位增量:{@code source∈USER|PICKED|DEFAULT|INFERRED};{@code confidence∈[0,1]}。 */
    @Data
    public static class SlotUpdate {
        @JsonPropertyDescription("槽位 id(purpose/audience/tone/angles/mustCover/mustAvoid/successCriteria/lengthTarget)")
        private String id;

        @JsonProperty(required = false)
        @JsonPropertyDescription("槽位展示名")
        private String label;

        @JsonPropertyDescription("本轮抽取到的槽位取值(文本;数组槽位可为 JSON 数组文本)")
        private String value;

        @JsonProperty(required = false)
        @JsonPropertyDescription("取值来源:USER|PICKED|DEFAULT|INFERRED")
        private String source;

        @JsonProperty(required = false)
        @JsonPropertyDescription("置信度 0~1")
        private Double confidence;
    }
}
