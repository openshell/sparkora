package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BYD 王朝推送任务实体。对应 sparkora_byd_dynasty_push_job。
 *
 * <p>任务由 Sparkora Server 生成契约 payload，由本机 Agent 领取后执行；Server 侧只做状态审计。
 */
@Data
@TableName("sparkora_byd_dynasty_push_job")
public class BydDynastyPushJobEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String jobId;
    private Long projectId;
    private Long versionId;
    private Long agentId;
    private String status;
    private String payloadJson;
    private String resultJson;
    private String errorMessage;
    private Long createdBy;
    private LocalDateTime claimedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
