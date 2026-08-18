package com.sparkora.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建/编辑创作任务请求。
 */
@Data
public class ProjectRequest {

    @NotBlank(message = "主题不能为空")
    @Size(max = 200, message = "主题不能超过 200 字")
    private String topic;

    @Size(max = 500)
    private String keywords;

    @Size(max = 200)
    private String audience;

    private Integer wordCountTarget;

    private Long brandVoiceProfileId;

    @Size(max = 500)
    private String remark;
}
