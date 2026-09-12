package com.sparkora.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识问答提问入参(C4)。
 */
@Data
public class QaAskDto {

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题不能超过 2000 字")
    private String question;
}
