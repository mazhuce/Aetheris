package com.aetheris.metadata.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import com.aetheris.metadata.config.MetadataV2SecurityConfiguration;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Actual temporary Ed25519 keys. In-memory session/nonce are fixture-only. */
public final class MetadataProofFixture {
    public final Clock clock=Clock.systemUTC();
    public final ProtocolVerifier verifier;
    public final ProtocolSigner user;
    private final Map<String,ProtocolSigner> workloads=new HashMap<>();
    public MetadataProofFixture() {
        try {
            var gen=KeyPairGenerator.getInstance("Ed25519");var pairs=new HashMap<String,KeyPair>();
            var entries=new ArrayList<KeyRegistry.Entry>();var userKey=gen.generateKeyPair();
            entries.add(new KeyRegistry.Entry("user-key",KeyPurpose.USER,"gateway",userKey.getPublic()));
            for(String service:List.of("metadata","auth","serving","ai-agent","workflow","ingestion","quality")) {
                var pair=gen.generateKeyPair();pairs.put(service,pair);entries.add(new KeyRegistry.Entry(service+"-key",KeyPurpose.WORKLOAD,service,pair.getPublic()));
            }
            var registry=new KeyRegistry(entries);verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
            user=new ProtocolSigner("user-key",KeyPurpose.USER,"gateway",userKey.getPrivate(),registry,clock,TimePolicy.defaults());
            pairs.forEach((service,pair)->workloads.put(service,new ProtocolSigner(service+"-key",KeyPurpose.WORKLOAD,service,pair.getPrivate(),registry,clock,TimePolicy.defaults())));
        }catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
    public ProtocolSigner workload(String service){return workloads.get(service);}
    public String user(String audience){return user(audience,42,"alice",3);}
    public String user(String audience,long uid,String name,long av){long now=clock.instant().getEpochSecond();
        return user.sign(new UserProof(2,"gateway",audience,uid,name,av,now,now+120,"metadata-v2-test"));}
    public String request(String caller,String path,String op,byte[] body,String compact){long now=clock.instant().getEpochSecond();
        return workload(caller).sign(new WorkloadRequestProof(2,caller,"metadata","POST",path,BodyHash.sha256(body),Nonce.random(),now,now+30,op,BodyHash.userProof(compact)));}
    public WorkloadProofFilter filter(NonceStore nonces,SessionValidator sessions){return new WorkloadProofFilter("metadata",verifier,workload("metadata"),
            MetadataV2SecurityConfiguration.workloadRoutes(),nonces,sessions,clock,TimePolicy.defaults());}
    public DirectUserProofFilter direct(SessionValidator sessions){return new DirectUserProofFilter("metadata",verifier,sessions,
            (method,path)->DirectRoutePolicy.Decision.PROTECTED);}
}
