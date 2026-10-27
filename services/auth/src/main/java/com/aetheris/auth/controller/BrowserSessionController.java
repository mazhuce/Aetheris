package com.aetheris.auth.controller;

import com.aetheris.auth.service.BrowserSessionService;
import com.aetheris.auth.validation.UsernameRules;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.servlet.WorkloadProofFilter;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Browser family admission is Gateway-only; durable execution uses the independent account contract. */
@RestController
public final class BrowserSessionController {
    public static final String PATH = "/internal/v2/browser-sessions/check";
    private static final Set<String> FIELDS = Set.of("uid", "username", "accessVersion", "sid");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final BrowserSessionService sessions;
    public BrowserSessionController(BrowserSessionService sessions) { this.sessions = sessions; }

    @PostMapping(PATH)
    public Map<String, Boolean> check(HttpServletRequest request, @RequestBody byte[] raw) {
        try {
            var trusted = WorkloadProofFilter.trusted(request);
            if (!"gateway".equals(trusted.workload().caller()) || trusted.user().isPresent()
                    || !AuthProtocolClient.BROWSER_SESSION_OPERATION.equals(trusted.workload().proof().operation()))
                throw new SecurityException();
        } catch (IllegalStateException absent) { throw new SecurityException(); }
        final long uid, av; final String username; final UUID sid;
        try {
            if (raw == null || raw.length > 4096) throw new IllegalArgumentException();
            var body = JSON.readTree(raw);
            if (body == null || !body.isObject() || body.size() != FIELDS.size()) throw new IllegalArgumentException();
            body.fieldNames().forEachRemaining(field -> { if (!FIELDS.contains(field)) throw new IllegalArgumentException(); });
            if (!body.get("uid").isIntegralNumber() || !body.get("uid").canConvertToLong()
                    || !body.get("accessVersion").isIntegralNumber() || !body.get("accessVersion").canConvertToLong()
                    || !body.get("sid").isTextual() || !body.get("username").isTextual()) throw new IllegalArgumentException();
            uid = body.get("uid").longValue(); av = body.get("accessVersion").longValue();
            username = UsernameRules.requireValid(body.get("username").textValue());
            String value = body.get("sid").textValue(); sid = UUID.fromString(value);
            if (!sid.toString().equals(value) || uid <= 0 || av < 0) throw new IllegalArgumentException();
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid browser session assertion"); }
        return Map.of("active", sessions.active(sid, uid, username, av));
    }

    @ExceptionHandler(SecurityException.class) ResponseEntity<?> unauthenticated() {
        return ResponseEntity.status(401).body(Map.of("reason", "AUTHENTICATED_GATEWAY_REQUIRED"));
    }
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("reason", "INVALID_BROWSER_SESSION_ASSERTION"));
    }
    @ExceptionHandler(DataAccessException.class) ResponseEntity<?> unavailable() {
        return ResponseEntity.status(503).body(Map.of("reason", "BROWSER_SESSION_UNAVAILABLE"));
    }
}
