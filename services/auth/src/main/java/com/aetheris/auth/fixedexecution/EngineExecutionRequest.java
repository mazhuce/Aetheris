package com.aetheris.auth.fixedexecution;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.Set;

/** Workflow-only assertion for one approved, resource-pinned Spark/Flink attempt. */
public record EngineExecutionRequest(int schemaVersion, Binding binding,
        FixedExecutionRequest.Actor actor, FixedExecutionRequest.Approval approval) {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(1024).maxNumberLength(20).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public EngineExecutionRequest {
        require(schemaVersion == 1 && binding != null && actor != null && approval != null);
        require(Set.of("manual", "cron", "pipeline").contains(actor.kind()));
        require(approval.revision() == binding.executionRevision()
                && approval.definitionSha256().equals(binding.definitionSha256()));
    }

    public record Binding(String engine, long taskId, long instanceId, long attemptId, long fence,
            long executionRevision, String manifestSha256, String definitionSha256,
            String engineDefinitionSha256) {
        public Binding {
            require(engine != null && Set.of("spark", "flink").contains(engine));
            require(taskId > 0 && instanceId > 0 && attemptId > 0 && fence > 0 && executionRevision > 0);
            hash(manifestSha256); hash(definitionSha256); hash(engineDefinitionSha256);
        }
    }

    public static EngineExecutionRequest parse(byte[] raw) {
        require(raw != null && raw.length > 0 && raw.length <= 16 * 1024);
        try {
            JsonNode n = JSON.readTree(raw);
            fields(n, "schemaVersion", "binding", "actor", "approval");
            require(number(n, "schemaVersion", 1) == 1);
            JsonNode b = n.get("binding"), a = n.get("actor"), p = n.get("approval");
            fields(b, "engine", "taskId", "instanceId", "attemptId", "fence", "executionRevision",
                    "manifestSha256", "definitionSha256", "engineDefinitionSha256");
            fields(a, "kind", "uid", "username", "accessVersion", "requestId");
            fields(p, "uid", "username", "accessVersion", "revision", "definitionSha256");
            return new EngineExecutionRequest(1,
                    new Binding(text(b, "engine"), number(b, "taskId", 1), number(b, "instanceId", 1),
                            number(b, "attemptId", 1), number(b, "fence", 1), number(b, "executionRevision", 1),
                            text(b, "manifestSha256"), text(b, "definitionSha256"), text(b, "engineDefinitionSha256")),
                    new FixedExecutionRequest.Actor(text(a, "kind"), number(a, "uid", 1), text(a, "username"),
                            number(a, "accessVersion", 0), text(a, "requestId")),
                    new FixedExecutionRequest.Approval(number(p, "uid", 1), text(p, "username"),
                            number(p, "accessVersion", 0), number(p, "revision", 1), text(p, "definitionSha256")));
        } catch (IOException malformed) { throw invalid(); }
    }

    private static void fields(JsonNode n, String... fields) {
        require(n != null && n.isObject() && n.size() == fields.length);
        Set<String> names = Set.of(fields);
        n.fieldNames().forEachRemaining(name -> require(names.contains(name)));
    }
    private static long number(JsonNode n, String field, long minimum) {
        JsonNode v = n.get(field);
        require(v != null && v.isIntegralNumber() && v.canConvertToLong() && v.longValue() >= minimum);
        return v.longValue();
    }
    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field); require(v != null && v.isTextual()); return v.textValue();
    }
    private static void hash(String value) { require(value != null && value.matches("[a-f0-9]{64}")); }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid engine execution assertion"); }
}
