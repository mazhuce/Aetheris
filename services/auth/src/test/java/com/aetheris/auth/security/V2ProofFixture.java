package com.aetheris.auth.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import com.aetheris.auth.config.AuthV2SecurityConfiguration;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Actual ephemeral Ed25519 keys; verifier and signer are never mocked. */
public final class V2ProofFixture {
    public final Clock clock=Clock.systemUTC();
    public final KeyRegistry registry;
    public final ProtocolVerifier verifier;
    public final Map<String,KeyPair> keys=new HashMap<>();
    public final ProtocolSigner gateway,auth,system;
    public V2ProofFixture() {
        try {
            var generator=KeyPairGenerator.getInstance("Ed25519");
            for(String name:List.of("user","auth","system","metadata","serving","gateway","ingestion","quality","workflow","outsider")) keys.put(name,generator.generateKeyPair());
            registry=new KeyRegistry(List.of(new KeyRegistry.Entry("user-key",KeyPurpose.USER,"gateway",keys.get("user").getPublic()),
                    new KeyRegistry.Entry("auth-key",KeyPurpose.WORKLOAD,"auth",keys.get("auth").getPublic()),
                    new KeyRegistry.Entry("system-key",KeyPurpose.WORKLOAD,"system",keys.get("system").getPublic()),
                    new KeyRegistry.Entry("metadata-key",KeyPurpose.WORKLOAD,"metadata",keys.get("metadata").getPublic()),
                    new KeyRegistry.Entry("serving-key",KeyPurpose.WORKLOAD,"serving",keys.get("serving").getPublic()),
                    new KeyRegistry.Entry("gateway-key",KeyPurpose.WORKLOAD,"gateway",keys.get("gateway").getPublic()),
                    new KeyRegistry.Entry("ingestion-key",KeyPurpose.WORKLOAD,"ingestion",keys.get("ingestion").getPublic()),
                    new KeyRegistry.Entry("quality-key",KeyPurpose.WORKLOAD,"quality",keys.get("quality").getPublic()),
                    new KeyRegistry.Entry("workflow-key",KeyPurpose.WORKLOAD,"workflow",keys.get("workflow").getPublic()),
                    new KeyRegistry.Entry("outsider-key",KeyPurpose.WORKLOAD,"outsider",keys.get("outsider").getPublic())));
            verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
            gateway=signer("user","gateway",KeyPurpose.USER); auth=signer("auth","auth",KeyPurpose.WORKLOAD); system=signer("system","system",KeyPurpose.WORKLOAD);
        }catch(GeneralSecurityException failure){throw new IllegalStateException(failure);}
    }
    public ProtocolSigner signer(String name,String caller,KeyPurpose purpose) {
        return new ProtocolSigner(name+"-key",purpose,caller,keys.get(name).getPrivate(),registry,clock,TimePolicy.defaults());
    }
    public String user(String audience,long uid,String username,long av) {
        long now=clock.instant().getEpochSecond();
        return gateway.sign(new UserProof(2,"gateway",audience,uid,username,av,now,now+60,"auth-v2-test"));
    }
    public String request(String path,String operation,byte[] body,String user) {
        boolean metadata=path.endsWith("/check-batch") || path.endsWith("/visibility") || path.endsWith("/may-mutate") || path.equals("/internal/v2/permissions/check");
        return request(metadata?signer("metadata","metadata",KeyPurpose.WORKLOAD):system,metadata?"metadata":"system","POST",path,operation,body,user);
    }
    public String request(ProtocolSigner signer,String caller,String method,String path,String operation,byte[] body,String user) {
        long now=clock.instant().getEpochSecond();
        String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return signer.sign(new WorkloadRequestProof(2,caller,"auth",method,path,BodyHash.sha256(body),nonce,now,now+30,operation,BodyHash.userProof(user)));
    }
    public WorkloadProofFilter workload(NonceStore nonces,SessionValidator sessions) {
        return new WorkloadProofFilter("auth",verifier,auth,AuthV2SecurityConfiguration.workloadRoutes(),nonces,sessions,clock,TimePolicy.defaults());
    }
    public DirectUserProofFilter direct(SessionValidator sessions) {
        return new DirectUserProofFilter("auth",verifier,sessions,AuthV2SecurityConfiguration.directRoutes());
    }
}
