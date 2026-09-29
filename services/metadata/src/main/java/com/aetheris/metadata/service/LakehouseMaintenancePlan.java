package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.aetheris.metadata.entity.LakehouseTable;

import java.util.Set;
import java.util.regex.Pattern;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Builds an auditable, side-effect-free Spark/Iceberg maintenance plan.
 *
 * <p>The plan is deliberately separate from execution: a future Spark runner
 * can consume the exact statement, while the metadata service never pretends
 * that an object-store mutation happened merely because a request was created.</p>
 */
public final class LakehouseMaintenancePlan {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_:-]{0,127}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile("[0-9]{1,32}");
    private static final Set<String> SUPPORTED = Set.of(
            "expire_snapshots", "rewrite_data_files", "remove_orphan_files",
            "storage_estimate", "time_travel", "rollback");

    private LakehouseMaintenancePlan() { }

    public static Plan build(LakehouseTable table, LakehouseMaintenanceJob job) {
        if (table == null || job == null) throw new IllegalArgumentException("维护计划缺少表或作业");
        String operation = job.getOperation();
        if (!SUPPORTED.contains(operation)) throw new IllegalArgumentException("不支持的湖仓维护操作");
        String identifier = qualifiedIdentifier(table);
        String tableLiteral = quoteLiteral(table.getNamespaceName() + "." + table.getTableName());
        return switch (operation) {
            case "expire_snapshots" -> new Plan(operation, identifier, true, null,
                    "CALL " + table.getCatalogName() + ".system.expire_snapshots(table => " + tableLiteral
                            + ", older_than => TIMESTAMP '" + retentionCutoff(table) + "')");
            case "rewrite_data_files" -> new Plan(operation, identifier, true, null,
                    "CALL " + table.getCatalogName() + ".system.rewrite_data_files(table => " + tableLiteral + ")");
            case "remove_orphan_files" -> new Plan(operation, identifier, true, null,
                    "CALL " + table.getCatalogName() + ".system.remove_orphan_files(table => " + tableLiteral
                            + ", older_than => TIMESTAMP '" + retentionCutoff(table) + "')");
            case "storage_estimate" -> new Plan(operation, identifier, false, null,
                    "SELECT COUNT(*) AS file_count, COALESCE(SUM(file_size_in_bytes), 0) AS total_bytes FROM "
                            + identifier + ".files");
            case "time_travel" -> {
                String snapshotId = requiredSnapshot(job);
                yield new Plan(operation, identifier, false, snapshotId,
                        "SELECT * FROM " + identifier + " VERSION AS OF " + snapshotId);
            }
            case "rollback" -> {
                String snapshotId = requiredSnapshot(job);
                yield new Plan(operation, identifier, true, snapshotId,
                        "CALL " + table.getCatalogName() + ".system.rollback_to_snapshot(" + tableLiteral
                                + ", " + snapshotId + ")");
            }
            default -> throw new IllegalArgumentException("不支持的湖仓维护操作");
        };
    }

    private static String qualifiedIdentifier(LakehouseTable table) {
        requirePart(table.getCatalogName(), "catalogName");
        if (table.getNamespaceName() == null || table.getNamespaceName().isBlank()) {
            throw new IllegalArgumentException("namespaceName 不能为空");
        }
        String[] namespace = table.getNamespaceName().split("\\.");
        for (String part : namespace) requirePart(part, "namespaceName");
        requirePart(table.getTableName(), "tableName");
        return table.getCatalogName() + "." + table.getNamespaceName() + "." + table.getTableName();
    }

    private static int retentionDays(LakehouseTable table) {
        int days = table.getRetentionDays() == null ? 30 : table.getRetentionDays();
        if (days < 1 || days > 3650) throw new IllegalArgumentException("retentionDays 必须在 1 到 3650 之间");
        return days;
    }

    /**
     * Iceberg Spark procedures require a TIMESTAMP literal for named
     * older_than arguments; Spark 3.5 rejects arbitrary interval expressions
     * there.  Materialize the cutoff while building the auditable plan so the
     * Runner executes exactly the statement that was reviewed.
     */
    private static String retentionCutoff(LakehouseTable table) {
        int days = retentionDays(table);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now().minus(days, ChronoUnit.DAYS));
    }

    private static String requiredSnapshot(LakehouseMaintenanceJob job) {
        String value = job.getRequestedSnapshotId();
        if (value == null || !SNAPSHOT_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("time_travel/rollback 必须提供合法的 requestedSnapshotId");
        }
        return value;
    }

    private static void requirePart(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 格式非法");
        }
    }

    private static String quoteLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    public record Plan(String operation, String tableIdentifier, boolean mutating,
                       String snapshotId, String statement) { }
}
