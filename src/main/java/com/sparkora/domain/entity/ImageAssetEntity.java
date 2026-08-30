package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配图资产实体。对应 sparkora_image_asset。
 * 三来源统一入库：upload（图库上传）/ ai-text2img（文生图）/ ai-img2img（图生图）。
 * AI 生成图一律转存本地（storagePath），不留 axonhub 临时 URL。
 */
@Data
@TableName("sparkora_image_asset")
public class ImageAssetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long projectId;
    private String fileName;        // 原始文件名（生成图为 prompt 摘要命名）
    private String storagePath;     // 本地相对路径（/images/** 静态映射根下）
    private String source;          // upload / ai-text2img / ai-img2img
    private String promptText;      // 生成 prompt（AI 来源时）
    private Long refImageId;        // 图生图参考图 id（自引用，可空）
    private Integer width;          // px，取不到时为空
    private Integer height;
    private String qiniuKey;        // S4:七牛图床 key(sparkora/{imageId}.{ext};懒转存,空=未上床)
    private String createdBy;
    private LocalDateTime createdAt;
}