package com.aetheris.system.security;

import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** Compatibility view of the identity already verified by the v2 DirectUserProofFilter. */
@Component
public class IdentityVerifier {
    public static final String IDENTITY_HEADER="X-Aetheris-Identity";
    public IdentityVerifier() { }
    /** Old Java construction cannot enable the removed shared-HMAC protocol. */
    @Deprecated public IdentityVerifier(String ignoredToken,long ignoredSkew) { }
    @Deprecated public boolean isConfigured(){return false;}
    @Deprecated public AetherisIdentity verify(String ignoredLegacyHeader){return null;}
    public AetherisIdentity verify(HttpServletRequest request) {
        if(request==null)return null;
        try {var proof=TrustedUser.require(request).proof();return new AetherisIdentity(proof.uid(),proof.username());}
        catch(IllegalStateException missing){return null;}
    }
}
