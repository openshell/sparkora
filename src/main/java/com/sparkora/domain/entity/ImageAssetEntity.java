package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配图资产实体。对应 sparkora_image_asset。
 * 四来源统一入库：upload（图库上传）/ ai-text2img（文生图）/ ai-img2img（图生图）/ byd（比亚迪同步）。
 * 图片入库即直接转存图床（storageKey），本地不留文件。
 */
@Data
@TableName("sparkora_image_asset")
public class ImageAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String fileName;        // 原始文件名（生成图为 prompt 摘要命名）
    private String source;          // upload / ai-text2img / ai-img2img / byd
    private String promptText;      // 生成 prompt（AI 来源时）
    private Long refImageId;        // 图生图参考图 id（自引用，可空）
    private Integer width;          // px，取不到时为空
    private Integer height;
    private String storageKey;      // 图床 key（入库即转存，非空）
    private String createdBy;
    private LocalDateTime createdAt;

    /** 图床公网 URL（非持久化，由 storageKey 实时拼，供前端直接展示/引用）。 */
    @TableField(exist = false)
    private String url;
}