package com.aetheris.auth.web;

/** 权限不足（403 PERMISSION_DENIED）。 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}