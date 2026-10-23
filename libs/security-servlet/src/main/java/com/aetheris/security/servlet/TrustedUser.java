package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import jakarta.servlet.http.HttpServletRequest;

/** Shared direct/forwarded identity, created only after cryptographic and live-session validation. No role. */
public final class TrustedUser {
    public static final String ATTRIBUTE=TrustedUser.class.getName()+".authenticated";
    private final ProtocolVerifier.Verified<UserProof> verified;
    private final String compactProof;
    TrustedUser(ProtocolVerifier.Verified<UserProof> verified,String compactProof){this.verified=verified;this.compactProof=compactProof;}
    public UserProof proof(){return verified.proof();}
    public ProtocolVerifier.Verified<UserProof> verified(){return verified;}
    public String compactProof(){return compactProof;}
    public static TrustedUser require(HttpServletRequest request){
        if(request.getAttribute(ATTRIBUTE) instanceof TrustedUser user)return user;
        throw new IllegalStateException("Missing authenticated user");
    }
    @Override public String toString(){return "TrustedUser[redacted]";}
}
