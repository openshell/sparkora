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

    /** S6:关联车型(可选;生成 brief/版本时注入车型知识库 RAG 上下文)。 */
    private Long carModelId;

    /** S6:补充信息(可选;用户个人见解/独家资讯等,生成 brief/版本时注入 prompt 作为创作素材)。 */
    @Size(max = 5000)
    private String extraInfo;

    @Size(max = 500)
    private String remark;
}
