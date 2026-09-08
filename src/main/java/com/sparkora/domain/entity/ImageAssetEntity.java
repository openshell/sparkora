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
    /** 缩略图 URL（非持久化；S10 起七牛 imageView2/webp 派生，非七牛实现降级为原图 url）。 */
    @TableField(exist = false)
    private String thumbUrl;
    /** 去重命中标记（非持久化；S10 起内容哈希命中已有记录时 true，前端提示「复用」）。 */
    @TableField(exist = false)
    private Boolean dedupeHit;

    // ==== S10 持久化字段（schema.sql S10 段幂等补列；存量行为 NULL） ====
    /** 内容哈希（sha256 hex，入库去重用；仅新增入库必填，存量允许 NULL）。 */
    private String contentHash;
    /** 生成留档：实际命中的模型名（AI 来源；上传/BYD 为空）。 */
    private String genModel;
    /** 生成留档：请求尺寸（AI 来源；auto/未指定为 NULL）。 */
    private String genSize;
}