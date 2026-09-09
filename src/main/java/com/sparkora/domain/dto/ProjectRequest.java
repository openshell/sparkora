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

    /** S6:关联车型列表(可选;一篇文章可关联多个车型,生成时跨车型检索知识库)。 */
    private java.util.List<Long> carModelIds;

    /** S6:补充信息(可选;用户个人见解/独家资讯等,生成 brief/版本时注入 prompt 作为创作素材)。 */
    @Size(max = 5000)
    private String extraInfo;

    /** S6:简报阶段选定的标题(可选;用户从标题候选中点选,生成版本时作为标题偏好注入 prompt)。 */
    @Size(max = 200)
    private String selectedTitle;

    // ===== 文章仿写(09-09-article-imitation) =====
    /** 创作方式:TOPIC(默认,主题创作)/IMITATION(文章仿写);仅仿写时非空提交。 */
    private String genSource;

    /** 参考原文全文(IMITATION 模式必填;≤20000 字,业务校验在控制器)。 */
    @Size(max = 20000, message = "参考原文不能超过 20000 字")
    private String imitationText;

    @Size(max = 500)
    private String remark;
}
