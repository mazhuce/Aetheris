package com.aetheris.security.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FixedVectorsTest {
    static JsonNode vectors;
    static KeyRegistry registry;
    static ProtocolVerifier verifier;
    static final ObjectMapper JSON=new ObjectMapper();
    @BeforeAll static void setup()throws Exception {
        try(var input=FixedVectorsTest.class.getResourceAsStream("/vectors/v2-bound-user-fixture.json")){assertNotNull(input);vectors=JSON.readTree(input);}
        registry=new KeyRegistry(List.of(entry("gateway",KeyPurpose.USER),entry("workflow",KeyPurpose.WORKLOAD),entry("metadata",KeyPurpose.WORKLOAD)));
        verifier=new ProtocolVerifier(registry,Clock.fixed(Instant.ofEpochSecond(vectors.get("epochSecond").longValue()),ZoneOffset.UTC),TimePolicy.defaults());
    }
    static KeyRegistry.Entry entry(String name,KeyPurpose purpose){return new KeyRegistry.Entry(name+"-1",purpose,name,KeyMaterial.parseX509Pem(KeyMaterialTest.pem("PUBLIC KEY",Base64.getDecoder().decode(vectors.get(name).get("publicKeyX509Base64").textValue()))));}
    @Test void goldenUser(){assertEquals(ProtocolTest.actor(),verifier.verifyUser(vectors.get("user").textValue(),"workflow").proof());}
    @Test void goldenRequest(){assertEquals(ProtocolTest.request(),verifier.verifyRequest(vectors.get("request").textValue(),ProtocolTest.expected()).proof());}
    @Test void goldenResponse(){assertEquals(ProtocolTest.response(),verifier.verifyResponse(vectors.get("response").textValue(),ProtocolTest.expectedResponse()).proof());}
    @Test void goldenSignaturesAreIndependentlyReproducibleWithJdk()throws Exception {
        for(String type:List.of("user","request","response")){
            String name=type.equals("user")?"gateway":type.equals("request")?"workflow":"metadata";
            PrivateKey privateKey=KeyMaterial.parsePkcs8Pem(KeyMaterialTest.pem("PRIVATE KEY",Base64.getDecoder().decode(vectors.get(name).get("privateKeyPkcs8Base64").textValue())));
            String token=vectors.get(type).textValue();String[] parts=token.split("\\.");
            Signature signature=Signature.getInstance("Ed25519");signature.initSign(privateKey);signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));assertEquals(parts[2],Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()));
        }
    }
    @Test void goldenBodyMatchesExactly(){assertEquals(ProtocolTest.BODY,BodyHash.sha256(Base64.getUrlDecoder().decode(vectors.get("rawBodyBase64url").textValue())));}
    @Test void oldRequestWithoutUserBindingIsRejectedEvenWithValidSignature() throws Exception {
        JsonNode old;
        try(var input=FixedVectorsTest.class.getResourceAsStream("/vectors/v2-fixture.json")){old=JSON.readTree(input);}
        PublicKey key=KeyMaterial.parseX509Pem(KeyMaterialTest.pem("PUBLIC KEY",Base64.getDecoder().decode(old.get("workflow").get("publicKeyX509Base64").textValue())));
        var oldVerifier=new ProtocolVerifier(new KeyRegistry(List.of(new KeyRegistry.Entry("workflow-1",KeyPurpose.WORKLOAD,"workflow",key))),
                Clock.fixed(Instant.ofEpochSecond(old.get("epochSecond").longValue()),ZoneOffset.UTC),TimePolicy.defaults());
        assertThrows(ProtocolException.class,()->oldVerifier.verifyRequest(old.get("request").textValue(),ProtocolTest.expected()));
    }
}
