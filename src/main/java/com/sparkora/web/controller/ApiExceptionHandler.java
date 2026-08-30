package com.sparkora.web.controller;

import com.sparkora.common.R;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;

/**
 * /api 全局参数异常兜底：把 Spring 框架层抛出的 400（类型不匹配/缺参/请求体不可读/multipart 超限）
 * 统一收敛为 R.fail(400, 中文提示)，避免返回裸 Spring 错误体
 * （此前图库页上传曾因 projectId=NaN 落到这里的默认 400，前端只看到 {"status":400} 无 msg）。
 * 业务异常仍由各控制器自行 catch 转换（保持既有模式，本类只兜框架层）。
 */
@RestControllerAdvice(basePackages = "com.sparkora.web.controller")
public class ApiExceptionHandler {

    /** @RequestParam Long 类型解析失败（NaN/字母等）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> typeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName() == null ? "参数" : ex.getName();
        return R.fail(400, "参数 " + name + " 格式不正确");
    }

    /** 缺少必填参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> missingParam(MissingServletRequestParameterException ex) {
        return R.fail(400, "缺少必填参数: " + ex.getParameterName());
    }

    /** 请求体 JSON 不可读/字段类型不符。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> unreadable(HttpMessageNotReadableException ex) {
        return R.fail(400, "请求体格式不正确");
    }

    /** multipart 超限/损坏（upload 走的是 @RequestParam，容器层异常在此兜底）。 */
    @ExceptionHandler(MultipartException.class)
    public R<Void> multipart(MultipartException ex) {
        Throwable root = ex.getRootCause() != null ? ex.getRootCause() : ex;
        return R.fail(400, "上传失败: " + root.getMessage());
    }
}