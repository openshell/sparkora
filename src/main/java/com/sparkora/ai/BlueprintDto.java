package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * C3 写作蓝图 DTO（10-03-gen-cognitive-redesign）:schema 由本类型单一派生,prompt 不内联 JSON 字面量。
 *
 * <p>由 {@code BlueprintService} 基于 C1 的 TaskBrief + C2 的 research_plan + fact_sheet 生成。
 * 本 DTO 只承载 LLM 产出的**语义结构**;{@code evidenceMap[].coverage} 与质量信号
 * （argumentDensity/evidenceCoverage/gapCount/taskBriefConsistency）**刻意不进 DTO**——它们由服务层
 * 以 fact_sheet.entries[].key 白名单确定性计算,不由模型自由发挥（design §4.3）。
 *
 * <p>反序列化容错沿用既有 Jackson 行为:未知字段忽略、缺失字段为 null（由服务层后处理兜底）。
 * 非核心字段标 {@code required=false},避免 schema 校验对合理缺省过度严格。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BlueprintDto {

    @JsonPropertyDescription("中心论点一句话(必须能统领全文分节论证)")
    private String thesis;

    @JsonPropertyDescription("面向目标读者的切入角度一句话")
    private String audienceAngle;

    @JsonPropertyDescription("叙事弧线(读者认知路径:问题→张力→解答→行动)")
    private String narrativeArc;

    @JsonProperty(required = false)
    @JsonPropertyDescription("分节论证结构(按写作顺序)")
    private List<Section> argumentStructure;

    @JsonProperty(required = false)
    @JsonPropertyDescription("证据映射:每个论点→所需证据→绑定的手册条目 key")
    private List<Evidence> evidenceMap;

    @JsonProperty(required = false)
    @JsonPropertyDescription("写作约束/合规红线(0~n 条)")
    private List<String> constraints;

    @JsonProperty(required = false)
    @JsonPropertyDescription("证据缺口声明(某节缺对应事实)")
    private List<Gap> gaps;

    /** 分节:role∈HOOK|CONTEXT|ARGUMENT|EVIDENCE|COUNTER|CONCLUSION。 */
    @Data
    public static class Section {
        @JsonPropertyDescription("分节标识(如 S1/S2,稳定短 id)")
        private String sectionId;

        @JsonPropertyDescription("分节标题")
        private String heading;

        @JsonPropertyDescription("该节角色:HOOK|CONTEXT|ARGUMENT|EVIDENCE|COUNTER|CONCLUSION")
        private String role;

        @JsonProperty(required = false)
        @JsonPropertyDescription("该节分论点/核心陈述")
        private String claim;

        @JsonProperty(required = false)
        @JsonPropertyDescription("该节叙事意图")
        private String narrativeIntent;

        @JsonProperty(required = false)
        @JsonPropertyDescription("与中心论点/其他节的关系(如:支撑 thesis / 回应 S2)")
        private String argumentRelation;
    }

    /**
     * 证据映射项:{@code entryKeys} 必须逐字取自 fact_sheet.entries[].key;
     * {@code coverage} 由服务层覆盖为 COVERED|PARTIAL|MISSING（LLM 值不可信,不采用）。
     */
    @Data
    public static class Evidence {
        @JsonPropertyDescription("分节标识(与 argumentStructure.sectionId 对应)")
        private String sectionId;

        @JsonProperty(required = false)
        @JsonPropertyDescription("该节分论点")
        private String argument;

        @JsonProperty(required = false)
        @JsonPropertyDescription("所需证据类型/描述")
        private String evidenceNeeded;

        @JsonProperty(required = false)
        @JsonPropertyDescription("绑定的手册事实条目 key 列表(逐字取自事实手册 entries[].key,不得编造)")
        private List<String> entryKeys;

        @JsonProperty(required = false)
        @JsonPropertyDescription("覆盖状态(由服务层确定性计算,模型可留空)")
        private String coverage;

        @JsonProperty(required = false)
        @JsonPropertyDescription("备注")
        private String note;
    }

    /** 证据缺口:{@code sectionId} 对应分节,{@code reason} 中文说明。 */
    @Data
    public static class Gap {
        @JsonPropertyDescription("缺失证据的分节标识")
        private String sectionId;

        @JsonPropertyDescription("缺口原因(中文)")
        private String reason;
    }
}
