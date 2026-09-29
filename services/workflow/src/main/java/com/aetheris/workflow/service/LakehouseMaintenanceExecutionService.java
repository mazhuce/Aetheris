package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionAdapter.ManagedHandle;
import com.aetheris.workflow.service.ExecutionAdapter.RemoteExecution;
import com.aetheris.workflow.service.ExecutionAdapter.Submission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Submits the metadata-generated Iceberg maintenance statement through the
 * same idempotent Spark runner used by managed jobs.  It never marks a job
 * successful: the caller must reconcile the returned remote handle.
 */
@Service
public class LakehouseMaintenanceExecutionService {
    private static final Set<String> OPERATIONS = Set.of(
            "expire_snapshots", "rewrite_data_files", "remove_orphan_files",
            "storage_estimate", "time_travel", "rollback");
    private static final Set<String> SCHEMA_OPERATIONS = Set.of(
            "add_column", "widen_type", "rename_column", "drop_column", "comment");
    private static final Pattern REQUEST_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ATTEMPT_ID = Pattern.compile("[0-9]{1,32}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MAX_STATEMENT_BYTES = 16 * 1024;
    private static final Pattern SCHEMA_STATEMENT = Pattern.compile(
            "ALTER\\s+TABLE\\s+(?:`[A-Za-z_][A-Za-z0-9_]{0,127}`\\.){1,7}`[A-Za-z_][A-Za-z0-9_]{0,127}`\\s+"
                    + "(?:ADD\\s+COLUMN\\s+`[A-Za-z_][A-Za-z0-9_]{0,127}`\\s+[A-Z]+(?:\\s*\\(\\s*\\d{1,2}\\s*,\\s*\\d{1,2}\\s*\\))?"
                    + "|ALTER\\s+COLUMN\\s+`[A-Za-z_][A-Za-z0-9_]{0,127}`\\s+TYPE\\s+[A-Z]+(?:\\s*\\(\\s*\\d{1,2}\\s*,\\s*\\d{1,2}\\s*\\))?"
                    + "|RENAME\\s+COLUMN\\s+`[A-Za-z_][A-Za-z0-9_]{0,127}`\\s+TO\\s+`[A-Za-z_][A-Za-z0-9_]{0,127}`"
                    + "|DROP\\s+COLUMN\\s+`[A-Za-z_][A-Za-z0-9_]{0,127}`"
                    + "|ALTER\\s+COLUMN\\s+`[A-Za-z_][A-Za-z0-9_]{0,127}`\\s+COMMENT\\s+'(?:''|[^'\\r\\n]){1,512}')",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final SparkBatchExecutionAdapter adapter;
    private final boolean enabled;
    private final String artifactKey;
    private final String artifactDigest;

    public LakehouseMaintenanceExecutionService(
            SparkBatchExecutionAdapter adapter,
            @Value("${aetheris.spark-runner.maintenance.enabled:false}") boolean enabled,
            @Value("${aetheris.spark-runner.maintenance.artifact-key:aetheris_lakehouse_maintenance.py}") String artifactKey,
            @Value("${aetheris.spark-runner.maintenance.artifact-sha256:}") String artifactDigest) {
        this.adapter = adapter;
        this.enabled = enabled;
        this.artifactKey = artifactKey == null ? "" : artifactKey.trim().toLowerCase();
        this.artifactDigest = artifactDigest == null ? "" : artifactDigest.trim().toLowerCase();
    }

    public MaintenanceHandle submit(SubmitRequest request) {
        if (!enabled) throw new SparkRunnerException("湖仓维护执行器未启用", 503, false);
        validate(request);
        if (!SHA256.matcher(artifactDigest).matches()) {
            throw new IllegalStateException("湖仓维护制品摘要未配置或格式非法");
        }
        adapter.validate(artifactKey, artifactDigest);
        Map<String, Object> parameters = Map.of(
                "bizDate", request.bizDate(),
                "attemptId", request.attemptId(),
                SCHEMA_OPERATIONS.contains(request.operation()) ? "schemaChange" : "maintenance",
                Map.of("operation", request.operation(), "statement", request.statement()));
        ManagedHandle handle = adapter.submit(new Submission(request.requestKey(), artifactKey, "pyspark",
                artifactDigest, null, parameters, 1, 1024, request.timeoutSeconds()));
        return new MaintenanceHandle(handle.id(), handle.requestKey(), handle.status(), handle.applicationId(),
                handle.artifactDigest());
    }

    public Optional<RemoteExecution> status(String requestKey) {
        if (!enabled) throw new SparkRunnerException("湖仓维护执行器未启用", 503, false);
        if (!REQUEST_KEY.matcher(requestKey == null ? "" : requestKey).matches()) {
            throw new IllegalArgumentException("requestKey 格式非法");
        }
        Optional<ManagedHandle> handle = adapter.reconcile(requestKey);
        if (handle.isEmpty()) return Optional.empty();
        return Optional.of(adapter.status(handle.get().id()));
    }

    public RemoteExecution cancel(String remoteHandle) {
        if (!enabled) throw new SparkRunnerException("湖仓维护执行器未启用", 503, false);
        if (remoteHandle == null || !remoteHandle.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("远端作业句柄格式非法");
        }
        return adapter.cancel(remoteHandle);
    }

    private static void validate(SubmitRequest request) {
        if (request == null || !REQUEST_KEY.matcher(value(request == null ? null : request.requestKey())).matches()) {
            throw new IllegalArgumentException("requestKey 格式非法");
        }
        boolean schemaChange = SCHEMA_OPERATIONS.contains(request.operation());
        if (!schemaChange && !OPERATIONS.contains(request.operation())) throw new IllegalArgumentException("维护操作不受支持");
        if (!StringUtils.hasText(request.statement()) || request.statement().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATEMENT_BYTES
                || request.statement().contains("\u0000") || request.statement().contains(";")
                || request.statement().contains("--") || request.statement().contains("/*")) {
            throw new IllegalArgumentException("维护语句格式非法");
        }
        String upper = request.statement().stripLeading().toUpperCase();
        if (schemaChange) {
            if (!SCHEMA_STATEMENT.matcher(request.statement()).matches()) {
                throw new IllegalArgumentException("Schema 变更语句格式非法");
            }
            String required = switch (request.operation()) {
                case "add_column" -> "ADD COLUMN";
                case "widen_type" -> "ALTER COLUMN";
                case "rename_column" -> "RENAME COLUMN";
                case "drop_column" -> "DROP COLUMN";
                case "comment" -> "COMMENT";
                default -> throw new IllegalArgumentException("Schema 变更操作不受支持");
            };
            if (!upper.contains(required)) throw new IllegalArgumentException("Schema 变更操作与语句不匹配");
        }
        boolean readOnly = !schemaChange && Set.of("storage_estimate", "time_travel").contains(request.operation());
        if (!schemaChange && ((readOnly && !upper.startsWith("SELECT ")) || (!readOnly && !upper.startsWith("CALL ")))) {
            throw new IllegalArgumentException("维护语句与操作类型不匹配");
        }
        if (!schemaChange && !readOnly) {
            String procedure = switch (request.operation()) {
                case "expire_snapshots" -> "EXPIRE_SNAPSHOTS";
                case "rewrite_data_files" -> "REWRITE_DATA_FILES";
                case "remove_orphan_files" -> "REMOVE_ORPHAN_FILES";
                case "rollback" -> "ROLLBACK_TO_SNAPSHOT";
                default -> throw new IllegalArgumentException("维护操作不受支持");
            };
            if (!upper.matches("CALL\\s+[A-Z0-9_:.\\-]+\\.SYSTEM\\." + procedure + "\\s*\\(.*")) {
                throw new IllegalArgumentException("维护过程与操作类型不匹配");
            }
        }
        if (!LocalDate.parse(request.bizDate()).toString().equals(request.bizDate())) {
            throw new IllegalArgumentException("bizDate 格式非法");
        }
        if (!ATTEMPT_ID.matcher(value(request.attemptId())).matches()) throw new IllegalArgumentException("attemptId 格式非法");
        if (request.timeoutSeconds() < 30 || request.timeoutSeconds() > 86400) {
            throw new IllegalArgumentException("timeoutSeconds 超出范围");
        }
    }

    private static String value(String value) { return value == null ? "" : value; }

    public record SubmitRequest(String requestKey, String bizDate, String attemptId,
                                String operation, String statement, int timeoutSeconds) { }

    public record MaintenanceHandle(String id, String requestKey, String status,
                                    String applicationId, String artifactDigest) { }
}
