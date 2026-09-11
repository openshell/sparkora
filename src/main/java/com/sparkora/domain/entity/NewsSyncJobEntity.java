package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 新闻同步任务实体。对应 sparkora_news_sync_job(C2)。
 * 异步任务化:创建任务即返回 jobId,前端轮询进度;失败明细存 failed_items JSON。
 */
@Data
@TableName("sparkora_news_sync_job")
public class NewsSyncJobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobType;        // FULL / INCREMENT / SCHEDULED / RETRY
    private String status;         // RUNNING/SUCCESS/PARTIAL/FAILED
    private Integer total;
    private Integer success;
    private Integer failed;
    private String failedItems;    // JSON:[{newsId,title,error}]
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMsg;
    private String createdBy;
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
