package com.sparkora.car.client;

/**
 * 官网三接口校验不通过(goodsInfo/goodsParams/goodsAttrList 为空或不完整)。
 *
 * <p>抛出前调用方已把车型标记为 {@code source_state=EMPTY/INCOMPLETE} 且只更新
 * {@code last_checked_at},**不得**执行任何业务表 delete/update,也不得刷新
 * {@code last_sync_at}。任务层据此记录失败明细(不写 SUCCESS)。
 */
public class SourceIncompleteException extends CarSourceException {

    /** EMPTY(官网明确无数据) / INCOMPLETE(有响应但结构不完整)。 */
    private final String sourceState;

    public SourceIncompleteException(String sourceState, String userMessage, String detail) {
        super(userMessage, detail, null);
        this.sourceState = sourceState;
    }

    public String sourceState() {
        return sourceState;
    }
}
