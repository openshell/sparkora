package com.sparkora.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页响应。
 */
@Data
public class PageResult<T> {
    private List<T> rows;
    private long total;
    private long page;
    private long size;

    public PageResult(List<T> rows, long total, long page, long size) {
        this.rows = rows;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}
