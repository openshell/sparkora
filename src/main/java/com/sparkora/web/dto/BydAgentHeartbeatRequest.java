package com.sparkora.web.dto;

import lombok.Data;

/** 本地 Agent 心跳请求；预检结果随心跳刷新，Server 不将其固化为永久缓存。 */
@Data
public class BydAgentHeartbeatRequest {
    private String agentId;
    private String machineName;
    private String osName;
    private String agentVersion;
    private Object preflight;
}
