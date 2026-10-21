package com.sparkora.car.client;

/**
 * 官网接口不可用(HTTP/业务 code/data 结构异常、重试耗尽)。
 *
 * <p>与「官网返回了空数据」区分:{@code data=null} 或业务 code 非成功属于本异常,
 * 调用方不得把它当成空目录/空车型继续写库。
 */
public class SourceUnavailableException extends CarSourceException {

    /** 限流/超时等可重试故障的稳定标记,便于任务明细与前端分类。 */
    private final boolean transientFailure;

    public SourceUnavailableException(String userMessage, String detail, Throwable cause, boolean transientFailure) {
        super(userMessage, detail, cause);
        this.transientFailure = transientFailure;
    }

    public SourceUnavailableException(String userMessage, String detail) {
        this(userMessage, detail, null, false);
    }

    public boolean transientFailure() {
        return transientFailure;
    }
}
