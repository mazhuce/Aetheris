package com.aetheris.aiagent.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import org.springframework.mock.web.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual ephemeral Ed25519; no production key or model provider. */
public final class AiTestKeys {
    public final Clock clock;public final KeyRegistry registry;public final ProtocolVerifier verifier;
    public final Map<String,KeyPair> pairs=new HashMap<>();public final ProtocolSigner user,ai,metadata,auth;
    public AiTestKeys(){this(Clock.systemUTC());}
    public AiTestKeys(Clock clock){
        this.clock=clock;
        try {var generator=KeyPairGenerator.getInstance("Ed25519");for(String key:List.of("user","ai-agent","metadata","auth"))pairs.put(key,generator.generateKeyPair());}
        catch(GeneralSecurityException error){throw new IllegalStateException(error);}
        var entries=new ArrayList<KeyRegistry.Entry>();
        for(var e:pairs.entrySet())entries.add(new KeyRegistry.Entry(e.getKey(),e.getKey().equals("user")?KeyPurpose.USER:KeyPurpose.WORKLOAD,e.getKey().equals("user")?"gateway":e.getKey(),e.getValue().getPublic()));
        registry=new KeyRegistry(entries);verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
        user=signer("user","gateway",KeyPurpose.USER);ai=signer("ai-agent","ai-agent",KeyPurpose.WORKLOAD);metadata=signer("metadata","metadata",KeyPurpose.WORKLOAD);auth=signer("auth","auth",KeyPurpose.WORKLOAD);
    }
    private ProtocolSigner signer(String key,String caller,KeyPurpose purpose){return new ProtocolSigner(key,purpose,caller,pairs.get(key).getPrivate(),registry,clock,TimePolicy.defaults());}
    public String token(String name){return token(name,clock.instant().getEpochSecond(),60);}
    public String token(String name,long issued,long ttl){return user.sign(new UserProof(2,"gateway","ai-agent",name.equals("bob")?8:7,name,3,issued,issued+ttl,"request-"+name));}
    public MockHttpServletRequest authenticated(String name) throws Exception {
        String token=token(name);var original=new MockHttpServletRequest("POST","/api/ai/chat");original.addHeader("X-Aetheris-User-Proof",token);
        var captured=new AtomicReference<TrustedUser>();
        new DirectUserProofFilter("ai-agent",verifier,(u,n,v)->true,(m,p)->DirectRoutePolicy.Decision.PROTECTED).doFilter(original,new MockHttpServletResponse(),(r,s)->captured.set(TrustedUser.require((jakarta.servlet.http.HttpServletRequest)r)));
        original.setAttribute(TrustedUser.ATTRIBUTE,Objects.requireNonNull(captured.get()));return original;
    }
}
