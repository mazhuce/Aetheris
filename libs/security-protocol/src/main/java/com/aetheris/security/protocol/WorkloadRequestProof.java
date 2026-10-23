package com.aetheris.security.protocol;

/** Resource/run/fence/definition bindings belong in the hashed raw request body. */
public record WorkloadRequestProof(int version, String caller, String audience, String method,
                                   String requestTarget, String rawBodySha256, String nonce,
                                   long issuedAt, long expiresAt, String operation,
                                   String userProofSha256) { }
