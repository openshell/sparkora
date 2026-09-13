package com.sparkora.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * AI 生图入参（S10 收口：原裸 Map 解析改为 @Valid DTO）。
 * projectId/refImageId 以 String 承接（兼容数字/字符串形式——前端路由参数为字符串），控制器健壮解析。
 */
@Data
public class ImageGenDTO {
    /** 关联项目（可空=全局图库；JSON 里数字或字符串均可）。 */
    private String projectId;
    /** 图生图参考图 id（仅 generate-from-image；可空）。 */
    private String refImageId;
    @NotBlank(message = "请输入生成提示词（prompt）")
    private String prompt;
    /** OpenAI 兼容 size 白名单（控制器复用 ImageService.ALLOWED_SIZES 校验）。 */
    private String size;
    /** 批量生成张数（1~4；S10 候选生成）。 */
    @Min(value = 1, message = "生成张数至少 1")
    @Max(value = 4, message = "生成张数最多 4")
    private Integer n = 1;
    /** 随图入库的预选标签（09-13 image-tags；可空=不打标，长度 1~50 由服务层校验）。 */
    private List<String> tags;
}
