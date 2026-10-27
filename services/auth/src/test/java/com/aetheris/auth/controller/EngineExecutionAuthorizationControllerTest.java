package com.aetheris.auth.controller;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.fixedexecution.*;
import com.aetheris.auth.security.*;
import com.aetheris.auth.service.*;
import com.aetheris.auth.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual signed request/filter/response; account and permission persistence are local doubles. */
class EngineExecutionAuthorizationControllerTest {
    private static final String PATH = EngineExecutionAuthorizationController.PATH;
    private static final String OP = EngineExecutionAuthorizationController.OPERATION;
    private static final String HASH = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final V2ProofFixture proof = new V2ProofFixture();
    private final AuthUserService users = mock(AuthUserService.class);
    private final PermissionDecisionService decisions = mock(PermissionDecisionService.class);
    private final PermissionAuditClient audit = mock(PermissionAuditClient.class);
    private final Map<String, AuthUser> accounts = new HashMap<>();
    private final Set<String> nonces = ConcurrentHashMap.newKeySet();
    private EngineExecutionAuthorizationService service;
    private MockMvc mvc;

    @BeforeEach void setup() {
        account(2, "operator"); account(3, "approver");
        when(users.findByUsername(anyString())).thenAnswer(i -> Optional.ofNullable(accounts.get(i.getArgument(0))));
        when(decisions.check(anyString(), eq("workflow_task"), anyLong(), anyString(), eq(Map.of())))
                .thenReturn(PermissionDecisionService.Decision.ALLOWED);
        when(decisions.mayManage("approver")).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        var sessions = new AuthSessionValidator(users);
        service = new EngineExecutionAuthorizationService(sessions, decisions, audit);
        mvc = MockMvcBuilders.standaloneSetup(new EngineExecutionAuthorizationController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(proof.workload((caller, nonce, expiry) -> nonces.add(caller + ":" + nonce), sessions),
                        proof.direct(sessions)).build();
    }
    private void account(long uid, String name) {
        var u = new AuthUser(); u.setId(uid); u.setUsername(name); u.setRole("viewer");
        u.setStatus("active"); u.setAccessVersion(0L); accounts.put(name, u);
    }
    private ObjectNode input() {
        var n = JSON.createObjectNode().put("schemaVersion", 1);
        n.putObject("binding").put("engine", "spark").put("taskId", 7).put("instanceId", 11)
                .put("attemptId", 13).put("fence", 2).put("executionRevision", 3)
                .put("manifestSha256", HASH).put("definitionSha256", HASH).put("engineDefinitionSha256", "b".repeat(64));
        n.putObject("actor").put("kind", "manual").put("uid", 2).put("username", "operator")
                .put("accessVersion", 0).put("requestId", "engine-test");
        n.putObject("approval").put("uid", 3).put("username", "approver").put("accessVersion", 0)
                .put("revision", 3).put("definitionSha256", HASH);
        return n;
    }
    private JsonNode call(String body, int status) throws Exception { return call("workflow", OP, body, null, status); }
    private JsonNode call(String caller, String operation, String body, String user, int status) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        String token = proof.request(proof.signer(caller, caller, KeyPurpose.WORKLOAD), caller,
                "POST", PATH, operation, raw, user);
        var request = post(PATH).contentType("application/json").content(raw)
                .header("X-Aetheris-Workload-Proof", token).header("X-User-Name", "admin");
        if (user != null) request.header("X-Aetheris-User-Proof", user);
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus());
        if (Set.of(200, 400, 503).contains(status)) {
            var verified = proof.verifier.verifyRequest(token,
                    new ExpectedRequest("auth", "POST", PATH, BodyHash.sha256(raw), operation, BodyHash.userProof(user)));
            proof.verifier.verifyResponse(response.getHeader("X-Aetheris-Response-Proof"),
                    new ExpectedResponse("auth", caller, verified.proof().nonce(), status,
                            BodyHash.sha256(response.getContentAsByteArray())));
        }
        return JSON.readTree(response.getContentAsByteArray());
    }
    @Test void ordinaryActorGetsOnlyBoundApprovedEngineCapability() throws Exception {
        var n = input(); var out = call(n.toString(), 200);
        assertTrue(out.path("allowed").booleanValue()); assertTrue(out.get("reason").isNull());
        assertEquals(n.get("binding"), out.get("binding")); assertEquals(3, out.get("checks").size());
        var order = inOrder(decisions);
        order.verify(decisions).check("operator", "workflow_task", 7L, "execute", Map.of());
        order.verify(decisions).check("approver", "workflow_task", 7L, "update", Map.of());
        order.verify(decisions).mayManage("approver"); verifyNoMoreInteractions(decisions); verifyNoInteractions(audit);
        assertTrue(out.get("checks").get(2).get("resourceId").isNull());
    }
    @Test void bothEnginesAndAllThreeOriginalInvocationKindsUseTheSameNarrowSet() throws Exception {
        for (String engine : List.of("spark", "flink")) for (String kind : List.of("manual", "cron", "pipeline")) {
            var n = input(); ((ObjectNode) n.get("binding")).put("engine", engine);
            ((ObjectNode) n.get("actor")).put("kind", kind);
            assertTrue(call(n.toString(), 200).path("allowed").booleanValue());
        }
        verify(decisions, never()).mayManage("operator");
        verify(decisions, never()).check(anyString(), eq("datasource"), anyLong(), anyString(), anyMap());
    }
    @Test void productionNonNullMapperStillPreservesEveryNullableWireField() throws Exception {
        var result = service.authorize(EngineExecutionRequest.parse(input().toString().getBytes(StandardCharsets.UTF_8)));
        var mapper = new ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        var wire = mapper.readTree(mapper.writeValueAsBytes(result));
        assertTrue(wire.has("reason")); assertTrue(wire.get("reason").isNull());
        assertTrue(wire.path("checks").get(2).has("resourceId"));
        assertTrue(wire.path("checks").get(2).get("resourceId").isNull());
    }
    @Test void exactUidAndAvAndEnabledStateAreRequiredForBothSubjects() throws Exception {
        for (String subject : List.of("actor", "approval")) for (String mutation : List.of("uid", "av", "disabled", "recreated")) {
            account(2, "operator"); account(3, "approver");
            var n = input(); var s = (ObjectNode) n.get(subject); String name = s.path("username").asText();
            switch (mutation) {
                case "uid" -> s.put("uid", 99);
                case "av" -> accounts.get(name).setAccessVersion(1L);
                case "disabled" -> accounts.get(name).setStatus("disabled");
                case "recreated" -> accounts.get(name).setId(99L);
            }
            var out = call(n.toString(), 200); assertFalse(out.path("allowed").booleanValue());
            assertEquals("SUBJECT_INACTIVE", out.path("reason").asText()); assertTrue(out.path("checks").isEmpty());
        }
        verifyNoInteractions(decisions);
    }
    @Test void eachActorAndApproverPermissionIsRequiredAndPrivateDenialReasonNeverLeaks() throws Exception {
        when(decisions.check("operator", "workflow_task", 7L, "execute", Map.of()))
                .thenReturn(PermissionDecisionService.Decision.denied("jdbc:secret-user:password"));
        assertEquals("PERMISSION_DENIED", call(input().toString(), 200).path("reason").asText());
        when(decisions.check("operator", "workflow_task", 7L, "execute", Map.of())).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        when(decisions.check("approver", "workflow_task", 7L, "update", Map.of())).thenReturn(PermissionDecisionService.Decision.denied("secret"));
        assertFalse(call(input().toString(), 200).path("allowed").booleanValue());
        when(decisions.check("approver", "workflow_task", 7L, "update", Map.of())).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        when(decisions.mayManage("approver")).thenReturn(PermissionDecisionService.Decision.denied("secret"));
        var out = call(input().toString(), 200); assertFalse(out.path("allowed").booleanValue()); assertFalse(out.toString().contains("secret"));
    }
    @Test void replacementOrRevocationDuringPermissionLookupCannotRestoreAuthorityByUsername() throws Exception {
        for (String subject : List.of("operator", "approver")) for (String mutation : List.of("uid", "av", "disabled")) {
            account(2, "operator"); account(3, "approver");
            doAnswer(invocation -> {
                var account = accounts.get(subject);
                if (mutation.equals("uid")) account.setId(99L);
                if (mutation.equals("av")) account.setAccessVersion(1L);
                if (mutation.equals("disabled")) account.setStatus("disabled");
                return PermissionDecisionService.Decision.ALLOWED;
            }).when(decisions).check("operator", "workflow_task", 7L, "execute", Map.of());
            var out = call(input().toString(), 200);
            assertFalse(out.path("allowed").booleanValue(), subject + ":" + mutation);
            assertEquals("SUBJECT_INACTIVE", out.path("reason").asText());
        }
    }
    @Test void nullAndUnavailablePermissionResponsesFailClosed() throws Exception {
        when(decisions.mayManage("approver")).thenReturn(null);
        assertEquals("AUTH_UNAVAILABLE", call(input().toString(), 503).path("reason").asText());
        when(decisions.mayManage("approver")).thenThrow(new IllegalStateException("private-password"));
        assertFalse(call(input().toString(), 503).toString().contains("private-password"));
    }
    @Test void approvalCannotAuthorizeAnotherRevisionOrDefinition() throws Exception {
        for (String field : List.of("revision", "definitionSha256")) {
            var n = input(); var p = (ObjectNode) n.get("approval");
            if (field.equals("revision")) p.put(field, 4); else p.put(field, "c".repeat(64));
            call(n.toString(), 400);
        }
        verifyNoInteractions(users, decisions, audit);
    }
    @Test void malformedAndExtendedWireAssertionsAreRejectedBeforeAccountLookup() throws Exception {
        List<String> bad = new ArrayList<>(); bad.add(input().toString().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"));
        bad.add(input() + " {}"); bad.add("null"); bad.add(input().put("targets", "arbitrary").toString());
        for (String field : List.of("engine", "taskId", "instanceId", "attemptId", "fence", "executionRevision", "manifestSha256", "engineDefinitionSha256")) {
            var n = input(); ((ObjectNode) n.get("binding")).putNull(field); bad.add(n.toString());
        }
        var child = input(); ((ObjectNode) child.get("actor")).put("kind", "workflow-child"); bad.add(child.toString());
        var engine = input(); ((ObjectNode) engine.get("binding")).put("engine", "sql"); bad.add(engine.toString());
        var number = input(); ((ObjectNode) number.get("binding")).put("fence", 1.5); bad.add(number.toString());
        for (String value : bad) call(value, 400);
        verifyNoInteractions(users, decisions, audit);
    }
    @Test void onlyWorkflowWorkloadWithoutUserCanAssertTheFrozenAttempt() throws Exception {
        String raw = input().toString();
        for (String caller : List.of("metadata", "system", "serving", "outsider")) call(caller, OP, raw, null, 403);
        call("workflow", OP, raw, proof.user("workflow", 2, "operator", 0), 403);
        call("workflow", "auth.permission.check", raw, null, 401);
        assertEquals(401, mvc.perform(post(PATH).contentType("application/json").content(raw)
                .header("X-User-Name", "admin").header("X-Internal-Token", "legacy")).andReturn().getResponse().getStatus());
        var naked = MockMvcBuilders.standaloneSetup(new EngineExecutionAuthorizationController(service)).build();
        assertEquals(401, naked.perform(post(PATH).contentType("application/json").content(raw)).andReturn().getResponse().getStatus());
        verifyNoInteractions(users, decisions, audit);
    }
    @Test void replayAndMetadataOnlyOriginalRouteCannotBeUsedAsAnEngineBypass() throws Exception {
        byte[] raw = input().toString().getBytes(StandardCharsets.UTF_8);
        String token = proof.request(proof.signer("workflow", "workflow", KeyPurpose.WORKLOAD), "workflow", "POST", PATH, OP, raw, null);
        for (int status : List.of(200, 409)) assertEquals(status, mvc.perform(post(PATH).contentType("application/json")
                .content(raw).header("X-Aetheris-Workload-Proof", token)).andReturn().getResponse().getStatus());
        var route = com.aetheris.auth.config.AuthV2SecurityConfiguration.workloadRoutes()
                .resolve("POST", FixedExecutionAuthorizationController.PATH).orElseThrow();
        assertEquals(Set.of("metadata"), route.allowedCallers());
    }
}
