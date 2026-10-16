package com.sparkora.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * BYD 王朝本地 Agent 实体。对应 sparkora_byd_dynasty_agent。
 *
 * <p>Agent 运行在微信所在的本机；Server 只保存它最近上报的预检摘要与在线状态。
 */
@Data
@TableName("sparkora_byd_dynasty_agent")
public class BydDynastyAgentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ownerUserId;
    private String agentId;
    private String machineName;
    private String osName;
    private String agentVersion;
    private String status;
    private LocalDateTime lastSeenAt;
    private String lastPreflightJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
