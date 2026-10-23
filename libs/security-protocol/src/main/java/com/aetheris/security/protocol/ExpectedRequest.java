package com.aetheris.security.protocol;

/** Compute the hash from received bytes, and all other values from the actual HTTP request. */
public record ExpectedRequest(String audience, String method, String requestTarget,
                              String rawBodySha256, String operation, String userProofSha256) { }
