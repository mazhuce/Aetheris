package com.aetheris.security.servlet;

/** Must check the current UID/name/accessVersion binding and active status; no role from a proof. */
@FunctionalInterface
public interface SessionValidator {
    boolean isActive(long uid, String username, long accessVersion) throws Exception;
}
