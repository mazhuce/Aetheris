package com.aetheris.auth.controller;

import com.aetheris.auth.security.V2ProofFixture;
import com.aetheris.auth.service.BrowserSessionService;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class BrowserSessionControllerTest {
    private static final String PATH = BrowserSessionController.PATH;
    private static final String OP = AuthProtocolClient.BROWSER_SESSION_OPERATION;
    private final V2ProofFixture proof = new V2ProofFixture();
    private final BrowserSessionService sessions = mock(BrowserSessionService.class);
    private final UUID sid = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc;
    @BeforeEach void setup() {
        var nonces = ConcurrentHashMap.<String>newKeySet();
        mvc = MockMvcBuilders.standaloneSetup(new BrowserSessionController(sessions))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter())
                .addFilters(proof.workload((caller, nonce, expiry) -> nonces.add(caller + ":" + nonce), (u,n,v) -> true),
                        proof.direct((u,n,v) -> true)).build();
    }
    private String input() throws Exception {
        return json.writeValueAsString(Map.of("uid", 42, "username", "alice", "accessVersion", 7, "sid", sid.toString()));
    }
    private com.fasterxml.jackson.databind.JsonNode call(String caller, String op, String raw, String user, int status) throws Exception {
        byte[] body = raw.getBytes(StandardCharsets.UTF_8);
        String signed = proof.request(proof.signer(caller, caller, KeyPurpose.WORKLOAD), caller, "POST", PATH, op, body, user);
        var request = post(PATH).contentType("application/json").content(body).header("X-Aetheris-Workload-Proof", signed);
        if (user != null) request.header("X-Aetheris-User-Proof", user);
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        if (Set.of(200,400,503).contains(status)) {
            var verified = proof.verifier.verifyRequest(signed, new ExpectedRequest("auth", "POST", PATH, BodyHash.sha256(body), op, BodyHash.userProof(user)));
            proof.verifier.verifyResponse(response.getHeader("X-Aetheris-Response-Proof"), new ExpectedResponse("auth", caller,
                    verified.proof().nonce(), status, BodyHash.sha256(response.getContentAsByteArray())));
        }
        return json.readTree(response.getContentAsByteArray());
    }
    @Test void exactGatewayFamilyGetsLiveSignedDecision() throws Exception {
        when(sessions.active(sid,42,"alice",7)).thenReturn(true, false);
        assertTrue(call("gateway",OP,input(),null,200).get("active").booleanValue());
        assertFalse(call("gateway",OP,input(),null,200).get("active").booleanValue());
        verify(sessions,times(2)).active(sid,42,"alice",7);
    }
    @Test void otherServicesCannotProbeBrowserFamilies() throws Exception {
        for (String caller : List.of("metadata","system","workflow","serving","quality","ingestion","outsider"))
            call(caller,OP,input(),null,403);
        verifyNoInteractions(sessions);
    }
    @Test void userProofOrAccountOnlyOperationCannotSubstituteForCapability() throws Exception {
        call("gateway",OP,input(),proof.user("auth",42,"alice",7),403);
        call("gateway","auth.session.check",input(),null,401);
        verifyNoInteractions(sessions);
    }
    @Test void unsignedRequestNeverReachesSessionStore() throws Exception {
        var response = mvc.perform(post(PATH).contentType("application/json").content(input())).andReturn().getResponse();
        assertEquals(401,response.getStatus()); verifyNoInteractions(sessions);
    }
    @Test void strictBodyRejectsDuplicatesExtraFieldsAndCoercedSubjects() throws Exception {
        String raw = input();
        for (String invalid : List.of(raw.replace("\"uid\":42", "\"uid\":\"42\""),
                raw.replace("\"uid\":42", "\"uid\":42.5"), raw.replace("\"uid\":42", "\"uid\":42,\"uid\":42"),
                raw.substring(0,raw.length()-1)+",\"admin\":true}", raw+" {}", raw.replace(sid.toString(),"1-1-1-1-1")))
            call("gateway",OP,invalid,null,400);
        verifyNoInteractions(sessions);
    }
    @Test void databaseFailureReturnsSignedUnavailableNeverInactiveOrActive() throws Exception {
        when(sessions.active(any(),anyLong(),anyString(),anyLong())).thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        assertEquals("BROWSER_SESSION_UNAVAILABLE",call("gateway",OP,input(),null,503).get("reason").textValue());
    }
}
