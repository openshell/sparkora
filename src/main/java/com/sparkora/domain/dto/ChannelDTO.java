package com.sparkora.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 信源栏目 DTO(10-09-cpca-gasgoo-collection G4;POST /api/sources 的 channels[] 与栏目写接口)。
 *
 * <p>创建时 {@code name}/{@code listUrl} 必填(由 {@code SourceService} 显式校验并映射 400);
 * 更新({@code PUT /api/channels/{id}})为部分更新:{@code null}=不改,空串=置空(但 {@code listUrl} 置空非法)。
 * {@code parseRules} 须为合法 JSON(非法 400)。
 */
@Data
public class ChannelDTO {
    @Size(max = 100, message = "栏目名不能超过 100 字")
    private String name;
    @Size(max = 500, message = "列表地址不能超过 500 字")
    private String listUrl;
    @Size(max = 500, message = "详情基址不能超过 500 字")
    private String detailBaseUrl;
    @Size(max = 30, message = "分类不能超过 30 字")
    private String category;
    /** JSON 字符串(列表/详情/表格/图片等选择器);非法 400。 */
    private String parseRules;
    /** NULL=继承源级。 */
    private Boolean needCrawl4ai;
    private Boolean enabled;
}
