package com.sparkora.web.dto;

import lombok.Data;

/** 本地 Agent 领取任务请求。 */
@Data
public class BydAgentClaimRequest {
    private String agentId;
}
