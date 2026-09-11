package com.sparkora.domain.dto;

import lombok.Data;

/**
 * 保存预览页样式请求(09-11-preview-publish-bridge)。字段可选:null 表示不改该项。
 * theme/highlight 白名单校验在控制器(与 PreviewService 同口径)。
 */
@Data
public class PreviewStyleRequest {

    /** 预览主题(白名单,非法 400) */
    private String theme;

    /** 高亮主题(白名单,非法 400) */
    private String highlight;

    /** Mac 代码块开关 */
    private Boolean macStyle;

    /** 链接转脚注开关 */
    private Boolean footnote;
}
