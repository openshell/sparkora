package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 信源采集任务实体(10-05-source-crawl-base,仿 sparkora_news_sync_job)。对应 sparkora_source_job。
 * 异步任务化:创建任务即返回 jobId,前端轮询进度;失败明细存 failed_items JSON。
 */
@Data
@TableName("sparkora_source_job")
public class SourceJobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sourceId;
    private Long channelId;       // 可空=整源
    private String jobType;       // SCHEDULED / MANUAL / RETRY
    private String status;        // RUNNING/SUCCESS/PARTIAL/FAILED
    private String batchKey;      // 发布窗口批次键
    private Integer total;
    private Integer success;
    private Integer failed;
    private Integer degraded;     // 降级跳过数(Crawl4AI 未就绪等)
    private String failedItems;   // JSON:[{channelId,externalId,title,error}]
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String errorMsg;
    private String createdBy;
    private LocalDateTime createdAt;
    @TableLogic
    private Integer deleted;
}
