package com.sparkora.web.dto;

import lombok.Data;

/** 用户侧创建 BYD 王朝推送任务请求。 */
@Data
public class BydPushJobCreateRequest {
    private String clubId;
    private String clubTitle;
}
