package com.aetheris.security.protocol;

/** Deliberately excludes tokens, bodies and key material from diagnostic messages. */
public final class ProtocolException extends IllegalArgumentException {
    public ProtocolException(String reason) { super(reason); }
}
