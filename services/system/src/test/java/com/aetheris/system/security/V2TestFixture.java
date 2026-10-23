package com.aetheris.system.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import com.aetheris.system.config.SystemV2SecurityConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Real, memory-only Ed25519 proofs. Local sessions and nonce storage are explicitly test doubles. */
public final class V2TestFixture {
    public static final Clock CLOCK=Clock.systemUTC();
    public static final ObjectMapper JSON=new ObjectMapper();
    public static final KeyRegistry REGISTRY;
    public static final ProtocolVerifier VERIFIER;
    public static final Map<String,ProtocolSigner> SIGNERS=new HashMap<>();
    private static final KeyPair GATEWAY_USER;
    static {
        try {
            var generator=KeyPairGenerator.getInstance("Ed25519");var entries=new ArrayList<KeyRegistry.Entry>();var keys=new HashMap<String,KeyPair>();
            for(String name:List.of("system","gateway","auth","metadata","workflow","quality","ingestion","serving","governance","ai-agent")) {
                var pair=generator.generateKeyPair();keys.put(name,pair);entries.add(new KeyRegistry.Entry(name+"-test",KeyPurpose.WORKLOAD,name,pair.getPublic()));
            }
            GATEWAY_USER=generator.generateKeyPair();entries.add(new KeyRegistry.Entry("gateway-user-test",KeyPurpose.USER,"gateway",GATEWAY_USER.getPublic()));
            REGISTRY=new KeyRegistry(entries);VERIFIER=new ProtocolVerifier(REGISTRY,CLOCK,TimePolicy.defaults());
            keys.forEach((name,pair)->SIGNERS.put(name,new ProtocolSigner(name+"-test",KeyPurpose.WORKLOAD,name,pair.getPrivate(),REGISTRY,CLOCK,TimePolicy.defaults())));
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    public static String user(long uid,String name,String audience){return userAt(uid,name,audience,CLOCK.instant().getEpochSecond());}
    public static String userAt(long uid,String name,String audience,long now) {
        var signer=new ProtocolSigner("gateway-user-test",KeyPurpose.USER,"gateway",GATEWAY_USER.getPrivate(),REGISTRY,Clock.fixed(Instant.ofEpochSecond(now),ZoneOffset.UTC),TimePolicy.defaults());
        return signer.sign(new UserProof(2,"gateway",audience,uid,name,3,now,now+120,"system-test-request"));
    }
    public static DirectUserProofFilter direct(){return new DirectUserProofFilter("system",VERIFIER,(uid,name,av)->true,SystemV2SecurityConfiguration.directRoutes());}
    public static RequestPostProcessor trusted(long uid,String name) {
        return request->{
            request.addHeader("X-Aetheris-User-Proof",user(uid,name,"system"));
            try {direct().doFilter(request,new MockHttpServletResponse(),(req,res)->{});}
            catch(Exception error){throw new IllegalStateException(error);}
            return request;
        };
    }
    public static WorkloadProofFilter workload() {
        Set<String> claims=ConcurrentHashMap.newKeySet();
        return new WorkloadProofFilter("system",VERIFIER,SIGNERS.get("system"),SystemV2SecurityConfiguration.workloadRoutes(),
                (caller,nonce,expiry)->claims.add(caller+":"+nonce),(uid,name,av)->true,CLOCK,TimePolicy.defaults());
    }
    public static String workloadProof(String caller,String path,byte[] body,String nonce,String operation,String userProof) {
        long now=CLOCK.instant().getEpochSecond();return SIGNERS.get(caller).sign(new WorkloadRequestProof(2,caller,"system","POST",path,BodyHash.sha256(body),nonce,now,now+30,operation,BodyHash.userProof(userProof)));
    }
    public static MockHttpServletResponse invoke(String caller,String path,String operation,Object body,FilterChain chain) throws Exception {
        byte[] bytes=JSON.writeValueAsBytes(body);String nonce=Nonce.random();
        var request=new MockHttpServletRequest("POST",path);request.setContent(bytes);request.addHeader("X-Aetheris-Workload-Proof",workloadProof(caller,path,bytes,nonce,operation,null));
        var response=new MockHttpServletResponse();workload().doFilter(request,response,chain);
        VERIFIER.verifyResponse(response.getHeader("X-Aetheris-Response-Proof"),new ExpectedResponse("system",caller,nonce,response.getStatus(),BodyHash.sha256(response.getContentAsByteArray())));
        return response;
    }
}
