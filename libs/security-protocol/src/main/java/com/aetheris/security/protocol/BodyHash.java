package com.aetheris.security.protocol;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

public final class BodyHash {
    private BodyHash() { }
    /** Bind the exact single forwarded compact header; absence is the SHA-256 of zero bytes. */
    public static String userProof(String compact) {
        if(compact==null)return sha256(new byte[0]);
        if(compact.isEmpty() || compact.length()>12288 || compact.chars().anyMatch(c->c<33 || c>126))
            throw new ProtocolException("Invalid compact user proof header");
        return sha256(compact.getBytes(StandardCharsets.US_ASCII));
    }
    public static String sha256(byte[] rawBody) {
        if(rawBody==null)throw new ProtocolException("Missing raw body bytes");
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
}
