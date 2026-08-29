package com.aetheris.workflow.service;

/**
 * SQL 执行失败。
 *
 * <p>message 已是可安全展示给用户的文案：metadata 侧在抛出前已把驱动原文替换掉，
 * 避免连接串或库内结构细节经任务日志泄露。
 */
public class SqlExecutionException extends RuntimeException {

    public SqlExecutionException(String message) {
        super(message);
    }
}
