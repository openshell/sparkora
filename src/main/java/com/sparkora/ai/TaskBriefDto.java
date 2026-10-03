package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * C1 结构化意图契约 TaskBrief（10-03-gen-cognitive-redesign）:schema 由本类型单一派生。
 *
 * <p>本 DTO 只承载「语义取值」;每槽位的 {@code source}/{@code confidence} 由
 * {@code ClarifyConversationService} 依据会话槽位元数据装配成
 * {@code {value,source,confidence}} 后再落 {@code task_brief}（列存 TEXT JSON）。
 * 必要槽位:{@code purpose}/{@code audience}/{@code mustCover}。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskBriefDto {

    @JsonPropertyDescription("写作目的/文章要达成的效果(必要槽位)")
    private String purpose;

    @JsonProperty(required = false)
    @JsonPropertyDescription("目标读者(必要槽位)")
    private String audience;

    @JsonProperty(required = false)
    @JsonPropertyDescription("语气风格倾向(可选)")
    private String tone;

    @JsonProperty(required = false)
    @JsonPropertyDescription("切入角度/论述方向(可选,0~n 条)")
    private List<String> angles;

    @JsonProperty(required = false)
    @JsonPropertyDescription("必须覆盖的内容点(必要槽位,0~n 条)")
    private List<String> mustCover;

    @JsonProperty(required = false)
    @JsonPropertyDescription("必须避免的内容/表述(可选,0~n 条)")
    private List<String> mustAvoid;

    @JsonProperty(required = false)
    @JsonPropertyDescription("成功标准/验收预期(可选)")
    private String successCriteria;

    @JsonProperty(required = false)
    @JsonPropertyDescription("目标字数(可选,整数)")
    private Integer lengthTarget;
}
