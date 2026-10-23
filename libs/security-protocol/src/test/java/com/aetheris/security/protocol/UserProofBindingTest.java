package com.aetheris.security.protocol;

import org.junit.jupiter.api.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Two individually valid identities must never be interchangeable in a signed operation. */
class UserProofBindingTest {
    static final long NOW=1_800_000_000L;
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC);
    static ProtocolSigner gateway,workflow;
    static ProtocolVerifier verifier;
    static String alice,bob;
    static final String BODY=BodyHash.sha256(new byte[]{1,2,3});
    @BeforeAll static void setup() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");var user=generator.generateKeyPair();var service=generator.generateKeyPair();
        var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("gateway-user",KeyPurpose.USER,"gateway",user.getPublic()),
                new KeyRegistry.Entry("workflow-1",KeyPurpose.WORKLOAD,"workflow",service.getPublic())));
        gateway=new ProtocolSigner("gateway-user",KeyPurpose.USER,"gateway",user.getPrivate(),registry,CLOCK,TimePolicy.defaults());
        workflow=new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"workflow",service.getPrivate(),registry,CLOCK,TimePolicy.defaults());
        verifier=new ProtocolVerifier(registry,CLOCK,TimePolicy.defaults());
        alice=gateway.sign(new UserProof(2,"gateway","workflow",7,"alice",3,NOW,NOW+120,"alice-request"));
        bob=gateway.sign(new UserProof(2,"gateway","workflow",8,"bob",4,NOW,NOW+120,"bob-request"));
    }
    static String request(String user) {
        return workflow.sign(new WorkloadRequestProof(2,"workflow","metadata","POST","/internal/v2/query",BODY,
                Nonce.random(),NOW,NOW+30,"table.query",BodyHash.userProof(user)));
    }
    static ExpectedRequest expected(String user) {
        return new ExpectedRequest("metadata","POST","/internal/v2/query",BODY,"table.query",BodyHash.userProof(user));
    }
    @Test void originalUserBoundToExactOperationIsAccepted() {
        assertEquals(7,verifier.verifyUser(alice,"workflow").proof().uid());
        assertEquals(BodyHash.userProof(alice),verifier.verifyRequest(request(alice),expected(alice)).proof().userProofSha256());
    }
    @Test void anotherIndividuallyValidUserCannotReplaceOriginalOnFirstRequest() {
        String firstRequest=request(alice);
        assertEquals(8,verifier.verifyUser(bob,"workflow").proof().uid());
        assertThrows(ProtocolException.class,()->verifier.verifyRequest(firstRequest,expected(bob)));
    }
    @Test void removalInsertionAndAbsenceAreBound() {
        assertThrows(ProtocolException.class,()->verifier.verifyRequest(request(alice),expected(null)));
        assertThrows(ProtocolException.class,()->verifier.verifyRequest(request(null),expected(alice)));
        assertEquals(BodyHash.userProof(null),verifier.verifyRequest(request(null),expected(null)).proof().userProofSha256());
    }
    @Test void malformedHeaderAndDigestHaveNoImplicitFallback() {
        for(String value:List.of("","x y","x\r\ny","中文","x".repeat(12289)))
            assertThrows(ProtocolException.class,()->BodyHash.userProof(value));
        for(String hash:Arrays.asList(null,"","A".repeat(64),"0".repeat(63))) {
            assertThrows(ProtocolException.class,()->workflow.sign(new WorkloadRequestProof(2,"workflow","metadata","POST",
                    "/internal/v2/query",BODY,Nonce.random(),NOW,NOW+30,"table.query",hash)));
            assertThrows(ProtocolException.class,()->verifier.verifyRequest(request(alice),
                    new ExpectedRequest("metadata","POST","/internal/v2/query",BODY,"table.query",hash)));
        }
    }
}
