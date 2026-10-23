package com.aetheris.metadata.security;

/** Retired compatibility type; shared HMAC is never accepted and this is not a Spring bean. */
@Deprecated
public final class IdentityVerifier {
    public static final String IDENTITY_HEADER="X-Aetheris-Identity";
    public IdentityVerifier(String ignored,long ignoredSkew) { }
    public boolean isConfigured() {return false;}
    public AetherisIdentity verify(String ignored) {return null;}
    public AetherisIdentity verify(jakarta.servlet.http.HttpServletRequest ignored) {return null;}
}
