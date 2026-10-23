package com.aetheris.security.protocol;

import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class BoundaryTest {
    @BeforeAll static void setup()throws Exception{ProtocolTest.setup();}
    static String requestJson(){return new String(Codec.bytes(ProtocolTest.request()),StandardCharsets.UTF_8);}
    static String responseJson(){return new String(Codec.bytes(ProtocolTest.response()),StandardCharsets.UTF_8);}
    static String requestToken(String payload){return ProtocolTest.raw(ProtocolTest.workflow.getPrivate(),ProtocolTest.header("workflow-1",ProtocolVerifier.REQUEST_TYPE),payload);}
    static String responseToken(String payload){return ProtocolTest.raw(ProtocolTest.metadata.getPrivate(),ProtocolTest.header("metadata-1",ProtocolVerifier.RESPONSE_TYPE),payload);}
    @TestFactory Stream<DynamicTest> signedRequestClaimFailures(){
        String json=requestJson();
        return Stream.of(
                json.replace("\"caller\":\"workflow\"","\"caller\":\"quality\""),
                json.replace("\"caller\":\"workflow\"","\"caller\":\"Workflow\""),
                json.replace("\"method\":\"POST\"","\"method\":\"post\""),
                json.replace("\"method\":\"POST\"","\"method\":\"TRACE\""),
                json.replace("\"nonce\":\""+ProtocolTest.NONCE+"\"","\"nonce\":\"YWJj\""),
                json.replace(ProtocolTest.NONCE,"a".repeat(87)),
                json.replace(ProtocolTest.BODY,ProtocolTest.BODY.toUpperCase(Locale.ROOT)),
                json.replace("execution.sql","execution sql"),
                json.replace("/internal/v2/executions/sql","/internal/v2/%2fexecutions/sql"),
                json.replace("/internal/v2/executions/sql","/internal/v2/executions/../sql"),
                json.replace("\"expiresAt\":1800000030","\"expiresAt\":1800000031"),
                json.replace("\"issuedAt\":1800000000","\"issuedAt\":1800000006"),
                json.replace("\"version\":2","\"version\":2147483648"),
                json.replace("\"version\":2","\"version\":2,\"caller\":\"workflow\""),
                json.replace("\"version\":2","\"version\":2,\"owner\":\"admin\""),
                json.replace("\"operation\":\"execution.sql\"","\"operation\":null"))
                .map(value->DynamicTest.dynamicTest("signed request "+value.hashCode(),()->assertThrows(ProtocolException.class,()->ProtocolTest.verifier.verifyRequest(requestToken(value),ProtocolTest.expected()))));
    }
    @TestFactory Stream<DynamicTest> signedResponseClaimFailures(){
        String json=responseJson();
        return Stream.of(json.replace("\"receiver\":\"metadata\"","\"receiver\":\"workflow\""),
                json.replace("\"status\":200","\"status\":99"),json.replace("\"status\":200","\"status\":600"),
                json.replace("\"status\":200","\"status\":200.0"),
                json.replace("\"status\":200","\"status\":200,\"status\":403"),
                json.replace("\"status\":200","\"status\":200,\"operation\":\"admin.manage\""),
                json.replace("\"requestNonce\":\""+ProtocolTest.NONCE+"\"","\"requestNonce\":\"a\""),
                json.replace("\"expiresAt\":1800000030","\"expiresAt\":1800000031"))
                .map(value->DynamicTest.dynamicTest("signed response "+value.hashCode(),()->assertThrows(ProtocolException.class,()->ProtocolTest.verifier.verifyResponse(responseToken(value),ProtocolTest.expectedResponse()))));
    }
    @Test void timeBoundariesAndPolicyHardLimits(){
        assertEquals(ProtocolTest.actor(),ProtocolTest.verifier.verifyUser(ProtocolTest.userSigner.sign(ProtocolTest.actor()),"workflow").proof());
        var atExpiry=new ProtocolVerifier(ProtocolTest.registry,Clock.fixed(Instant.ofEpochSecond(ProtocolTest.NOW+120),ZoneOffset.UTC),TimePolicy.defaults());
        assertThrows(ProtocolException.class,()->atExpiry.verifyUser(ProtocolTest.userSigner.sign(ProtocolTest.actor()),"workflow"));
        var atLastSecond=new ProtocolVerifier(ProtocolTest.registry,Clock.fixed(Instant.ofEpochSecond(ProtocolTest.NOW+119),ZoneOffset.UTC),TimePolicy.defaults());
        assertEquals(7,atLastSecond.verifyUser(ProtocolTest.userSigner.sign(ProtocolTest.actor()),"workflow").proof().uid());
        String future=ProtocolTest.userJson().replace("\"issuedAt\":1800000000","\"issuedAt\":1800000005");
        assertEquals(ProtocolTest.NOW+5,ProtocolTest.verifier.verifyUser(ProtocolTest.raw(ProtocolTest.user.getPrivate(),ProtocolTest.header("gateway-1",ProtocolVerifier.USER_TYPE),future),"workflow").proof().issuedAt());
        assertThrows(ProtocolException.class,()->new TimePolicy(0,30,5));
        assertThrows(ProtocolException.class,()->new TimePolicy(120,0,5));
        assertThrows(ProtocolException.class,()->new TimePolicy(120,30,-1));
    }
    @Test void noncanonicalUnusedSignatureBitsAreRejected(){
        String token=ProtocolTest.userSigner.sign(ProtocolTest.actor());int last="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".indexOf(token.charAt(token.length()-1));
        String altered=token.substring(0,token.length()-1)+"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".charAt(last+1);
        assertArrayEquals(Base64.getUrlDecoder().decode(token.split("\\.")[2]),Base64.getUrlDecoder().decode(altered.split("\\.")[2]));
        assertThrows(ProtocolException.class,()->ProtocolTest.verifier.verifyUser(altered,"workflow"));
    }
    @Test void malformedUtf8CannotBeSmuggledThroughSignedPayload()throws Exception {
        byte[] payload={(byte)0xC0,(byte)0xAF};String input=ProtocolTest.enc(ProtocolTest.header("gateway-1",ProtocolVerifier.USER_TYPE))+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        Signature signature=Signature.getInstance("Ed25519");signature.initSign(ProtocolTest.user.getPrivate());signature.update(input.getBytes(StandardCharsets.US_ASCII));String token=input+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        assertThrows(ProtocolException.class,()->ProtocolTest.verifier.verifyUser(token,"workflow"));
    }
    @Test void oversizedAndNestedSignedJsonAreRejected(){
        String oversized=ProtocolTest.userJson().replace("alice","a".repeat(6200));
        assertThrows(ProtocolException.class,()->ProtocolTest.verifier.verifyUser(ProtocolTest.raw(ProtocolTest.user.getPrivate(),ProtocolTest.header("gateway-1",ProtocolVerifier.USER_TYPE),oversized),"workflow"));
        String nested=ProtocolTest.userJson().replace("\"uid\":7","\"uid\":[[[[[7]]]]]");
        assertThrows(ProtocolException.class,()->ProtocolTest.verifier.verifyUser(ProtocolTest.raw(ProtocolTest.user.getPrivate(),ProtocolTest.header("gateway-1",ProtocolVerifier.USER_TYPE),nested),"workflow"));
    }
    @Test void chineseUsernameAndBoundaryIntegersRemainSupported(){
        var value=new UserProof(2,"gateway","metadata",Long.MAX_VALUE,"数据管理员",Long.MAX_VALUE,ProtocolTest.NOW,ProtocolTest.NOW+1,"r:1");
        assertEquals(value,ProtocolTest.verifier.verifyUser(ProtocolTest.userSigner.sign(value),"metadata").proof());
    }
    @Test void privateSignerDiagnosticsExcludeKeyAndVerifierResultCannotBeForgedByConstructor(){
        String privateBase64=Base64.getEncoder().encodeToString(ProtocolTest.user.getPrivate().getEncoded());
        assertFalse(ProtocolTest.userSigner.toString().contains(privateBase64));
        assertTrue(Arrays.stream(ProtocolVerifier.Verified.class.getDeclaredConstructors()).allMatch(c->java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
        assertTrue(java.lang.reflect.Modifier.isFinal(ProtocolVerifier.Verified.class.getModifiers()));
    }
}
