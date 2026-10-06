package com.sparkora.domain.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 更新系统检索设置请求。字段可选:null 表示不改该项。
 * 09-25-brief-web-search:新增 {@code webProviderOrder}(外部搜索策略,仅 ADMIN)。
 */
@Data
public class SettingUpdateDto {

    /** 内部知识库启用 */
    private Boolean kbEnabled;

    /** 外部搜索启用 */
    private Boolean webSearchEnabled;

    /**
     * 外部搜索 provider 顺序(逗号分隔;TAVILY,SEARXNG=TAVILY_FIRST / SEARXNG,TAVILY=SEARXNG_FIRST /
     * 含 SERPER 时回落 PRIMARY_FANOUT 标签)。10-04-serper-provider A-R9:放开 SERPER。
     * null/空表示不改;非空时仅允许 TAVILY/SEARXNG/SERPER 的任意顺序组合,非法值 400。
     */
    @Pattern(regexp = "^\\s*$|^\\s*(TAVILY|SEARXNG|SERPER)(\\s*,\\s*(TAVILY|SEARXNG|SERPER))*\\s*$",
            message = "外部搜索策略仅支持 TAVILY/SEARXNG/SERPER 的顺序组合")
    private String webProviderOrder;
}
