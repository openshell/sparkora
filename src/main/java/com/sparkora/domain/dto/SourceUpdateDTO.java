package com.sparkora.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 信源编辑 DTO(10-05-source-crawl-base,PUT /api/sources/{id})。
 * 只编辑源级字段(触发调度重注册);栏目管理另行/后续任务。
 */
@Data
public class SourceUpdateDTO {
    @Size(max = 100, message = "信源名不能超过 100 字")
    private String name;
    @Size(max = 10, message = "类型不合法")
    private String type;             // RSS | SITE
    @Size(max = 30, message = "垂直领域不合法")
    private String vertical;
    @Size(max = 50, message = "cron 不能超过 50 字")
    private String cron;
    private Integer windowStartDay;  // 1-31(与 end 同时给才生效)
    private Integer windowEndDay;
    @Size(max = 20, message = "权威分档不合法")
    private String authorityTier;
    private Boolean needCrawl4ai;
    private Boolean enabled;
    /** 是否同时覆盖源下每个栏目的 need_crawl4ai(缺省不动栏目)。 */
    private Boolean applyCrawl4aiToChannels;
}
