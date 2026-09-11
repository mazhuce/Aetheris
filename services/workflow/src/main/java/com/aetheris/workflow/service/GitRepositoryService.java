package com.aetheris.workflow.service;

import com.aetheris.workflow.service.GitRemoteInspector.RemoteHead;
import com.aetheris.workflow.service.GitRemoteInspector.RevisionEvidence;
import com.aetheris.workflow.service.GitRemoteInspector.SourceEvidence;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Audited registry of remote Git repositories, signature policy and immutable revisions. */
@Service
public class GitRepositoryService {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Set<String> STATUSES = Set.of("active", "disabled");
    private static final Set<String> SIGNATURE_POLICIES = Set.of("required", "optional");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final GitRemoteInspector inspector;

    public GitRepositoryService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                GitRemoteInspector inspector) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.inspector = inspector;
    }

    public List<RepositoryView> repositories() {
        return jdbc.query(repositorySelect() + " ORDER BY updated_at DESC,id DESC", this::mapRepository);
    }

    public RepositoryView register(RepositoryInput input, String username) {
        String key = required(input.repositoryKey(), "仓库标识不能为空").toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("仓库标识格式不合法");
        String name = required(input.name(), "仓库名称不能为空");
        if (name.length() > 128) throw new IllegalArgumentException("仓库名称不能超过 128 个字符");
        String actor = actor(username);
        SignaturePolicy policy = signaturePolicy(input.signaturePolicy(), input.trustedSignerFingerprints());
        RemoteHead head = inspector.inspect(input.remoteUrl(), input.defaultBranch(),
                input.credentialUsername(), input.credentialRef());
        RevisionEvidence revision = inspector.inspectRevision(head, input.credentialUsername(),
                input.credentialRef(), policy.policy(), policy.fingerprints());
        try {
            Long id = transactions.execute(status -> {
                Long repositoryId = jdbc.queryForObject("""
                        INSERT INTO workflow.source_repository
                            (repository_key,name,remote_url,default_branch,credential_username,
                             credential_ref,signature_policy,trusted_signer_fingerprints,status,
                             current_commit_sha,last_synced_at,created_by)
                        VALUES (?,?,?,?,?,?,?,?,'active',?,now(),?) RETURNING id
                        """, Long.class, key, name, head.remoteUrl(), head.branch(),
                        blank(input.credentialUsername()), blank(input.credentialRef()), policy.policy(),
                        policy.csv(), head.commitSha(), actor);
                recordRevision(repositoryId, head.branch(), revision, actor);
                event(repositoryId, "registered", actor, null, head.commitSha(), revision.signatureStatus());
                return repositoryId;
            });
            return repository(id);
        } catch (DataIntegrityViolationException conflict) {
            throw new IllegalStateException("仓库标识或远端地址已经存在");
        }
    }

    public RepositoryView synchronize(long id, String username) {
        RepositoryRecord observed = record(id);
        if (!"active".equals(observed.status())) throw new IllegalStateException("已停用仓库不能同步");
        String actor = actor(username);
        RemoteHead head = inspector.inspect(observed.remoteUrl(), observed.defaultBranch(),
                observed.credentialUsername(), observed.credentialRef());
        RevisionEvidence revision = inspector.inspectRevision(head, observed.credentialUsername(),
                observed.credentialRef(), observed.signaturePolicy(), observed.trustedFingerprints());
        transactions.executeWithoutResult(status -> {
            RepositoryRecord current = lockedRecord(id);
            if (!"active".equals(current.status())) {
                throw new IllegalStateException("仓库已在同步期间停用，请重新确认状态");
            }
            if (!current.signaturePolicy().equals(observed.signaturePolicy())
                    || !current.trustedFingerprints().equals(observed.trustedFingerprints())) {
                throw new IllegalStateException("仓库信任策略在同步期间发生变化，请重试");
            }
            recordRevision(id, head.branch(), revision, actor);
            int changed = jdbc.update("""
                    UPDATE workflow.source_repository
                    SET current_commit_sha=?,last_synced_at=now(),updated_at=now()
                    WHERE id=? AND status='active'
                    """, head.commitSha(), id);
            if (changed != 1) throw new IllegalStateException("Git 仓库状态已变化，请重试");
            event(id, "synchronized", actor, current.currentCommitSha(), head.commitSha(),
                    revision.signatureStatus());
        });
        return repository(id);
    }

    public RepositoryView changeStatus(long id, String requestedStatus, String username) {
        String target = required(requestedStatus, "仓库状态不能为空").toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(target)) throw new IllegalArgumentException("仓库状态不合法");
        RepositoryRecord observed = record(id);
        String actor = actor(username);
        RemoteHead refreshed = null;
        RevisionEvidence revision = null;
        if ("active".equals(target)) {
            refreshed = inspector.inspect(observed.remoteUrl(), observed.defaultBranch(),
                    observed.credentialUsername(), observed.credentialRef());
            revision = inspector.inspectRevision(refreshed, observed.credentialUsername(),
                    observed.credentialRef(), observed.signaturePolicy(), observed.trustedFingerprints());
        }
        RemoteHead finalRefreshed = refreshed;
        RevisionEvidence finalRevision = revision;
        transactions.executeWithoutResult(status -> {
            RepositoryRecord current = lockedRecord(id);
            if (target.equals(current.status())) return;
            if ("active".equals(target)) {
                if (!current.signaturePolicy().equals(observed.signaturePolicy())
                        || !current.trustedFingerprints().equals(observed.trustedFingerprints())) {
                    throw new IllegalStateException("仓库信任策略在启用期间发生变化，请重试");
                }
                recordRevision(id, finalRefreshed.branch(), finalRevision, actor);
                jdbc.update("""
                        UPDATE workflow.source_repository
                        SET status='active',current_commit_sha=?,last_synced_at=now(),updated_at=now()
                        WHERE id=?
                        """, finalRefreshed.commitSha(), id);
            } else {
                jdbc.update("UPDATE workflow.source_repository SET status=?,updated_at=now() WHERE id=?",
                        target, id);
            }
            event(id, target.equals("active") ? "enabled" : "disabled", actor,
                    current.currentCommitSha(), finalRefreshed == null
                            ? current.currentCommitSha() : finalRefreshed.commitSha(),
                    finalRevision == null ? null : finalRevision.signatureStatus());
        });
        return repository(id);
    }

    public RepositoryView changeTrust(long id, RepositoryTrustInput input, String username) {
        if (input == null) throw new IllegalArgumentException("Git 仓库信任策略不能为空");
        RepositoryRecord observed = record(id);
        String actor = actor(username);
        SignaturePolicy target = signaturePolicy(input.signaturePolicy(), input.trustedSignerFingerprints());
        RemoteHead head = inspector.inspect(observed.remoteUrl(), observed.defaultBranch(),
                observed.credentialUsername(), observed.credentialRef());
        RevisionEvidence revision = inspector.inspectRevision(head, observed.credentialUsername(),
                observed.credentialRef(), target.policy(), target.fingerprints());
        transactions.executeWithoutResult(status -> {
            RepositoryRecord current = lockedRecord(id);
            if (!current.status().equals(observed.status())
                    || !java.util.Objects.equals(current.currentCommitSha(), observed.currentCommitSha())
                    || !current.signaturePolicy().equals(observed.signaturePolicy())
                    || !current.trustedFingerprints().equals(observed.trustedFingerprints())) {
                throw new IllegalStateException("仓库状态或信任策略已被其他操作修改，请刷新后重试");
            }
            recordRevision(id, head.branch(), revision, actor);
            jdbc.update("""
                    UPDATE workflow.source_repository
                    SET signature_policy=?,trusted_signer_fingerprints=?,current_commit_sha=?,
                        last_synced_at=now(),updated_at=now()
                    WHERE id=?
                    """, target.policy(), target.csv(), head.commitSha(), id);
            jdbc.update("""
                    INSERT INTO workflow.source_repository_event
                        (repository_id,event_type,actor,from_commit_sha,to_commit_sha,details_json)
                    VALUES (?, 'trust_updated', ?, ?, ?, jsonb_build_object(
                        'signatureStatus',?,'fromPolicy',?,'toPolicy',?,
                        'trustedSignerFingerprints',?))
                    """, id, actor, current.currentCommitSha(), head.commitSha(),
                    revision.signatureStatus(), current.signaturePolicy(), target.policy(), target.csv());
        });
        return repository(id);
    }

    /** Re-reads an exact commit/path and enforces the repository's current signature policy. */
    public SourceEvidence verifySource(long id, String commitSha, String sourcePath) {
        RepositoryRecord observed = record(id);
        if (!"active".equals(observed.status())) throw new IllegalStateException("Git 仓库已停用");
        Integer known = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.source_revision
                WHERE repository_id=? AND commit_sha=?
                """, Integer.class, id, commitSha);
        if (known == null || known != 1) throw new IllegalStateException("Git 修订尚未被仓库同步发现");
        SourceEvidence evidence = inspector.inspectSource(observed.remoteUrl(), observed.defaultBranch(),
                commitSha, sourcePath, observed.credentialUsername(), observed.credentialRef(),
                observed.signaturePolicy(), observed.trustedFingerprints());
        RepositoryRecord current = record(id);
        if (!"active".equals(current.status())
                || !current.remoteUrl().equals(observed.remoteUrl())
                || !current.defaultBranch().equals(observed.defaultBranch())
                || !current.signaturePolicy().equals(observed.signaturePolicy())
                || !current.trustedFingerprints().equals(observed.trustedFingerprints())) {
            throw new IllegalStateException("Git 仓库安全配置在验证期间发生变化，请重试");
        }
        return evidence;
    }

    /** Locks the repository and re-applies the current trust policy before evidence is persisted. */
    void assertEvidenceStillTrusted(long id, SourceEvidence evidence) {
        RepositoryRecord current = lockedRecord(id);
        if (!"active".equals(current.status())) throw new IllegalStateException("Git 仓库已停用");
        Integer known = jdbc.queryForObject("""
                SELECT count(*) FROM workflow.source_revision
                WHERE repository_id=? AND commit_sha=?
                """, Integer.class, id, evidence.revision().commitSha());
        if (known == null || known != 1) throw new IllegalStateException("Git 修订尚未被仓库同步发现");
        GitRemoteInspector.enforceSignature(new GitRemoteInspector.SignatureEvidence(
                        evidence.revision().signatureStatus(), evidence.revision().signerFingerprint(),
                        evidence.revision().signatureCreatedAt()),
                current.signaturePolicy(), current.trustedFingerprints());
    }

    public List<RevisionView> revisions(long repositoryId) {
        repository(repositoryId);
        return jdbc.query("""
                SELECT id,repository_id,branch_name,commit_sha,signature_status,
                       signer_fingerprint,signature_verified_at,discovered_by,discovered_at
                FROM workflow.source_revision WHERE repository_id=?
                ORDER BY discovered_at DESC,id DESC
                """, (rs, row) -> new RevisionView(rs.getLong("id"), rs.getLong("repository_id"),
                rs.getString("branch_name"), rs.getString("commit_sha"),
                rs.getString("signature_status"), rs.getString("signer_fingerprint"),
                rs.getObject("signature_verified_at", OffsetDateTime.class),
                rs.getString("discovered_by"), rs.getObject("discovered_at", OffsetDateTime.class)),
                repositoryId);
    }

    private RepositoryView repository(long id) {
        List<RepositoryView> values = jdbc.query(repositorySelect() + " WHERE id=?", this::mapRepository, id);
        if (values.isEmpty()) throw new IllegalArgumentException("Git 仓库不存在");
        return values.getFirst();
    }

    private String repositorySelect() {
        return """
                SELECT id,repository_key,name,remote_url,default_branch,
                       credential_ref IS NOT NULL credential_configured,signature_policy,
                       trusted_signer_fingerprints,status,current_commit_sha,last_synced_at,
                       created_by,created_at,updated_at
                FROM workflow.source_repository
                """;
    }

    private RepositoryView mapRepository(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RepositoryView(rs.getLong("id"), rs.getString("repository_key"), rs.getString("name"),
                rs.getString("remote_url"), rs.getString("default_branch"),
                rs.getBoolean("credential_configured"), rs.getString("signature_policy"),
                list(rs.getString("trusted_signer_fingerprints")), rs.getString("status"),
                rs.getString("current_commit_sha"), rs.getObject("last_synced_at", OffsetDateTime.class),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RepositoryRecord record(long id) {
        List<RepositoryRecord> values = jdbc.query(repositoryRecordSelect() + " WHERE id=?",
                this::mapRepositoryRecord, id);
        if (values.isEmpty()) throw new IllegalArgumentException("Git 仓库不存在");
        return values.getFirst();
    }

    private RepositoryRecord lockedRecord(long id) {
        List<RepositoryRecord> values = jdbc.query(repositoryRecordSelect() + " WHERE id=? FOR UPDATE",
                this::mapRepositoryRecord, id);
        if (values.isEmpty()) throw new IllegalArgumentException("Git 仓库不存在");
        return values.getFirst();
    }

    private String repositoryRecordSelect() {
        return """
                SELECT id,remote_url,default_branch,credential_username,credential_ref,status,
                       current_commit_sha,signature_policy,trusted_signer_fingerprints
                FROM workflow.source_repository
                """;
    }

    private RepositoryRecord mapRepositoryRecord(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RepositoryRecord(rs.getLong("id"), rs.getString("remote_url"),
                rs.getString("default_branch"), rs.getString("credential_username"),
                rs.getString("credential_ref"), rs.getString("status"),
                rs.getString("current_commit_sha"), rs.getString("signature_policy"),
                Set.copyOf(list(rs.getString("trusted_signer_fingerprints"))));
    }

    private void recordRevision(long id, String branch, RevisionEvidence revision, String actor) {
        jdbc.update("""
                INSERT INTO workflow.source_revision
                    (repository_id,branch_name,commit_sha,signature_status,signer_fingerprint,
                     signature_verified_at,discovered_by)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (repository_id,commit_sha) DO NOTHING
                """, id, branch, revision.commitSha(), revision.signatureStatus(),
                revision.signerFingerprint(), OffsetDateTime.now(), actor);
    }

    private void event(long id, String type, String actor, String from, String to, String signatureStatus) {
        jdbc.update("""
                INSERT INTO workflow.source_repository_event
                    (repository_id,event_type,actor,from_commit_sha,to_commit_sha,details_json)
                VALUES (?,?,?,?,?,jsonb_build_object('signatureStatus',?))
                """, id, type, actor, from, to, signatureStatus);
    }

    private static SignaturePolicy signaturePolicy(String requested, List<String> requestedFingerprints) {
        String policy = StringUtils.hasText(requested) ? requested.trim().toLowerCase(Locale.ROOT) : "required";
        if (!SIGNATURE_POLICIES.contains(policy)) throw new IllegalArgumentException("Git 签名策略不合法");
        LinkedHashSet<String> fingerprints = new LinkedHashSet<>();
        if (requestedFingerprints != null) {
            for (String item : requestedFingerprints) {
                String value = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
                if (!FINGERPRINT.matcher(value).matches()) {
                    throw new IllegalArgumentException("可信签名指纹必须是 40 或 64 位十六进制值");
                }
                fingerprints.add(value);
            }
        }
        if (fingerprints.size() > 32) throw new IllegalArgumentException("单个仓库最多配置 32 个可信签名指纹");
        if ("required".equals(policy) && fingerprints.isEmpty()) {
            throw new IllegalArgumentException("强制签名仓库必须配置至少一个可信签名指纹");
        }
        String csv = fingerprints.isEmpty() ? null : String.join(",", fingerprints);
        if (csv != null && csv.length() > 4096) throw new IllegalArgumentException("可信签名指纹配置过长");
        return new SignaturePolicy(policy, Set.copyOf(fingerprints), csv);
    }

    private static List<String> list(String csv) {
        return StringUtils.hasText(csv) ? Arrays.asList(csv.split(",")) : List.of();
    }

    private static String required(String value, String message) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String blank(String value) { return StringUtils.hasText(value) ? value.trim() : null; }

    private static String actor(String username) {
        String value = required(username, "操作者身份不能为空");
        if (value.length() > 64) throw new IllegalArgumentException("操作者身份过长");
        return value;
    }

    private record SignaturePolicy(String policy, Set<String> fingerprints, String csv) { }
    private record RepositoryRecord(long id, String remoteUrl, String defaultBranch,
                                    String credentialUsername, String credentialRef,
                                    String status, String currentCommitSha, String signaturePolicy,
                                    Set<String> trustedFingerprints) { }

    public record RepositoryInput(String repositoryKey, String name, String remoteUrl,
                                  String defaultBranch, String credentialUsername,
                                  String credentialRef, String signaturePolicy,
                                  List<String> trustedSignerFingerprints) {
        public RepositoryInput(String repositoryKey, String name, String remoteUrl,
                               String defaultBranch, String credentialUsername, String credentialRef) {
            this(repositoryKey, name, remoteUrl, defaultBranch, credentialUsername,
                    credentialRef, "required", List.of());
        }
    }
    public record RepositoryStatusInput(String status) { }
    public record RepositoryTrustInput(String signaturePolicy,
                                       List<String> trustedSignerFingerprints) { }
    public record RepositoryView(long id, String repositoryKey, String name, String remoteUrl,
                                 String defaultBranch, boolean credentialConfigured,
                                 String signaturePolicy, List<String> trustedSignerFingerprints,
                                 String status, String currentCommitSha,
                                 OffsetDateTime lastSyncedAt, String createdBy,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record RevisionView(long id, long repositoryId, String branchName, String commitSha,
                               String signatureStatus, String signerFingerprint,
                               OffsetDateTime signatureVerifiedAt,
                               String discoveredBy, OffsetDateTime discoveredAt) { }
}
