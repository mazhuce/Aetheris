package com.aetheris.auth.web;

/** 统一错误响应体：{code, message}。 */
public record ApiError(String code, String message) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message);
    }
}