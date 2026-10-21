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
    private String jobType;        // SELECTED / RETRY / IMAGE_BACKFILL(轻回填) / INCREMENTAL(增量) / SCHEDULED(旧定时历史值)
    private String status;         // RUNNING/SUCCESS/PARTIAL/FAILED
    private Integer total;
    private Integer success;
    private Integer failed;
    /** 跳过数(10-21):增量「指纹一致」/回填「官网无数据」等,不并入 success。 */
    private Integer skipped;
    private String failedItems;    // JSON:[{goodsId,name,error|note}]
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 心跳时间(10-21):逐车型更新,陈旧判定优先于 started_at。 */
    private LocalDateTime heartbeatAt;
    /** 取消请求(10-21):1 = 停止处理后续车型。 */
    private Integer cancelRequested;
    private String errorMsg;
    private String createdBy;
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
