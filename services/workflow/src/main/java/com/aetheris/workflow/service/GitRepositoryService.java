package com.aetheris.workflow.service;

import com.aetheris.workflow.service.GitRemoteInspector.RemoteHead;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Audited registry of remote Git repositories and immutable observed revisions. */
@Service
public class GitRepositoryService {

    private static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    private static final Set<String> STATUSES = Set.of("active", "disabled");

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
        return jdbc.query("""
                SELECT id,repository_key,name,remote_url,default_branch,
                       credential_ref IS NOT NULL credential_configured,status,current_commit_sha,
                       last_synced_at,created_by,created_at,updated_at
                FROM workflow.source_repository ORDER BY updated_at DESC,id DESC
                """, (rs, row) -> new RepositoryView(
                rs.getLong("id"), rs.getString("repository_key"), rs.getString("name"),
                rs.getString("remote_url"), rs.getString("default_branch"),
                rs.getBoolean("credential_configured"), rs.getString("status"),
                rs.getString("current_commit_sha"), rs.getObject("last_synced_at", OffsetDateTime.class),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    public RepositoryView register(RepositoryInput input, String username) {
        String key = required(input.repositoryKey(), "仓库标识不能为空").toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) throw new IllegalArgumentException("仓库标识格式不合法");
        String name = required(input.name(), "仓库名称不能为空");
        if (name.length() > 128) throw new IllegalArgumentException("仓库名称不能超过 128 个字符");
        String actor = actor(username);
        RemoteHead head = inspector.inspect(input.remoteUrl(), input.defaultBranch(),
                input.credentialUsername(), input.credentialRef());
        try {
            Long id = transactions.execute(status -> {
                Long repositoryId = jdbc.queryForObject("""
                        INSERT INTO workflow.source_repository
                            (repository_key,name,remote_url,default_branch,credential_username,
                             credential_ref,status,current_commit_sha,last_synced_at,created_by)
                        VALUES (?,?,?,?,?,?,'active',?,now(),?) RETURNING id
                        """, Long.class, key, name, head.remoteUrl(), head.branch(),
                        blank(input.credentialUsername()), blank(input.credentialRef()), head.commitSha(), actor);
                recordRevision(repositoryId, head, actor);
                event(repositoryId, "registered", actor, null, head.commitSha());
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
        transactions.executeWithoutResult(status -> {
            RepositoryRecord current = lockedRecord(id);
            if (!"active".equals(current.status())) {
                throw new IllegalStateException("仓库已在同步期间停用，请重新确认状态");
            }
            recordRevision(id, head, actor);
            int changed = jdbc.update("""
                    UPDATE workflow.source_repository
                    SET current_commit_sha=?,last_synced_at=now(),updated_at=now()
                    WHERE id=? AND status='active'
                    """, head.commitSha(), id);
            if (changed != 1) throw new IllegalStateException("Git 仓库状态已变化，请重试");
            event(id, "synchronized", actor, current.currentCommitSha(), head.commitSha());
        });
        return repository(id);
    }

    public RepositoryView changeStatus(long id, String requestedStatus, String username) {
        String target = required(requestedStatus, "仓库状态不能为空").toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(target)) throw new IllegalArgumentException("仓库状态不合法");
        RepositoryRecord observed = record(id);
        String actor = actor(username);
        RemoteHead refreshed = null;
        if ("active".equals(target)) {
            refreshed = inspector.inspect(observed.remoteUrl(), observed.defaultBranch(),
                    observed.credentialUsername(), observed.credentialRef());
        }
        RemoteHead finalRefreshed = refreshed;
        transactions.executeWithoutResult(status -> {
            RepositoryRecord current = lockedRecord(id);
            if (target.equals(current.status())) return;
            if ("active".equals(target)) {
                recordRevision(id, finalRefreshed, actor);
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
                            ? current.currentCommitSha() : finalRefreshed.commitSha());
        });
        return repository(id);
    }

    public List<RevisionView> revisions(long repositoryId) {
        repository(repositoryId);
        return jdbc.query("""
                SELECT id,repository_id,branch_name,commit_sha,discovered_by,discovered_at
                FROM workflow.source_revision WHERE repository_id=?
                ORDER BY discovered_at DESC,id DESC
                """, (rs, row) -> new RevisionView(rs.getLong("id"), rs.getLong("repository_id"),
                rs.getString("branch_name"), rs.getString("commit_sha"),
                rs.getString("discovered_by"), rs.getObject("discovered_at", OffsetDateTime.class)),
                repositoryId);
    }

    private RepositoryView repository(long id) {
        List<RepositoryView> values = jdbc.query("""
                SELECT id,repository_key,name,remote_url,default_branch,
                       credential_ref IS NOT NULL credential_configured,status,current_commit_sha,
                       last_synced_at,created_by,created_at,updated_at
                FROM workflow.source_repository WHERE id=?
                """, (rs, row) -> new RepositoryView(
                rs.getLong("id"), rs.getString("repository_key"), rs.getString("name"),
                rs.getString("remote_url"), rs.getString("default_branch"),
                rs.getBoolean("credential_configured"), rs.getString("status"),
                rs.getString("current_commit_sha"), rs.getObject("last_synced_at", OffsetDateTime.class),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), id);
        if (values.isEmpty()) throw new IllegalArgumentException("Git 仓库不存在");
        return values.getFirst();
    }

    private RepositoryRecord record(long id) {
        List<RepositoryRecord> values = jdbc.query("""
                SELECT id,remote_url,default_branch,credential_username,credential_ref,status,current_commit_sha
                FROM workflow.source_repository WHERE id=?
                """, (rs, row) -> new RepositoryRecord(rs.getLong("id"), rs.getString("remote_url"),
                rs.getString("default_branch"), rs.getString("credential_username"),
                rs.getString("credential_ref"), rs.getString("status"),
                rs.getString("current_commit_sha")), id);
        if (values.isEmpty()) throw new IllegalArgumentException("Git 仓库不存在");
        return values.getFirst();
    }

    private RepositoryRecord lockedRecord(long id) {
        List<RepositoryRecord> values = jdbc.query("""
                SELECT id,remote_url,default_branch,credential_username,credential_ref,status,current_commit_sha
                FROM workflow.source_repository WHERE id=? FOR UPDATE
                """, (rs, row) -> new RepositoryRecord(rs.getLong("id"), rs.getString("remote_url"),
                rs.getString("default_branch"), rs.getString("credential_username"),
                rs.getString("credential_ref"), rs.getString("status"),
                rs.getString("current_commit_sha")), id);
        if (values.isEmpty()) throw new IllegalArgumentException("Git 仓库不存在");
        return values.getFirst();
    }

    private void recordRevision(long id, RemoteHead head, String actor) {
        jdbc.update("""
                INSERT INTO workflow.source_revision
                    (repository_id,branch_name,commit_sha,discovered_by)
                VALUES (?,?,?,?) ON CONFLICT (repository_id,commit_sha) DO NOTHING
                """, id, head.branch(), head.commitSha(), actor);
    }

    private void event(long id, String type, String actor, String from, String to) {
        jdbc.update("""
                INSERT INTO workflow.source_repository_event
                    (repository_id,event_type,actor,from_commit_sha,to_commit_sha,details_json)
                VALUES (?,?,?,?,?,'{}'::jsonb)
                """, id, type, actor, from, to);
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

    private record RepositoryRecord(long id, String remoteUrl, String defaultBranch,
                                    String credentialUsername, String credentialRef,
                                    String status, String currentCommitSha) { }

    public record RepositoryInput(String repositoryKey, String name, String remoteUrl,
                                  String defaultBranch, String credentialUsername,
                                  String credentialRef) { }
    public record RepositoryStatusInput(String status) { }
    public record RepositoryView(long id, String repositoryKey, String name, String remoteUrl,
                                 String defaultBranch, boolean credentialConfigured, String status,
                                 String currentCommitSha, OffsetDateTime lastSyncedAt, String createdBy,
                                 OffsetDateTime createdAt, OffsetDateTime updatedAt) { }
    public record RevisionView(long id, long repositoryId, String branchName, String commitSha,
                               String discoveredBy, OffsetDateTime discoveredAt) { }
}
