package com.aetheris.security.protocol;

import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    static final long NOW=1_800_000_000L;
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC);
    static final String NONCE="AQIDBAUGBwgJCgsMDQ4PEA";
    static final String BODY=BodyHash.sha256("{\"id\":1}".getBytes(StandardCharsets.UTF_8));
    static KeyPair user,workflow,metadata;
    static KeyRegistry registry;
    static ProtocolVerifier verifier;
    static ProtocolSigner userSigner,workflowSigner,metadataSigner;
    @BeforeAll static void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");
        user=generator.generateKeyPair();workflow=generator.generateKeyPair();metadata=generator.generateKeyPair();
        registry=new KeyRegistry(List.of(
                new KeyRegistry.Entry("gateway-1",KeyPurpose.USER,"gateway",user.getPublic()),
                new KeyRegistry.Entry("workflow-1",KeyPurpose.WORKLOAD,"workflow",workflow.getPublic()),
                new KeyRegistry.Entry("metadata-1",KeyPurpose.WORKLOAD,"metadata",metadata.getPublic())));
        verifier=new ProtocolVerifier(registry,CLOCK,TimePolicy.defaults());
        userSigner=new ProtocolSigner("gateway-1",KeyPurpose.USER,"gateway",user.getPrivate(),registry,CLOCK,TimePolicy.defaults());
        workflowSigner=new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"workflow",workflow.getPrivate(),registry,CLOCK,TimePolicy.defaults());
        metadataSigner=new ProtocolSigner("metadata-1",KeyPurpose.WORKLOAD,"metadata",metadata.getPrivate(),registry,CLOCK,TimePolicy.defaults());
    }
    static UserProof actor(){return new UserProof(2,"gateway","workflow",7,"alice",3,NOW,NOW+120,"request-1");}
    static WorkloadRequestProof request(){return new WorkloadRequestProof(2,"workflow","metadata","POST","/internal/v2/executions/sql",BODY,NONCE,NOW,NOW+30,"execution.sql",BodyHash.userProof(null));}
    static WorkloadResponseProof response(){return new WorkloadResponseProof(2,"metadata","workflow",NONCE,200,BODY,NOW,NOW+30);}
    static ExpectedRequest expected(){return new ExpectedRequest("metadata","POST","/internal/v2/executions/sql",BODY,"execution.sql",BodyHash.userProof(null));}
    static ExpectedResponse expectedResponse(){return new ExpectedResponse("metadata","workflow",NONCE,200,BODY);}
    @Test void roundTripsAllTypesAndRegistryOwnership() {
        var u=verifier.verifyUser(userSigner.sign(actor()),"workflow");assertEquals(actor(),u.proof());assertEquals("gateway",u.caller());assertEquals("gateway-1",u.keyId());
        var r=verifier.verifyRequest(workflowSigner.sign(request()),expected());assertEquals(request(),r.proof());assertEquals("workflow",r.caller());
        assertEquals(response(),verifier.verifyResponse(metadataSigner.sign(response()),expectedResponse()).proof());
    }
    @Test void signerRejectsMisconfiguredKeysAndClaimedOwner() {
        assertThrows(ProtocolException.class,()->new ProtocolSigner("workflow-1",KeyPurpose.USER,"gateway",workflow.getPrivate(),registry,CLOCK,TimePolicy.defaults()));
        assertThrows(ProtocolException.class,()->new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"quality",workflow.getPrivate(),registry,CLOCK,TimePolicy.defaults()));
        assertThrows(ProtocolException.class,()->new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"workflow",metadata.getPrivate(),registry,CLOCK,TimePolicy.defaults()));
        assertThrows(ProtocolException.class,()->workflowSigner.sign(actor()));
        assertThrows(ProtocolException.class,()->userSigner.sign(request()));
        assertThrows(ProtocolException.class,()->workflowSigner.sign(new WorkloadRequestProof(2,"quality","metadata","POST",request().requestTarget(),BODY,NONCE,NOW,NOW+30,"execution.sql",BodyHash.userProof(null))));
        assertThrows(ProtocolException.class,()->metadataSigner.sign(new WorkloadResponseProof(2,"workflow","metadata",NONCE,200,BODY,NOW,NOW+30)));
    }
    @Test void keyRegistryRejectsMissingDuplicateWrongPurposeAndPhysicalReuse() {
        assertThrows(ProtocolException.class,()->new KeyRegistry(List.of()));
        assertThrows(ProtocolException.class,()->new KeyRegistry(List.of(registry.require("workflow-1"),registry.require("workflow-1"))));
        assertThrows(ProtocolException.class,()->new KeyRegistry(List.of(new KeyRegistry.Entry("a",KeyPurpose.USER,"workflow",user.getPublic()))));
        assertThrows(ProtocolException.class,()->new KeyRegistry(List.of(registry.require("gateway-1"),new KeyRegistry.Entry("b",KeyPurpose.WORKLOAD,"workflow",user.getPublic()))));
        assertThrows(ProtocolException.class,()->registry.require("missing"));
    }
    @TestFactory Stream<DynamicTest> requestBindingFailures() {
        return Stream.of(
                new ExpectedRequest("quality","POST",request().requestTarget(),BODY,"execution.sql",BodyHash.userProof(null)),
                new ExpectedRequest("metadata","GET",request().requestTarget(),BODY,"execution.sql",BodyHash.userProof(null)),
                new ExpectedRequest("metadata","POST","/internal/v2/executions/ingestion",BODY,"execution.sql",BodyHash.userProof(null)),
                new ExpectedRequest("metadata","POST",request().requestTarget(),BodyHash.sha256(new byte[0]),"execution.sql",BodyHash.userProof(null)),
                new ExpectedRequest("metadata","POST",request().requestTarget(),BODY,"execution.query",BodyHash.userProof(null)))
                .map(value->DynamicTest.dynamicTest("binding "+value,()->assertThrows(ProtocolException.class,()->verifier.verifyRequest(workflowSigner.sign(request()),value))));
    }
    @TestFactory Stream<DynamicTest> invalidPaths() {
        return Stream.of("/internal/v2/../admin","/internal/v2/./sql","/internal/v2//sql","/internal/v2/sql/","/internal/v2/sql?q=x","/internal/v2/sql#x","/internal/v2/%2e%2e/sql","/internal/v2/%252f","/internal/v2/a\\b","/internal/v2/中文","/internal/v1/sql","http://metadata/internal/v2/sql","/internal/v2/","/internal/v2/a b")
                .map(path->DynamicTest.dynamicTest(path,()->assertThrows(ProtocolException.class,()->workflowSigner.sign(new WorkloadRequestProof(2,"workflow","metadata","POST",path,BODY,NONCE,NOW,NOW+30,"execution.sql",BodyHash.userProof(null))))));
    }
    @TestFactory Stream<DynamicTest> strictPayloadFailures() {
        String json=userJson();
        return Stream.of(
                json.replace("\"version\":2","\"version\":1"),
                json.replace("\"uid\":7","\"uid\":0"),
                json.replace("\"uid\":7","\"uid\":7.0"),
                json.replace("\"uid\":7","\"uid\":\"7\""),
                json.replace("\"uid\":7","\"uid\":9223372036854775808"),
                json.replace("\"accessVersion\":3","\"accessVersion\":-1"),
                json.replace("\"issuer\":\"gateway\"","\"issuer\":\"serving\""),
                json.replace("\"username\":\"alice\"","\"username\":\"alice\\nadmin\""),
                json.replace("\"username\":\"alice\"","\"username\":\"\\uD800\""),
                json.replace("\"username\":\"alice\"","\"username\":\""+"a".repeat(129)+"\""),
                json.replace("\"uid\":7","\"uid\":7,\"uid\":8"),
                json.replace("\"uid\":7","\"uid\":7,\"role\":\"admin\""),
                json.replace("\"uid\":7,",""),json+" {}", "[]", "null")
                .map(value->DynamicTest.dynamicTest("strict payload "+value.hashCode(),()->assertThrows(ProtocolException.class,()->verifier.verifyUser(raw(user.getPrivate(),header("gateway-1","AETHERIS-USER-V2"),value),"workflow"))));
    }
    @TestFactory Stream<DynamicTest> strictHeaders() {
        return Stream.of("{\"alg\":\"HS256\",\"typ\":\"AETHERIS-USER-V2\",\"kid\":\"gateway-1\"}",
                "{\"alg\":\"none\",\"typ\":\"AETHERIS-USER-V2\",\"kid\":\"gateway-1\"}",
                header("gateway-1","AETHERIS-USER-V2").replace("\"alg\":\"EdDSA\"","\"alg\":\"EdDSA\",\"alg\":\"HS256\""),
                header("gateway-1","AETHERIS-USER-V2").replace("}",",\"jku\":\"http://attacker\"}"),
                header("unknown","AETHERIS-USER-V2"),header("workflow-1","AETHERIS-USER-V2"),header("gateway-1","JWT"))
                .map(value->DynamicTest.dynamicTest(value,()->assertThrows(ProtocolException.class,()->verifier.verifyUser(raw(user.getPrivate(),value,userJson()),"workflow"))));
    }
    @TestFactory Stream<DynamicTest> timeFailures() {
        return Stream.of(new long[]{NOW-1,NOW},new long[]{NOW+6,NOW+30},new long[]{NOW,NOW+121},new long[]{NOW,NOW},new long[]{Long.MIN_VALUE,Long.MAX_VALUE},new long[]{NOW,Long.MAX_VALUE})
                .map(times->DynamicTest.dynamicTest(Arrays.toString(times),()->assertThrows(ProtocolException.class,()->verifier.verifyUser(raw(user.getPrivate(),header("gateway-1","AETHERIS-USER-V2"),userJson().replace("\"issuedAt\":"+NOW,"\"issuedAt\":"+times[0]).replace("\"expiresAt\":"+(NOW+120),"\"expiresAt\":"+times[1])),"workflow"))));
    }
    @Test void verifiesReceivedSigningInputWithoutReserializing() {
        String custom="{ \"kid\" : \"gateway-1\", \"typ\":\"AETHERIS-USER-V2\",\"alg\":\"EdDSA\" }";
        assertEquals("alice",verifier.verifyUser(raw(user.getPrivate(),custom,userJson().replace(",",", ")),"workflow").proof().username());
    }
    @Test void acceptsPublicKeyOnlyVerifierAndRejectsCrossTypAndWrongUserAudience() {
        var publicOnly=new ProtocolVerifier(new KeyRegistry(List.of(registry.require("gateway-1"))),CLOCK,TimePolicy.defaults());
        assertEquals(7,publicOnly.verifyUser(userSigner.sign(actor()),"workflow").proof().uid());
        assertThrows(ProtocolException.class,()->verifier.verifyUser(userSigner.sign(actor()),"metadata"));
        assertThrows(ProtocolException.class,()->verifier.verifyRequest(metadataSigner.sign(response()),expected()));
        assertThrows(ProtocolException.class,()->verifier.verifyResponse(workflowSigner.sign(request()),expectedResponse()));
    }
    @TestFactory Stream<DynamicTest> compactEncodingFailures() {
        return Stream.of("", "abc.def", "abc.def.ghi.jkl", "eyJ1aWQiOiJhZG1pbiJ9."+"0".repeat(64),"a".repeat(13000),"=.e30.aaa","e30=.e30.aaa","e30.e30.A+/")
                .map(value->DynamicTest.dynamicTest("encoding "+value.length(),()->assertThrows(ProtocolException.class,()->verifier.verifyUser(value,"workflow"))));
    }
    @Test void tamperingAndWrongSignatureAreRejected() {
        String token=userSigner.sign(actor());String[] p=token.split("\\.");
        assertThrows(ProtocolException.class,()->verifier.verifyUser(p[0]+"."+enc(userJson().replace("alice","admin"))+"."+p[2],"workflow"));
        assertThrows(ProtocolException.class,()->verifier.verifyUser(raw(workflow.getPrivate(),header("gateway-1","AETHERIS-USER-V2"),userJson()),"workflow"));
        assertThrows(ProtocolException.class,()->verifier.verifyUser(token+"=","workflow"));
    }
    @TestFactory Stream<DynamicTest> responseBindingFailures() {
        return Stream.of(new ExpectedResponse("quality","workflow",NONCE,200,BODY),new ExpectedResponse("metadata","quality",NONCE,200,BODY),new ExpectedResponse("metadata","workflow",Nonce.random(),200,BODY),new ExpectedResponse("metadata","workflow",NONCE,403,BODY),new ExpectedResponse("metadata","workflow",NONCE,200,BodyHash.sha256(new byte[0])))
                .map(value->DynamicTest.dynamicTest("response "+value,()->assertThrows(ProtocolException.class,()->verifier.verifyResponse(metadataSigner.sign(response()),value))));
    }
    @Test void concurrentSigningAndVerificationAreThreadSafe() throws Exception {
        try(var executor=Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> jobs=new ArrayList<>();
            for(int i=0;i<160;i++) jobs.add(()->verifier.verifyRequest(workflowSigner.sign(request()),expected()).proof().equals(request()));
            for(Future<Boolean> result:executor.invokeAll(jobs))assertTrue(result.get());
        }
    }
    static String userJson(){return "{\"version\":2,\"issuer\":\"gateway\",\"audience\":\"workflow\",\"uid\":7,\"username\":\"alice\",\"accessVersion\":3,\"issuedAt\":"+NOW+",\"expiresAt\":"+(NOW+120)+",\"requestId\":\"request-1\"}";}
    static String header(String kid,String typ){return "{\"alg\":\"EdDSA\",\"typ\":\""+typ+"\",\"kid\":\""+kid+"\"}";}
    static String enc(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    static String raw(PrivateKey key,String header,String payload) {
        try {String input=enc(header)+"."+enc(payload);Signature signature=Signature.getInstance("Ed25519");signature.initSign(key);signature.update(input.getBytes(StandardCharsets.US_ASCII));return input+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());}
        catch(Exception error){throw new AssertionError(error);}
    }
}
