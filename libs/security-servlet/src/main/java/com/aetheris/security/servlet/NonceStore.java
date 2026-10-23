package com.aetheris.security.servlet;

/** Atomic persistent claim across all receiver replicas. true only for the first (caller,nonce).
 * expiresAt is epoch seconds; never use this short nonce TTL to delete execution receipts. */
@FunctionalInterface
public interface NonceStore {
    boolean claim(String caller, String nonce, long expiresAt) throws Exception;
}
