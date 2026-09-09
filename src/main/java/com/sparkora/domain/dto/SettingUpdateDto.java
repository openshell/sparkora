package com.sparkora.domain.dto;

import lombok.Data;

/**
 * 更新系统检索设置请求。字段可选:null 表示不改该项。
 */
@Data
public class SettingUpdateDto {

    /** 内部知识库启用 */
    private Boolean kbEnabled;

    /** 外部搜索启用 */
    private Boolean webSearchEnabled;
}