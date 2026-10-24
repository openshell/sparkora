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
 * 车型主表实体。对应 sparkora_car_model。
 * 数据源:比亚迪官网 goodsListForSearch / getGoodsInfoById。
 */
@Data
@TableName("sparkora_car_model")
public class CarModelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String goodsId;        // 官网 goodsId,如 156
    private String name;           // 大唐EV
    private String salesNetwork;   // 王朝 / 海洋
    private String vehicleId;      // 官网 vehicleId
    private String priceRange;     // "239,900 - 309,900"
    private String features;       // JSON 数组,卖点
    private String introImages;    // JSON 数组,图库 asset id 列表
    /** 车型介绍图公网 URL 列表(非持久化;由 introImages 的图库 id 实时解析,供前端直接展示)。 */
    @TableField(exist = false)
    private List<String> introImageUrls;
    /**
     * 封面图库资产 id(10-21-knowledge-car-sync-ux):官网 goodsInfo.img 主体图转存后的
     * sparkora_image_asset.id。NULL = 无封面,前端展示层回退 introImageUrls 首图;
     * 封面与详情图集(intro_images)分离,互不覆盖。
     */
    private Long coverImageId;
    /** 封面公网 URL(非持久化;由 coverImageId 实时解析,解析失败为 null,不回退详情图)。 */
    @TableField(exist = false)
    private String coverImageUrl;
    private String detailPage;     // 官网详情页路径
    private String carRights;      // JSON,购车权益
    private String sourceUrl;      // 来源官网 URL
    /**
     * 同步指纹(10-15-car-image-backfill R2):goodsInfo 稳定字段 SHA-256(64 hex)。
     * NULL = 未初始化(存量兼容)——增量分派对该车型走轻回填顺带初始化;非 NULL 时与
     * 最新 goodsInfo 指纹比对,一致即跳过(零下载/零清洗/零嵌入)。已知盲区:goodsParams
     * 独立变化不触发(需手动全目录同步兜底)。由 persistModel(全量)/backfillImages(轻回填)统一写入。
     */
    private String syncFingerprint;
    /** 图片域指纹(10-21):SHA-256(img + introduce),用于「只换图」不触发全量清洗/向量。 */
    private String imageFingerprint;
    /** 参数域指纹(10-21):SHA-256(goodsParams + goodsAttrList),用于「只改参数」定向重建。 */
    private String paramsFingerprint;
    private String syncStatus;     // PENDING/SYNCING/SUCCESS/FAILED
    /** 官网数据源状态(10-21):OK / EMPTY / INCOMPLETE / STALE;NULL = 存量未标记。 */
    private String sourceState;
    /** 最近一次检查/同步的面向用户短说明(如「官网无数据」「部分图片失败」)。 */
    private String syncNote;
    /** 最近一次官网检查时间(空数据/跳过也更新);lastSyncAt 只在完整成功后更新。 */
    private LocalDateTime lastCheckedAt;
    private LocalDateTime lastSyncAt;
    /** 官网 goodsInfo.createdAt(数据创建时间);UI 文案「创建时间」,不是系统审计 created_at。 */
    private LocalDateTime sourceCreatedAt;
    private String lastSyncError;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
