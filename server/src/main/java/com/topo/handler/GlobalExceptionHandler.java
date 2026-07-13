package com.topo.handler;

import com.topo.result.Result;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理
 *
 * 所有 Controller 抛出的异常在这里统一拦截，
 * 包装成 Result 返回给前端，不用在业务代码里 try-catch。
 *
 * 异常顺序从具体到通用：
 *   RuntimeException → 参数校验异常 → Exception（兜底）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：如邮箱已注册、密码错误、无权限等 */
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        return Result.error(e.getMessage());
    }

    /** 参数校验失败：@Valid 校验不通过时触发 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("参数校验失败");
        return Result.error(400, msg);
    }

    /** 兜底：未知异常 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        return Result.error("服务器内部错误: " + e.getMessage());
    }
}
