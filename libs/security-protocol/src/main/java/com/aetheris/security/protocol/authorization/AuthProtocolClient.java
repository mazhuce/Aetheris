package com.aetheris.security.protocol.authorization;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Auth's fixed v2 contracts. Explicit original USER proof, never a username substitute. */
public final class AuthProtocolClient {
    // Deliberately excluded from generic service clients' OPERATIONS capability set.
    public static final String BROWSER_SESSION_OPERATION = "auth.browser-session.check";
    public static final Set<String> OPERATIONS = Set.of("auth.session.check", "auth.permission.check",
            "auth.permission.visibility", "auth.permission.batch", "auth.permission.manage",
            "auth.permission.mutate", "auth.permission.version");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                    .maxStringLength(4096).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SignedHttpClient transport;
    public AuthProtocolClient(SignedHttpClient transport) { this.transport = Objects.requireNonNull(transport); }
    public record Decision(boolean allowed, String reason) { }
    public record Visibility(String mode, List<Long> ids) {
        public Visibility { ids = List.copyOf(ids); }
    }
    public record Item(long resourceId, Map<String, String> ctx) {
        public Item { if (resourceId <= 0) throw invalid(); ctx = context(ctx); }
    }
    public CompletableFuture<Boolean> sessionActive(long uid, String username, long accessVersion) {
        if (uid <= 0 || accessVersion < 0 || username == null || username.isBlank() || username.length() > 128) throw invalid();
        return post("sessions/check", "auth.session.check", Map.of("uid", uid, "username", username,
                "accessVersion", accessVersion), null).thenApply(node -> {
                    fields(node, Set.of("active")); return bool(node, "active");
                });
    }
    public CompletableFuture<Boolean> browserSessionActive(long uid, String username, long accessVersion, String sid) {
        if (uid <= 0 || accessVersion < 0 || username == null || username.isBlank() || username.length() > 64
                || sid == null || !UUID.fromString(sid).toString().equals(sid)) throw invalid();
        return post("browser-sessions/check", BROWSER_SESSION_OPERATION, Map.of("uid", uid, "username", username,
                "accessVersion", accessVersion, "sid", sid), null).thenApply(node -> {
                    fields(node, Set.of("active")); return bool(node, "active");
                });
    }
    public CompletableFuture<Decision> mayManage(String userProof) {
        return post("permissions/may-manage", "auth.permission.manage", Map.of(), user(userProof)).thenApply(AuthProtocolClient::decision);
    }
    public CompletableFuture<Decision> mayMutate(String userProof, String service) {
        name(service); return post("permissions/may-mutate", "auth.permission.mutate", Map.of("service", service), user(userProof))
                .thenApply(AuthProtocolClient::decision);
    }
    public CompletableFuture<Decision> check(String userProof, String resourceType, Long resourceId,
                                              String action, Map<String, String> ctx) {
        name(resourceType); name(action); if (resourceId != null && resourceId <= 0) throw invalid();
        Map<String, Object> body = new LinkedHashMap<>(); body.put("resourceType", resourceType);
        body.put("resourceId", resourceId); body.put("action", action); body.put("ctx", context(ctx));
        return post("permissions/check", "auth.permission.check", body, user(userProof)).thenApply(AuthProtocolClient::decision);
    }
    public CompletableFuture<Visibility> visibility(String userProof, String resourceType, String action) {
        name(resourceType); name(action);
        return post("permissions/visibility", "auth.permission.visibility", Map.of("resourceType", resourceType, "action", action),
                user(userProof)).thenApply(node -> {
                    fields(node, Set.of("mode", "ids")); String mode = text(node, "mode");
                    if (!Set.of("ALL", "IDS", "OPAQUE").contains(mode)) throw invalid();
                    return new Visibility(mode, ids(node.get("ids"), 100_000));
                });
    }
    public CompletableFuture<Set<Long>> checkBatch(String userProof, String resourceType, String action, List<Item> items) {
        name(resourceType); name(action);
        if (items == null || items.size() > 1000) throw invalid();
        var copy = List.copyOf(items); var requested = new HashSet<Long>();
        for (var item : copy) if (!requested.add(item.resourceId())) throw invalid();
        return post("permissions/check-batch", "auth.permission.batch", Map.of("resourceType", resourceType,
                "action", action, "items", copy), user(userProof)).thenApply(node -> {
                    fields(node, Set.of("allowedIds")); var allowed = ids(node.get("allowedIds"), 1000);
                    if (!requested.containsAll(allowed)) throw invalid(); return Set.copyOf(allowed);
                });
    }
    public CompletableFuture<Long> version() {
        return post("permissions/version", "auth.permission.version", Map.of(), null).thenApply(node -> {
            fields(node, Set.of("version")); JsonNode value = node.get("version");
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
            return value.longValue();
        });
    }
    private CompletableFuture<JsonNode> post(String path, String operation, Object body, String userProof) {
        final byte[] bytes;
        try { bytes = JSON.writeValueAsBytes(body); } catch (Exception error) { throw invalid(); }
        return transport.sendAsync(new SignedHttpClient.Request("auth", "POST", "/internal/v2/" + path,
                operation, bytes, userProof)).thenApply(response -> {
                    if (response.status() != 200) throw invalid();
                    try { return JSON.readTree(response.body()); } catch (Exception error) { throw invalid(); }
                });
    }
    private static Decision decision(JsonNode node) {
        fields(node, Set.of("allowed", "reason")); boolean allowed = bool(node, "allowed");
        JsonNode reason = node.get("reason"); if (!reason.isNull() && !reason.isTextual()) throw invalid();
        return new Decision(allowed, reason.isNull() ? null : reason.textValue());
    }
    private static List<Long> ids(JsonNode node, int maximum) {
        if (node == null || !node.isArray() || node.size() > maximum) throw invalid();
        var result = new ArrayList<Long>(); var seen = new HashSet<Long>();
        for (JsonNode id : node) {
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || !seen.add(id.longValue())) throw invalid();
            result.add(id.longValue());
        }
        return result;
    }
    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        node.fieldNames().forEachRemaining(field -> { if (!expected.contains(field)) throw invalid(); });
    }
    private static boolean bool(JsonNode node, String field) {
        if (!node.get(field).isBoolean()) throw invalid(); return node.get(field).booleanValue();
    }
    private static String text(JsonNode node, String field) {
        if (!node.get(field).isTextual()) throw invalid(); return node.get(field).textValue();
    }
    private static String user(String value) { if (value == null || value.isBlank()) throw invalid(); return value; }
    private static void name(String value) { if (value == null || !value.matches("[a-z][a-z0-9_-]{0,63}")) throw invalid(); }
    private static Map<String, String> context(Map<String, String> value) {
        if (value == null) return Map.of(); if (value.size() > 16) throw invalid();
        value.forEach((key, item) -> { if (key == null || !key.matches("[A-Za-z][A-Za-z0-9]{0,63}")
                || item == null || item.length() > 256 || item.chars().anyMatch(Character::isISOControl)) throw invalid(); });
        return Map.copyOf(value);
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Invalid or unavailable auth v2 contract"); }
}
