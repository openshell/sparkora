package com.sparkora.ai;

/** AI 调用异常（chat/生图失败等）。上层据此类做状态回滚与错误展示。 */
public class AiException extends RuntimeException {
    public AiException(String message, Throwable cause) {
        super(message, cause);
    }
}
