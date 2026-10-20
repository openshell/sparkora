package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** BYD 王朝小程序正文媒体暂存记录。 */
@Data
@TableName("sparkora_byd_dynasty_media")
public class BydDynastyMediaEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String mediaId;
    private Long projectId;
    /** IMAGE / VIDEO。 */
    private String mediaType;
    /** STAGED / URL。 */
    private String sourceKind;
    /** PENDING / READY / FAILED。 */
    private String storageState;
    /** 相对于 BYD_MEDIA_STORAGE_DIR 的受控路径，不向前端返回。 */
    private String storagePath;
    private String sourceUrl;
    private String fileName;
    private String mimeType;
    private Long byteSize;
    private Integer width;
    private Integer height;
    private BigDecimal durationSeconds;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
    @TableLogic
    private Integer deleted;
}
