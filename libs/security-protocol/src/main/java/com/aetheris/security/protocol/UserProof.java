package com.aetheris.security.protocol;

/** Epoch seconds; no role or implicit authorization claims. */
public record UserProof(int version, String issuer, String audience, long uid, String username,
                        long accessVersion, long issuedAt, long expiresAt, String requestId) { }
