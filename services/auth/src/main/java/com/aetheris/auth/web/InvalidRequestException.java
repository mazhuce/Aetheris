package com.aetheris.auth.web;

/** 请求参数或状态非法（400 VALIDATION_FAILED）。 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}