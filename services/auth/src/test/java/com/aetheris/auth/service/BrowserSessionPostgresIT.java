package com.aetheris.auth.service;

import com.aetheris.auth.entity.AuthUser;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit disposable PostgreSQL acceptance; the ordinary Maven unit suite does not run this class. */
class BrowserSessionPostgresIT {
    private JdbcTemplate jdbc;
    private BrowserSessionService first, second;
    private AuthUser user;
    private final List<Long> owned = new ArrayList<>();
    @BeforeEach void setup() {
        String url = System.getenv("AETHERIS_BROWSER_FAMILY_URL");
        String marker = System.getenv("AETHERIS_BROWSER_FAMILY_RUN");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_api_key_audit")
                || marker == null || !marker.matches("[a-f0-9]{32}") || !marker.equals(System.getProperty("aetheris.browser.family.audit")))
            throw new IllegalStateException("Requires owned loopback PostgreSQL and exact audit marker");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "aetheris_auth", System.getenv("AETHERIS_BROWSER_FAMILY_PASSWORD")));
        assertEquals("aetheris_auth", jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals("aetheris_api_key_audit", jdbc.queryForObject("SELECT current_database()", String.class));
        first = new BrowserSessionService(jdbc); second = new BrowserSessionService(jdbc);
        user = insert("family_" + UUID.randomUUID().toString().replace("-", ""));
    }
    private AuthUser insert(String name) {
        long uid = jdbc.queryForObject("""
                INSERT INTO auth.auth_user(username,password_hash,status,role,access_version,session_version)
                VALUES(?,'test-password-hash','active','viewer',8,3) RETURNING id
                """, Long.class, name); owned.add(uid);
        var u = new AuthUser(); u.setId(uid); u.setUsername(name); u.setPasswordHash("test-password-hash");
        u.setStatus("active"); u.setRole("viewer"); u.setAccessVersion(8L); u.setSessionVersion(3L); return u;
    }
    @AfterEach void cleanup() {
        for (Long uid : owned) jdbc.update("DELETE FROM auth.auth_user WHERE id=?", uid);
    }
    private BrowserSessionService.Session open() { return first.open(user, Duration.ofMinutes(5)).orElseThrow(); }
    private Optional<BrowserSessionService.Session> rotate(BrowserSessionService store, BrowserSessionService.Session s) {
        return store.rotate(s.sid(), s.uid(), s.username(), s.accessVersion(), s.refreshVersion());
    }
    private boolean active(BrowserSessionService store, BrowserSessionService.Session s) {
        return store.active(s.sid(), s.uid(), s.username(), s.accessVersion());
    }
    private void revoke(BrowserSessionService.Session s) { second.revoke(s.sid(), s.uid(), s.username(), s.accessVersion()); }

    @Test void twoInstancesConsumeOneRefreshVersionExactlyOnce() throws Exception {
        var session = open(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 8; n++) {
                BrowserSessionService store = n % 2 == 0 ? first : second;
                results.add(pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return rotate(store, session).isPresent(); }));
            }
            start.countDown(); int winners = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
        }
        assertTrue(active(second, session)); assertTrue(rotate(first, session).isEmpty());
        assertEquals(1L, jdbc.queryForObject("SELECT refresh_version FROM auth.browser_session WHERE sid=?", Long.class, session.sid()));
    }
    @Test void committedRefreshThenLogoutMakesEveryLateTokenInThatFamilyInactive() {
        var original = open(); var late = rotate(first, original).orElseThrow(); revoke(original);
        assertFalse(active(first, late)); assertFalse(active(new BrowserSessionService(jdbc), late));
        assertTrue(rotate(second, late).isEmpty());
        assertEquals(8L, jdbc.queryForObject("SELECT access_version FROM auth.auth_user WHERE id=?", Long.class, user.getId()));
        assertEquals(3L, jdbc.queryForObject("SELECT session_version FROM auth.auth_user WHERE id=?", Long.class, user.getId()));
    }
    @Test void committedLogoutThenRefreshNeverProducesNewCredential() {
        var original = open(); revoke(original); assertTrue(rotate(first, original).isEmpty()); assertFalse(active(first, original));
    }
    @Test void racingRefreshAndLogoutAlwaysEndsRevokedAcrossNewStoreInstance() throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            var original = open(); var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var refresh = pool.submit(() -> { start.await(); return rotate(first, original); });
                var logout = pool.submit(() -> { start.await(); revoke(original); return true; });
                start.countDown(); logout.get(10,TimeUnit.SECONDS); refresh.get(10,TimeUnit.SECONDS);
            }
            assertFalse(active(new BrowserSessionService(jdbc), original));
            assertTrue(first.rotate(original.sid(), original.uid(), original.username(), 8, 1).isEmpty());
        }
    }
    @Test void oldFamilyLogoutCannotRevokeANewerLoginOfTheSameAccount() {
        var old = open(); var current = open(); revoke(old);
        assertFalse(active(first, old)); assertTrue(active(second, current)); assertTrue(rotate(second, current).isPresent());
    }
    @Test void repeatLogoutKeepsSameRevocationAndNeverChangesAccountApprovalVersions() {
        var s = open(); revoke(s);
        var firstTime = jdbc.queryForObject("SELECT revoked_at FROM auth.browser_session WHERE sid=?", java.sql.Timestamp.class, s.sid());
        revoke(s);
        assertEquals(firstTime, jdbc.queryForObject("SELECT revoked_at FROM auth.browser_session WHERE sid=?", java.sql.Timestamp.class, s.sid()));
        assertEquals(List.of(8L,3L), jdbc.queryForObject("SELECT access_version,session_version FROM auth.auth_user WHERE id=?",
                (row, n) -> List.of(row.getLong(1),row.getLong(2)), user.getId()));
    }
    @Test void mismatchedStableSubjectOrFamilyCannotReadRotateOrRevokeAnotherFamily() {
        var s = open();
        assertFalse(first.active(s.sid(),s.uid()+1,s.username(),8)); assertFalse(first.active(s.sid(),s.uid(),"another",8));
        assertFalse(first.active(s.sid(),s.uid(),s.username(),9)); assertFalse(first.active(UUID.randomUUID(),s.uid(),s.username(),8));
        assertTrue(first.rotate(s.sid(),s.uid(),s.username(),9,0).isEmpty());
        second.revoke(s.sid(),s.uid()+1,s.username(),8); assertTrue(active(first,s));
    }
    @Test void disabledOrChangedAccountVersionsInvalidateEveryBrowserFamily() {
        var s = open(); jdbc.update("UPDATE auth.auth_user SET status='disabled' WHERE id=?",user.getId());
        assertFalse(active(second,s)); assertTrue(rotate(first,s).isEmpty());
        jdbc.update("UPDATE auth.auth_user SET status='active',access_version=9 WHERE id=?",user.getId());
        assertFalse(active(second,s)); assertTrue(rotate(first,s).isEmpty());
        jdbc.update("UPDATE auth.auth_user SET access_version=8,session_version=4 WHERE id=?",user.getId());
        assertFalse(active(second,s)); assertTrue(rotate(first,s).isEmpty());
    }
    @Test void familyExpiryRejectsAccessAndRefreshWithoutProcessMemory() {
        var s = open(); jdbc.update("UPDATE auth.browser_session SET created_at=clock_timestamp()-interval '10 seconds',expires_at=clock_timestamp()-interval '1 second' WHERE sid=?",s.sid());
        assertFalse(active(new BrowserSessionService(jdbc),s)); assertTrue(rotate(first,s).isEmpty());
    }
    @Test void loginCannotIssueFamilyAfterPasswordOrAuthoritySnapshotChanged() {
        jdbc.update("UPDATE auth.auth_user SET password_hash='new-hash',access_version=access_version+1,session_version=session_version+1 WHERE id=?",user.getId());
        assertTrue(first.open(user,Duration.ofMinutes(5)).isEmpty());
        assertEquals(0L,jdbc.queryForObject("SELECT count(*) FROM auth.browser_session WHERE user_id=?",Long.class,user.getId()));
    }
    @Test void sameNameRecreatedUidCannotInheritOldFamilyAndOldLogoutCannotRevokeNewOne() {
        var old = open(); jdbc.update("DELETE FROM auth.auth_user WHERE id=?",user.getId());
        user = insert(user.getUsername()); var current = open(); revoke(old);
        assertFalse(active(first,old)); assertTrue(active(second,current));
    }
    @Test void refreshCannotExtendTheAbsoluteFamilyHorizon() {
        var original = open(); var next = rotate(second,original).orElseThrow();
        assertEquals(original.expiresAt(),next.expiresAt()); assertEquals(original.sid(),next.sid());
        assertEquals(1,next.refreshVersion());
    }
}
