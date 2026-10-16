package com.sparkora.web.dto;

import lombok.Data;

import java.util.List;

/** 本地 Agent 执行结果上报。 */
@Data
public class BydAgentResultRequest {
    private String agentId;
    private Boolean success;
    private String draftId;
    private Integer draftCount;
    private String error;
    private List<String> uploadedImageUrls;
    private Object preflightSummary;
    private Object logsSummary;
}
