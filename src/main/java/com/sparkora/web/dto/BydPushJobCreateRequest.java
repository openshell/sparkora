package com.sparkora.web.dto;

import lombok.Data;

/** 用户侧创建 BYD 王朝推送任务请求。 */
@Data
public class BydPushJobCreateRequest {
    /** 前端显式选择：true=使用所选标签，false=不使用标签，null=旧客户端兼容语义。 */
    private Boolean useClubTag;
    private String clubId;
    private String clubTitle;
}
