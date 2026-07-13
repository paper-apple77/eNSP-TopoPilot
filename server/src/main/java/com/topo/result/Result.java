package com.topo.result;

import lombok.Data;

/**
 * 统一响应格式
 *
 * 所有 API 返回的 JSON 结构：
 *   { "code": 200, "message": "ok", "data": {...} }
 *
 * 使用方式：
 *   Result.success(data)        → 200 + 数据
 *   Result.error("错误信息")     → 500 + 错误信息
 *   Result.error(400, "参数错误") → 自定义状态码
 */
@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "ok", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(500, message, null);
    }
}
