package com.sparkora.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建信源 DTO(10-09-cpca-gasgoo-collection G4;POST /api/sources)。
 * 事务内插入 source + channels,返回详情。{@code type ∈ {RSS,SITE}};栏目 name/listUrl/parseRules 由服务校验。
 */
@Data
public class SourceCreateDTO {
    @NotBlank(message = "信源名不能为空")
    @Size(max = 100, message = "信源名不能超过 100 字")
    private String name;
    @NotBlank(message = "类型不能为空")
    @Pattern(regexp = "RSS|SITE", message = "类型必须是 RSS 或 SITE")
    private String type;
    @Size(max = 30, message = "垂直领域不合法")
    private String vertical;
    @Size(max = 50, message = "cron 不能超过 50 字")
    private String cron;
    private Integer windowStartDay;   // 1-31
    private Integer windowEndDay;     // 1-31
    @Size(max = 20, message = "权威分档不合法")
    private String authorityTier;
    private Boolean needCrawl4ai;
    private Boolean enabled;
    /** 栏目列表(可空 = 建后补)。 */
    @Valid
    private List<ChannelDTO> channels;
}
