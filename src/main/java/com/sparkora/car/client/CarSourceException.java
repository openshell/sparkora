package com.sparkora.car.client;

/**
 * 官网车型采集异常基类(10-21)。
 *
 * <p>用户可见信息统一走 {@link #userMessage()} 的分类短文案;原始 URL/堆栈只进日志,
 * 不进入接口响应或任务失败明细(防止把内部地址/SQL 暴露给前端)。
 */
public class CarSourceException extends RuntimeException {

    private final String userMessage;

    public CarSourceException(String userMessage, String detail, Throwable cause) {
        super(detail == null ? userMessage : detail, cause);
        this.userMessage = userMessage;
    }

    public CarSourceException(String userMessage, String detail) {
        this(userMessage, detail, null);
    }

    /** 面向用户的短文案(中文,不含 URL/堆栈)。 */
    public String userMessage() {
        return userMessage;
    }

    /**
     * 把任意异常收敛为用户可见的短文案(任务明细/接口 msg 共用)。
     * 已知采集异常走其分类文案;其余按网络/数据库/未知兜底,绝不透传原始内部串。
     */
    public static String classify(Throwable e) {
        if (e == null) return "未知错误";
        if (e instanceof CarSourceException cse) return cse.userMessage();
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String low = msg.toLowerCase();
        if (low.contains("timeout") || low.contains("timed out") || msg.contains("超时")) return "网络超时,请稍后重试";
        if (low.contains("current transaction is aborted") || low.contains("sql") || low.contains("jdbc")) {
            return "数据库操作失败";
        }
        if (e instanceof IllegalArgumentException) return msg.isBlank() ? "参数错误" : msg;
        // 短业务文案可原样展示;含 URL/堆栈/换行的原始串一律收敛,避免内部信息外泄
        boolean safe = msg.isBlank() || msg.length() > 120 || msg.contains("\n")
                || msg.contains("://") || msg.contains("\tat ") || low.contains("exception");
        return safe ? "同步失败,请查看后端日志" : msg;
    }
}
