package com.aetheris.security.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Clock;
import java.util.Set;

/** Authentication only: caller ACL, live auth, DB nonce/receipt and business RBAC remain mandatory. */
public final class ProtocolVerifier {
    public static final String USER_TYPE=Rules.USER,REQUEST_TYPE=Rules.REQUEST,RESPONSE_TYPE=Rules.RESPONSE;
    private static final Set<String> HEADER=Set.of("alg","typ","kid");
    private static final Set<String> USER=Set.of("version","issuer","audience","uid","username","accessVersion","issuedAt","expiresAt","requestId");
    private static final Set<String> REQUEST=Set.of("version","caller","audience","method","requestTarget","rawBodySha256","nonce","issuedAt","expiresAt","operation","userProofSha256");
    private static final Set<String> RESPONSE=Set.of("version","receiver","caller","requestNonce","status","rawBodySha256","issuedAt","expiresAt");
    private final KeyRegistry registry;
    private final Clock clock;
    private final TimePolicy policy;
    public ProtocolVerifier(KeyRegistry registry,Clock clock,TimePolicy policy){
        Rules.require(registry!=null && clock!=null && policy!=null,"Incomplete verifier configuration");this.registry=registry;this.clock=clock;this.policy=policy;
    }
    /** This result cannot be constructed by application code or a deserializer. */
    public static final class Verified<T> {
        private final T proof;private final String keyId,caller;
        private Verified(T proof,KeyRegistry.Entry key){this.proof=proof;this.keyId=key.keyId();this.caller=key.caller();}
        public T proof(){return proof;}public String keyId(){return keyId;}public String caller(){return caller;}
    }
    public Verified<UserProof> verifyUser(String compact,String expectedAudience){
        Rules.id(expectedAudience);Parsed parsed=parse(compact,Rules.USER,KeyPurpose.USER,USER);JsonNode n=parsed.node();
        UserProof proof=new UserProof(integer(n,"version"),text(n,"issuer"),text(n,"audience"),number(n,"uid"),text(n,"username"),number(n,"accessVersion"),number(n,"issuedAt"),number(n,"expiresAt"),text(n,"requestId"));
        Rules.user(proof,clock,policy);Rules.require(parsed.key().caller().equals(proof.issuer()) && proof.audience().equals(expectedAudience),"User issuer or audience mismatch");return new Verified<>(proof,parsed.key());
    }
    public Verified<WorkloadRequestProof> verifyRequest(String compact,ExpectedRequest expected){
        Rules.require(expected!=null,"Missing expected request");Rules.id(expected.audience());Rules.method(expected.method());Rules.path(expected.requestTarget());Rules.hash(expected.rawBodySha256());Rules.operation(expected.operation());Rules.hash(expected.userProofSha256());
        Parsed parsed=parse(compact,Rules.REQUEST,KeyPurpose.WORKLOAD,REQUEST);JsonNode n=parsed.node();
        WorkloadRequestProof proof=new WorkloadRequestProof(integer(n,"version"),text(n,"caller"),text(n,"audience"),text(n,"method"),text(n,"requestTarget"),text(n,"rawBodySha256"),text(n,"nonce"),number(n,"issuedAt"),number(n,"expiresAt"),text(n,"operation"),text(n,"userProofSha256"));
        Rules.request(proof,clock,policy);Rules.require(parsed.key().caller().equals(proof.caller()),"Request caller does not match registry");
        Rules.require(proof.audience().equals(expected.audience()) && proof.method().equals(expected.method()) && proof.requestTarget().equals(expected.requestTarget()) && proof.rawBodySha256().equals(expected.rawBodySha256()) && proof.operation().equals(expected.operation()) && proof.userProofSha256().equals(expected.userProofSha256()),"Request binding mismatch");return new Verified<>(proof,parsed.key());
    }
    public Verified<WorkloadResponseProof> verifyResponse(String compact,ExpectedResponse expected){
        Rules.require(expected!=null,"Missing expected response");Rules.id(expected.receiver());Rules.id(expected.caller());Rules.nonce(expected.requestNonce());Rules.status(expected.status());Rules.hash(expected.rawBodySha256());
        Parsed parsed=parse(compact,Rules.RESPONSE,KeyPurpose.WORKLOAD,RESPONSE);JsonNode n=parsed.node();
        WorkloadResponseProof proof=new WorkloadResponseProof(integer(n,"version"),text(n,"receiver"),text(n,"caller"),text(n,"requestNonce"),integer(n,"status"),text(n,"rawBodySha256"),number(n,"issuedAt"),number(n,"expiresAt"));
        Rules.response(proof,clock,policy);Rules.require(parsed.key().caller().equals(proof.receiver()),"Response receiver does not match registry");
        Rules.require(proof.receiver().equals(expected.receiver()) && proof.caller().equals(expected.caller()) && proof.requestNonce().equals(expected.requestNonce()) && proof.status()==expected.status() && proof.rawBodySha256().equals(expected.rawBodySha256()),"Response binding mismatch");return new Verified<>(proof,parsed.key());
    }
    private Parsed parse(String token,String type,KeyPurpose purpose,Set<String> fields){
        Rules.require(token!=null && !token.isBlank() && token.length()<=12288,"Missing or oversized compact proof");
        String[] parts=token.split("\\.",-1);Rules.require(parts.length==3,"Invalid compact structure");
        byte[] header=Codec.decode(parts[0],768),payload=Codec.decode(parts[1],8192),signature=Codec.decode(parts[2],86);
        Rules.require(signature.length==64,"Invalid Ed25519 signature length");
        JsonNode h=Codec.object(header,HEADER);
        Rules.require("EdDSA".equals(text(h,"alg")) && type.equals(text(h,"typ")),"Unsupported algorithm or proof type");
        KeyRegistry.Entry key=registry.require(text(h,"kid"));Rules.require(key.purpose()==purpose,"Wrong verification key purpose");
        try {
            Signature verifier=Signature.getInstance("Ed25519");verifier.initVerify(key.publicKey());
            // Verify the actual received base64url signing input, never a reserialized JSON tree.
            verifier.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));Rules.require(verifier.verify(signature),"Invalid proof signature");
        }catch(GeneralSecurityException error){throw new ProtocolException("Invalid proof signature");}
        return new Parsed(key,Codec.object(payload,fields));
    }
    private record Parsed(KeyRegistry.Entry key,JsonNode node){ }
    private static String text(JsonNode n,String field){return Codec.text(n,field);}
    private static long number(JsonNode n,String field){return Codec.number(n,field);}
    private static int integer(JsonNode n,String field){return Codec.integer(n,field);}
}
