package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 车型同步任务实体。对应 sparkora_car_sync_job。
 * 异步任务化:创建任务即返回 jobId,前端轮询进度;失败明细存 failed_items JSON。
 */
@Data
@TableName("sparkora_car_sync_job")
public class CarSyncJobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobType;        // SELECTED / RETRY
    private String status;         // RUNNING/SUCCESS/PARTIAL/FAILED
    private Integer total;
    private Integer success;
    private Integer failed;
    private String failedItems;    // JSON:[{goodsId,name,error}]
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMsg;
    private String createdBy;
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
