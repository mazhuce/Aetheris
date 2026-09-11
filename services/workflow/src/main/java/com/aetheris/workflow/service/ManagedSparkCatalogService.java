package com.aetheris.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Registry of immutable approved artifacts and versioned Spark job definitions. */
@Service
public class ManagedSparkCatalogService {

    private static final Pattern ARTIFACT_KEY = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}\\.py$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern TABLE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*){1,2}$");
    private static final String OUTPUT_PREFIX = "hdfs://localhost:9000/user/aetheris/managed/";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExecutionAdapter adapter;

    public ManagedSparkCatalogService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                      SparkBatchExecutionAdapter adapter) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.adapter = adapter;
    }

    public List<ComputeEnvironmentView> environments() {
        return jdbc.query("""
                SELECT id, environment_key, name, adapter_type, spark_version, java_version,
                       python_version, master, max_cores, max_memory_mb, max_timeout_seconds,
                       max_concurrency, status, created_at, updated_at
                FROM workflow.compute_environment ORDER BY id
                """, this::mapEnvironment);
    }

    public List<ManagedArtifactView> artifacts() {
        return jdbc.query("""
                SELECT id, name, version, artifact_type, artifact_key, sha256, status,
                       created_by, approved_by, approved_at, revoked_at, created_at, updated_at
                FROM workflow.managed_artifact ORDER BY updated_at DESC, id DESC
                """, this::mapArtifact);
    }

    public ManagedArtifactView artifact(long id) {
        return single(jdbc.query("""
                SELECT id, name, version, artifact_type, artifact_key, sha256, status,
                       created_by, approved_by, approved_at, revoked_at, created_at, updated_at
                FROM workflow.managed_artifact WHERE id = ?
                """, this::mapArtifact, id), "受管制品不存在");
    }

    @Transactional
    public ManagedArtifactView registerArtifact(ArtifactInput input, String username) {
        String key = required(input.artifactKey(), "制品键不能为空").toLowerCase(Locale.ROOT);
        String digest = required(input.sha256(), "制品摘要不能为空").toLowerCase(Locale.ROOT);
        if (!ARTIFACT_KEY.matcher(key).matches() || !SHA256.matcher(digest).matches()) {
            throw new IllegalArgumentException("制品键或 SHA-256 格式不合法");
        }
        adapter.validate(key, digest);
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO workflow.managed_artifact
                        (name, version, artifact_type, artifact_key, sha256, status,
                         created_by, created_at, updated_at)
                    VALUES (?, ?, 'pyspark', ?, ?, 'draft', ?, now(), now()) RETURNING id
                    """, Long.class, required(input.name(), "制品名称不能为空"),
                    required(input.version(), "制品版本不能为空"), key, digest, username);
            return artifact(id);
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalStateException("同名制品版本已经存在");
        }
    }

    @Transactional
    public ManagedArtifactView approveArtifact(long id, String username) {
        ManagedArtifactView artifact = artifact(id);
        if (!"draft".equals(artifact.status())) {
            throw new IllegalStateException("只有草稿制品可以审批");
        }
        if (artifact.createdBy().equals(username)) {
            throw new IllegalStateException("制品提交人不能审批自己的变更");
        }
        adapter.validate(artifact.artifactKey(), artifact.sha256());
        int changed = jdbc.update("""
                UPDATE workflow.managed_artifact
                SET status='approved', approved_by=?, approved_at=now(), updated_at=now()
                WHERE id=? AND status='draft' AND created_by<>?
                """, username, id, username);
        if (changed != 1) throw new IllegalStateException("制品状态已变化，请刷新后重试");
        return artifact(id);
    }

    @Transactional
    public ManagedArtifactView revokeArtifact(long id) {
        int referenced = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.spark_job_definition
                WHERE artifact_id=? AND status='published'
                """, Integer.class, id);
        if (referenced > 0) throw new IllegalStateException("制品仍被已发布作业引用，不能撤销");
        int changed = jdbc.update("""
                UPDATE workflow.managed_artifact
                SET status='revoked', revoked_at=now(), updated_at=now()
                WHERE id=? AND status IN ('draft','approved')
                """, id);
        if (changed != 1) throw new IllegalStateException("制品不存在或已经撤销");
        return artifact(id);
    }

    public List<SparkJobView> jobs() {
        return jdbc.query(jobSelect() + " ORDER BY job.updated_at DESC, job.id DESC", this::mapJob);
    }

    public SparkJobView job(long id) {
        return single(jdbc.query(jobSelect() + " WHERE job.id=?", this::mapJob, id), "Spark 作业不存在");
    }

    public SparkJobView runnableJob(long id) {
        SparkJobView job = job(id);
        if (!"published".equals(job.status())) throw new IllegalStateException("Spark 作业尚未发布");
        if (!"approved".equals(job.artifactStatus())) throw new IllegalStateException("作业制品未获批准");
        if (!"active".equals(job.environmentStatus())) throw new IllegalStateException("计算环境已停用");
        return job;
    }

    @Transactional
    public SparkJobView createJob(SparkJobInput input, String username) {
        validateJobInput(input);
        ManagedArtifactView artifact = artifact(input.artifactId());
        if ("revoked".equals(artifact.status())) throw new IllegalStateException("已撤销制品不能创建作业");
        ComputeEnvironmentView environment = environment(input.environmentId());
        if (!"active".equals(environment.status())) throw new IllegalStateException("计算环境已停用");
        validateResources(input, environment);
        int version = jdbc.queryForObject("""
                SELECT COALESCE(max(version),0)+1 FROM workflow.spark_job_definition WHERE name=?
                """, Integer.class, required(input.name(), "作业名称不能为空"));
        Long id = jdbc.queryForObject("""
                INSERT INTO workflow.spark_job_definition
                    (name, version, environment_id, artifact_id, parameter_schema,
                     default_parameters, input_tables, output_table, output_uri, write_mode,
                     driver_cores, driver_memory_mb, timeout_seconds, max_retries, status,
                     owner, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::jsonb, '{}'::jsonb, ?::jsonb, ?, ?,
                        'overwrite_partition', ?, ?, ?, ?, 'draft', ?, ?, now(), now())
                RETURNING id
                """, Long.class, input.name().trim(), version, input.environmentId(), input.artifactId(),
                "{\"bizDate\":{\"type\":\"date\",\"required\":true}}",
                json(input.inputTables()), blankToNull(input.outputTable()), input.outputUri().trim(),
                input.driverCores(), input.driverMemoryMb(), input.timeoutSeconds(),
                input.maxRetries(), username, blankToNull(input.description()));
        return job(id);
    }

    @Transactional
    public SparkJobView publishJob(long id, String username) {
        SparkJobView job = job(id);
        if (!"draft".equals(job.status())) throw new IllegalStateException("只有草稿作业可以发布");
        if (job.owner().equals(username)) throw new IllegalStateException("作业创建人不能发布自己的变更");
        if (!"approved".equals(job.artifactStatus())) throw new IllegalStateException("制品审批通过后才能发布作业");
        adapter.validate(job.artifactKey(), job.artifactDigest());
        String digest = definitionDigest(job);
        int changed = jdbc.update("""
                UPDATE workflow.spark_job_definition
                SET status='published', definition_digest=?, published_by=?, published_at=now(),
                    updated_at=now()
                WHERE id=? AND status='draft' AND owner<>?
                """, digest, username, id, username);
        if (changed != 1) throw new IllegalStateException("作业状态已变化，请刷新后重试");
        return job(id);
    }

    @Transactional
    public SparkJobView disableJob(long id) {
        int active = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.execution_attempt
                WHERE job_definition_id=? AND status IN ('queued','submitting','running','cancel_requested')
                """, Integer.class, id);
        if (active > 0) throw new IllegalStateException("作业仍有活动执行，不能停用");
        int changed = jdbc.update("""
                UPDATE workflow.spark_job_definition SET status='disabled', updated_at=now()
                WHERE id=? AND status IN ('draft','published')
                """, id);
        if (changed != 1) throw new IllegalStateException("作业不存在或已经停用");
        return job(id);
    }

    public ComputeEnvironmentView environment(long id) {
        return single(jdbc.query("""
                SELECT id, environment_key, name, adapter_type, spark_version, java_version,
                       python_version, master, max_cores, max_memory_mb, max_timeout_seconds,
                       max_concurrency, status, created_at, updated_at
                FROM workflow.compute_environment WHERE id=?
                """, this::mapEnvironment, id), "计算环境不存在");
    }

    private void validateJobInput(SparkJobInput input) {
        required(input.name(), "作业名称不能为空");
        if (input.inputTables() == null || input.inputTables().isEmpty()
                || input.inputTables().stream().anyMatch(value -> !TABLE.matcher(value).matches())) {
            throw new IllegalArgumentException("输入表必须使用 catalog.schema.table 或 schema.table 标识");
        }
        if (!StringUtils.hasText(input.outputUri()) || !input.outputUri().startsWith(OUTPUT_PREFIX)
                || input.outputUri().contains("..")) {
            throw new IllegalArgumentException("输出路径必须位于受管 HDFS 目录");
        }
        if (StringUtils.hasText(input.outputTable()) && !TABLE.matcher(input.outputTable()).matches()) {
            throw new IllegalArgumentException("输出表标识不合法");
        }
        if (input.driverCores() == null || input.driverMemoryMb() == null
                || input.timeoutSeconds() == null || input.maxRetries() == null) {
            throw new IllegalArgumentException("资源与重试参数不能为空");
        }
    }

    private void validateResources(SparkJobInput input, ComputeEnvironmentView environment) {
        if (input.driverCores() < 1 || input.driverCores() > environment.maxCores()
                || input.driverMemoryMb() < 512 || input.driverMemoryMb() > environment.maxMemoryMb()
                || input.timeoutSeconds() < 30 || input.timeoutSeconds() > environment.maxTimeoutSeconds()
                || input.maxRetries() < 0 || input.maxRetries() > 3) {
            throw new IllegalArgumentException("作业资源参数超出计算环境白名单");
        }
    }

    private String definitionDigest(SparkJobView job) {
        String canonical = String.join("|", job.name(), String.valueOf(job.version()),
                job.artifactDigest(), json(job.inputTables()), job.outputUri(),
                String.valueOf(job.driverCores()), String.valueOf(job.driverMemoryMb()),
                String.valueOf(job.timeoutSeconds()), "overwrite_partition");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private String jobSelect() {
        return """
                SELECT job.id, job.name, job.version, job.environment_id, environment.name environment_name,
                       environment.status environment_status, job.artifact_id, artifact.name artifact_name,
                       artifact.artifact_key, artifact.sha256 artifact_digest, artifact.status artifact_status,
                       job.parameter_schema, job.default_parameters, job.input_tables, job.output_table,
                       job.output_uri, job.write_mode, job.driver_cores, job.driver_memory_mb,
                       job.timeout_seconds, job.max_retries, job.status, job.definition_digest,
                       job.owner, job.description, job.published_by, job.published_at,
                       job.created_at, job.updated_at
                FROM workflow.spark_job_definition job
                JOIN workflow.compute_environment environment ON environment.id=job.environment_id
                JOIN workflow.managed_artifact artifact ON artifact.id=job.artifact_id
                """;
    }

    private ComputeEnvironmentView mapEnvironment(ResultSet rs, int row) throws SQLException {
        return new ComputeEnvironmentView(rs.getLong("id"), rs.getString("environment_key"),
                rs.getString("name"), rs.getString("adapter_type"), rs.getString("spark_version"),
                rs.getString("java_version"), rs.getString("python_version"), rs.getString("master"),
                rs.getInt("max_cores"), rs.getInt("max_memory_mb"), rs.getInt("max_timeout_seconds"),
                rs.getInt("max_concurrency"), rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ManagedArtifactView mapArtifact(ResultSet rs, int row) throws SQLException {
        return new ManagedArtifactView(rs.getLong("id"), rs.getString("name"), rs.getString("version"),
                rs.getString("artifact_type"), rs.getString("artifact_key"), rs.getString("sha256"),
                rs.getString("status"), rs.getString("created_by"), rs.getString("approved_by"),
                rs.getObject("approved_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private SparkJobView mapJob(ResultSet rs, int row) throws SQLException {
        return new SparkJobView(rs.getLong("id"), rs.getString("name"), rs.getInt("version"),
                rs.getLong("environment_id"), rs.getString("environment_name"), rs.getString("environment_status"),
                rs.getLong("artifact_id"), rs.getString("artifact_name"), rs.getString("artifact_key"),
                rs.getString("artifact_digest"), rs.getString("artifact_status"),
                tree(rs.getString("parameter_schema")), tree(rs.getString("default_parameters")),
                objectMapper.convertValue(tree(rs.getString("input_tables")),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                rs.getString("output_table"), rs.getString("output_uri"), rs.getString("write_mode"),
                rs.getInt("driver_cores"), rs.getInt("driver_memory_mb"), rs.getInt("timeout_seconds"),
                rs.getInt("max_retries"), rs.getString("status"), rs.getString("definition_digest"),
                rs.getString("owner"), rs.getString("description"), rs.getString("published_by"),
                rs.getObject("published_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private JsonNode tree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("数据库 JSON 无法解析", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("定义无法序列化", error);
        }
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static <T> T single(List<T> values, String message) {
        if (values.isEmpty()) throw new IllegalArgumentException(message);
        return values.getFirst();
    }

    public record ComputeEnvironmentView(
            Long id, String environmentKey, String name, String adapterType,
            String sparkVersion, String javaVersion, String pythonVersion, String master,
            int maxCores, int maxMemoryMb, int maxTimeoutSeconds, int maxConcurrency,
            String status, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record ManagedArtifactView(
            Long id, String name, String version, String artifactType, String artifactKey,
            String sha256, String status, String createdBy, String approvedBy,
            OffsetDateTime approvedAt, OffsetDateTime revokedAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record ArtifactInput(String name, String version, String artifactKey, String sha256) { }

    public record SparkJobInput(
            String name, Long environmentId, Long artifactId, List<String> inputTables,
            String outputTable, String outputUri, Integer driverCores, Integer driverMemoryMb,
            Integer timeoutSeconds, Integer maxRetries, String description) { }

    public record SparkJobView(
            Long id, String name, int version, Long environmentId, String environmentName,
            String environmentStatus, Long artifactId, String artifactName, String artifactKey,
            String artifactDigest, String artifactStatus, JsonNode parameterSchema,
            JsonNode defaultParameters, List<String> inputTables, String outputTable,
            String outputUri, String writeMode, int driverCores, int driverMemoryMb,
            int timeoutSeconds, int maxRetries, String status, String definitionDigest,
            String owner, String description, String publishedBy, OffsetDateTime publishedAt,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
}
