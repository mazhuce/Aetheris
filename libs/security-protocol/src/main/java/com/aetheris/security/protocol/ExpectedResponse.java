package com.aetheris.security.protocol;

/** receiver=remote service; caller=local requesting service. */
public record ExpectedResponse(String receiver, String caller, String requestNonce, int status,
                               String rawBodySha256) { }
