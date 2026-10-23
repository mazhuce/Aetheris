package com.aetheris.security.servlet;

/** Explicit server policy for every /api path. Null/exception means reject, never public. */
@FunctionalInterface
public interface DirectRoutePolicy {
    Decision resolve(String method,String rawPath);
    enum Decision { PROTECTED, PUBLIC, REJECT }
}
