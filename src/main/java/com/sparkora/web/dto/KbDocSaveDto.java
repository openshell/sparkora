package com.sparkora.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * KB 文档新增/编辑入参(S7)。
 */
public class KbDocSaveDto {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题不能超过 200 字")
    private String title;

    /** 领域标签;为空落「通用」。 */
    @Size(max = 50, message = "领域标签不能超过 50 字")
    private String domain;

    @NotBlank(message = "正文不能为空")
    @Size(max = 50000, message = "正文不能超过 50000 字")
    private String content;

    /** 编辑时可选;新增固定 true。 */
    private Boolean enabled;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}