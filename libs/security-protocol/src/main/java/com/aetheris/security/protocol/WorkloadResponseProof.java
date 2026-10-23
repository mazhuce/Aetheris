package com.aetheris.security.protocol;

public record WorkloadResponseProof(int version, String receiver, String caller, String requestNonce,
                                    int status, String rawBodySha256, long issuedAt, long expiresAt) { }
