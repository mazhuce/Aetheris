package com.aetheris.auth.web;

/** 资源不存在（404 NOT_FOUND）。 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}