package com.sparkora.web.dto;

import lombok.Data;

/** 用户侧创建 BYD 王朝推送任务请求。 */
@Data
public class BydPushJobCreateRequest {
    /** 新前端显式选择：true=携带服务端固定圈子，false=不携带圈子。 */
    private Boolean useCircle;
    /** 旧版兼容字段；仅在值与服务端固定圈子一致时可用。 */
    private Boolean useClubTag;
    /** BYD 草稿协议字段；新前端不再提交，服务端固定圈子为唯一来源。 */
    private String clubId;
    private String clubTitle;
}
