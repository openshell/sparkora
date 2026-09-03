package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创作项目实体。对应 sparkora_article_project。
 */
@Data
@TableName("sparkora_article_project")
public class ArticleProjectEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String topic;
    private String keywords;
    private String audience;
    private Integer wordCountTarget;
    private Long brandVoiceProfileId;
    private String status;  // DRAFT / GENERATING_BRIEF / READY / ...
    private Long currentBriefId;       // S1：指向当前 brief（sparkora_article_brief.id）
    private String lastBriefError;     // S1：最近一次生成失败原因（成功后清空）
    private Long currentVersionId;      // S1b：指向选定版本（sparkora_article_version.id）
    private String lastVersionError;    // S1b：最近一次版本生成失败原因（成功后清空）
    private String remark;
    private String publishMediaId;      // S5:公众号草稿箱 media_id(发布成功后落库)
    private String publishTheme;        // S5/S4:发布所用排版主题
    private LocalDateTime publishedAt;  // S5:发布时间
    private String lastPublishError;    // S5:最近一次发布失败原因(成功后清空)
    private String extraInfo;           // S6:补充信息(可选;个人见解/独家资讯等,生成时注入 prompt 作为创作素材)
    private String selectedTitle;       // S6:简报阶段选定的标题(可选;生成版本时作为标题偏好注入 prompt)
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;

    /** S6:关联车型 id 列表(非表字段,由控制器从关联表填充,供前端展示/编辑)。 */
    @TableField(exist = false)
    private List<Long> carModelIds;
}
