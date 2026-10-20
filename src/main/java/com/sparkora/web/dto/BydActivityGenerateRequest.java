package com.sparkora.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 王朝小程序「官方活动发文」生成请求(10-19-channel-editor-publish R4)。
 *
 * <p>activityBrief = 官方活动要求全文;personalInfo = 车主个人情况/可分享的经历。
 * 两项均为必填,由 {@code @Valid} 直接映射 400 中文提示。</p>
 */
@Data
public class BydActivityGenerateRequest {

    @NotBlank(message = "官方活动要求不能为空")
    @Size(max = 20000, message = "官方活动要求不能超过 20000 字")
    private String activityBrief;

    @NotBlank(message = "我的情况不能为空")
    @Size(max = 5000, message = "我的情况不能超过 5000 字")
    private String personalInfo;
}
