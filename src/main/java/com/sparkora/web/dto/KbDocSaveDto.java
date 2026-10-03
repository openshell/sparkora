package com.sparkora.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * KB 文档新增/编辑入参(S7;10-03 E3 扩展 source/tags/生效期)。
 */
public class KbDocSaveDto {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    /** 受控领域标签(通用/充电/保养/政策/技术科普/安全/驾驶);为空落「通用」,非法值 400。 */
    @Size(max = 50, message = "领域标签不能超过 50 字")
    private String domain;

    /** 来源(URL/出处/署名);可空,空白归一为 null。 */
    @Size(max = 200, message = "来源不能超过 200 字")
    private String source;

    /** 标签(自由文本,逐项 trim/去空/去重/≤50);空数组=清空。 */
    private List<String> tags;

    /** 生效起(可空 = 不限)。 */
    private LocalDate effectiveFrom;

    /** 生效止(可空 = 不限)。 */
    private LocalDate effectiveTo;

    @NotBlank(message = "正文不能为空")
    @Size(max = 50000, message = "正文不能超过 50000 字")
    private String content;

    /** 编辑时可选;新增固定 true。 */
    private Boolean enabled;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
