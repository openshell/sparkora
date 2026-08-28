package com.sparkora.service;

/**
 * 生成请求的前置状态不满足（如尚未生成 brief、brief 不存在）。
 * 属客户端错误，控制器映射为 R.fail(400)；与并发生成中的 409 区分。
 */
public class NotReadyException extends RuntimeException {
    public NotReadyException(String message) { super(message); }
}