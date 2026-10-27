package com.aetheris.auth.service;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.validation.UsernameRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL is the authority for every Gateway, refresh and logout. No local positive cache. */
@Service
public class BrowserSessionService {
    private final JdbcTemplate jdbc;

    public BrowserSessionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Session(UUID sid, long uid, String username, String role, String nickname,
                          long accessVersion, long refreshVersion, Instant expiresAt) { }

    private static final RowMapper<Session> SESSION = (row, ignored) -> new Session(
            row.getObject("sid", UUID.class), row.getLong("user_id"), row.getString("username"),
            row.getString("role"), row.getString("nickname"), row.getLong("access_version"),
            row.getLong("refresh_version"), row.getTimestamp("expires_at").toInstant());

    public Optional<Session> open(AuthUser user, Duration lifetime) {
        requireSubject(user.getId(), user.getUsername(), user.getAccessVersion());
        long lifetimeMs = Objects.requireNonNull(lifetime).toMillis();
        if (lifetimeMs < 1000) throw new IllegalArgumentException("Browser session lifetime is too short");
        // At most 100 expired rows of this owner per login. Deleting absent authority cannot revive tokens.
        jdbc.update("""
                DELETE FROM auth.browser_session WHERE sid IN (
                    SELECT sid FROM auth.browser_session WHERE user_id=? AND expires_at<=clock_timestamp()
                    ORDER BY expires_at LIMIT 100)
                """, user.getId());
        return jdbc.query("""
                WITH opened AS (
                    INSERT INTO auth.browser_session(sid,user_id,username,access_version,account_session_version,expires_at)
                    SELECT ?,u.id,u.username,u.access_version,u.session_version,
                           clock_timestamp() + (? * interval '1 millisecond')
                    FROM auth.auth_user u
                    WHERE u.id=? AND u.username=? AND u.status='active'
                      AND u.access_version=? AND u.session_version=? AND u.password_hash=?
                    RETURNING *)
                SELECT opened.*,u.role,u.nickname FROM opened JOIN auth.auth_user u ON u.id=opened.user_id
                """, SESSION, UUID.randomUUID(), lifetimeMs, user.getId(), user.getUsername(),
                user.getAccessVersion(), user.getSessionVersion(), user.getPasswordHash()).stream().findFirst();
    }

    public Optional<Session> rotate(UUID sid, long uid, String username, long accessVersion, long refreshVersion) {
        requireSubject(uid, username, accessVersion); Objects.requireNonNull(sid);
        if (refreshVersion < 0 || refreshVersion == Long.MAX_VALUE) return Optional.empty();
        // UPDATE serializes on the same row as revoke. A late successful response retains the revoked sid.
        return jdbc.query("""
                UPDATE auth.browser_session s SET refresh_version=s.refresh_version+1
                FROM auth.auth_user u
                WHERE s.sid=? AND s.user_id=? AND s.username=? AND s.access_version=? AND s.refresh_version=?
                  AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
                  AND u.id=s.user_id AND u.username=s.username AND u.status='active'
                  AND u.access_version=s.access_version AND u.session_version=s.account_session_version
                RETURNING s.*,u.role,u.nickname
                """, SESSION, sid, uid, username, accessVersion, refreshVersion).stream().findFirst();
    }

    public boolean active(UUID sid, long uid, String username, long accessVersion) {
        requireSubject(uid, username, accessVersion); Objects.requireNonNull(sid);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM auth.browser_session s JOIN auth.auth_user u ON u.id=s.user_id
                    WHERE s.sid=? AND s.user_id=? AND s.username=? AND s.access_version=?
                      AND s.revoked_at IS NULL AND s.expires_at>clock_timestamp()
                      AND u.username=s.username AND u.status='active' AND u.access_version=s.access_version
                      AND u.session_version=s.account_session_version)
                """, Boolean.class, sid, uid, username, accessVersion));
    }

    public void revoke(UUID sid, long uid, String username, long accessVersion) {
        requireSubject(uid, username, accessVersion); Objects.requireNonNull(sid);
        // An absent row is already inactive. Never mutate an account version or another family.
        jdbc.update("""
                UPDATE auth.browser_session SET revoked_at=COALESCE(revoked_at,clock_timestamp())
                WHERE sid=? AND user_id=? AND username=? AND access_version=?
                """, sid, uid, username, accessVersion);
    }

    private static void requireSubject(long uid, String username, long accessVersion) {
        if (uid <= 0 || accessVersion < 0) throw new IllegalArgumentException("Invalid browser subject");
        UsernameRules.requireValid(username);
    }
}
