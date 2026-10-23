package com.aetheris.security.protocol;

import java.security.SecureRandom;
import java.util.Base64;

/** Generation only. Receivers MUST separately persist and atomically claim nonces. */
public final class Nonce {
    private static final SecureRandom RANDOM=new SecureRandom();
    private Nonce(){ }
    public static String random(){byte[] bytes=new byte[24];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
