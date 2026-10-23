package com.aetheris.aiagent.security;

import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;

/** Only immutable admitted identity crosses the worker boundary; never a servlet request or headers. */
public final class AiProofContext {
    private static final ThreadLocal<Snapshot> CURRENT=new ThreadLocal<>();
    private AiProofContext(){}
    public static final class Snapshot {
        private final TrustedUser user;
        private Snapshot(TrustedUser user){this.user=user;}
        public TrustedUser user(){return user;}
        public String compact(){return user.compactProof();}
        public String requestId(){return user.proof().requestId();}
        @Override public String toString(){return "AiIdentitySnapshot[redacted]";}
    }
    public static Snapshot snapshot(HttpServletRequest request){
        if(request==null || !(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user))
            throw new IllegalStateException("Missing verified AI identity");
        return new Snapshot(user);
    }
    public static Snapshot current(){return CURRENT.get();}
    public static void attach(Snapshot snapshot){if(snapshot==null)throw new IllegalArgumentException("Missing AI identity");CURRENT.set(snapshot);}
    public static void clear(){CURRENT.remove();}
}
