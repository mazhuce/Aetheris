package com.aetheris.auth.web;

/** 状态冲突（409 CONFLICT），如重复编码、存在关联对象无法删除。 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}