package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * AI 返回的 Brief 结构（C2 起 schema 单一来源：由本类型派生，prompt 不再内联字面量）。
 *
 * <p>字段保持不变；{@link JsonPropertyDescription} 把原 prompt 中「字段说明」内聚到类型上，
 * 生成的 JSON Schema 会带上 description。schema 的 {@code required} 来自生成器
 * 「所有声明属性默认必填」（{@code PROPERTY_REQUIRED_BY_DEFAULT}），**不是** jakarta 注解驱动
 * ——实际使用的 victools SchemaGenerator 不读取 jakarta.validation，故 {@code @Size} 等注解
 * 对生成的 schema 无影响，仅作字段约束声明保留（避免误以为它们约束了 AI 输出）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BriefDto {

    @Size(max = 3)
    @JsonPropertyDescription("3个标题候选")
    private List<String> titleCandidates;

    @JsonPropertyDescription("细化后的目标读者一句话描述")
    private String audienceRefine;

    @Size(max = 4)
    @JsonPropertyDescription("2-4条核心观点")
    private List<String> coreViewpoints;

    @JsonPropertyDescription("大纲：每章 heading + 2-4 个 subPoints")
    private List<OutlineItem> outline;

    @JsonPropertyDescription("事实风险：由手册 warnings 与低置信条目派生，至少 1 条")
    private List<FactRisk> factRisks;

    @Data
    public static class OutlineItem {
        @JsonPropertyDescription("章节标题")
        private String heading;
        @JsonPropertyDescription("2-4个要点")
        private List<String> subPoints;
    }

    @Data
    public static class FactRisk {
        @JsonPropertyDescription("文中可能提到的事实性表述")
        private String claim;
        @JsonPropertyDescription("low|medium|high")
        private String riskLevel;
        @JsonPropertyDescription("核实/表述建议")
        private String suggestion;
    }
}
