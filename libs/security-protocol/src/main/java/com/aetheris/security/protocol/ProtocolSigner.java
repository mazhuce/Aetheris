package com.aetheris.security.protocol;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.Map;

/** Each operation allocates its own JCA Signature; safe for concurrent callers. */
public final class ProtocolSigner {
    private final String keyId,caller;
    private final KeyPurpose purpose;
    private final PrivateKey privateKey;
    private final Clock clock;
    private final TimePolicy policy;
    public ProtocolSigner(String keyId,KeyPurpose purpose,String caller,PrivateKey privateKey,
                          KeyRegistry registry,Clock clock,TimePolicy policy){
        Rules.require(registry!=null && purpose!=null && clock!=null && policy!=null && privateKey!=null,"Incomplete signer configuration");
        KeyRegistry.Entry registered=registry.require(keyId);
        Rules.require(registered.purpose()==purpose && registered.caller().equals(caller),"Signer does not match registry ownership");
        KeyMaterial.requireEd25519(privateKey);
        try {
            byte[] challenge="Aetheris v2 signing-key binding".getBytes(StandardCharsets.US_ASCII);
            Signature check=Signature.getInstance("Ed25519");check.initSign(privateKey);check.update(challenge);byte[] proof=check.sign();
            check.initVerify(registered.publicKey());check.update(challenge);Rules.require(check.verify(proof),"Private key does not match registry");
        }catch(GeneralSecurityException error){throw new ProtocolException("Invalid signing key binding");}
        this.keyId=keyId;this.purpose=purpose;this.caller=caller;this.privateKey=privateKey;this.clock=clock;this.policy=policy;
    }
    public String sign(UserProof proof){
        Rules.require(purpose==KeyPurpose.USER,"Wrong signing key purpose");Rules.user(proof,clock,policy);
        Rules.require(caller.equals(proof.issuer()),"User issuer does not match signing key");return compact(Rules.USER,proof);
    }
    public String sign(WorkloadRequestProof proof){
        Rules.require(purpose==KeyPurpose.WORKLOAD,"Wrong signing key purpose");Rules.request(proof,clock,policy);
        Rules.require(caller.equals(proof.caller()),"Request caller does not match signing key");return compact(Rules.REQUEST,proof);
    }
    public String sign(WorkloadResponseProof proof){
        Rules.require(purpose==KeyPurpose.WORKLOAD,"Wrong signing key purpose");Rules.response(proof,clock,policy);
        Rules.require(caller.equals(proof.receiver()),"Response receiver does not match signing key");return compact(Rules.RESPONSE,proof);
    }
    private String compact(String type,Object proof){
        String input=Codec.encode(Codec.bytes(Map.of("alg","EdDSA","typ",type,"kid",keyId)))+"."+Codec.encode(Codec.bytes(proof));
        try {Signature signature=Signature.getInstance("Ed25519");signature.initSign(privateKey);signature.update(input.getBytes(StandardCharsets.US_ASCII));return input+"."+Codec.encode(signature.sign());}
        catch(GeneralSecurityException error){throw new ProtocolException("Unable to sign proof");}
    }
    @Override public String toString(){return "ProtocolSigner[keyId="+keyId+",purpose="+purpose+",caller="+caller+"]";}
}
