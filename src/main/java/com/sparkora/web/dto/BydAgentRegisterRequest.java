package com.sparkora.web.dto;

import lombok.Data;

/** 本地 Agent 注册请求；preflight 由前端原始对象保存为 JSON 摘要。 */
@Data
public class BydAgentRegisterRequest {
    private String agentId;
    private String machineName;
    private String osName;
    private String agentVersion;
    private Object preflight;
}
