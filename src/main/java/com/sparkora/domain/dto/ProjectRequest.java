package com.sparkora.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建/编辑创作任务请求。
 *
 * <p>topic 的必填性按渠道区分(10-19-channel-editor-publish):公众号 gzh 仍必填,
 * 王朝小程序 byd_miniapp 允许空(方式一从空草稿开始自由编辑),因此不在 DTO 上写
 * {@code @NotBlank},由控制器按项目渠道显式校验并返回等价中文提示。</p>
 */
@Data
public class ProjectRequest {

    @Size(max = 200, message = "主题不能超过 200 字")
    private String topic;

    /** 10-19:发布渠道,可选,缺省 gzh;仅允许 gzh / byd_miniapp(控制器校验)。 */
    @Size(max = 20, message = "渠道标识不能超过 20 字")
    private String channel;

    @Size(max = 200)
    private String audience;

    private Integer wordCountTarget;

    private Long brandVoiceProfileId;

    /** S6:关联车型列表(可选;一篇文章可关联多个车型,生成时跨车型检索知识库)。 */
    private java.util.List<Long> carModelIds;

    /** 10-02:内容描述(可选;原 extra_info 改名;用户个人见解/独家资讯等,全链路注入 prompt 作为创作素材)。 */
    @Size(max = 5000)
    private String contentDescription;

    /** S6:简报阶段选定的标题(可选;用户从标题候选中点选,生成版本时作为标题偏好注入 prompt)。 */
    @Size(max = 200)
    private String selectedTitle;

    // ===== 文章仿写(09-09-article-imitation) =====
    /** 创作方式:TOPIC(默认,主题创作)/IMITATION(文章仿写);仅仿写时非空提交。 */
    private String genSource;

    /** 参考原文全文(IMITATION 模式必填;≤20000 字,业务校验在控制器)。 */
    @Size(max = 20000, message = "参考原文不能超过 20000 字")
    private String imitationText;
}
