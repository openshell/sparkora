package com.sparkora.kb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * KB 批量导入的「一行」模型(10-03 C 批量导入)。
 *
 * <p>字段与单条入参 {@code com.sparkora.web.dto.KbDocSaveDto} 对齐:CSV/JSON/Markdown 三种格式
 * 解析后统一落到本模型,再逐条委托 {@code KbDocService.create}。
 *
 * <p>{@link #parseError} 承载**行级解析错误**(如生效期格式非法):非空时该行直接判失败、
 * 不进入 create,保证「单条失败不阻断其余、且不产生孤儿文档」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class KbImportRow {

    private String title;
    private String domain;
    private String content;
    private String source;
    private List<String> tags;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;

    /** 行级解析错误(中文);非空则该行不进入写库链路。 */
    private String parseError;
}
