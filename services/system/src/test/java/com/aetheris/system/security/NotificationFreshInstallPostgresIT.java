package com.aetheris.system.security;

import com.aetheris.system.dto.OperationsNotificationRequest;
import com.aetheris.system.service.OperationsInboxStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Run in a separate fresh fixture DB from the V7-upgrade IT; never accepts a pre-migrated schema. */
class NotificationFreshInstallPostgresIT {
    @Test void allEightMigrationsInstallFromEmptyAndEnforceNewBindings() {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL"),password=System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit")||password==null||password.length()<24)
            throw new IllegalStateException("Requires isolated loopback aetheris_audit PostgreSQL");
        var source=new DriverManagerDataSource(url,"audit_admin",password);var jdbc=new JdbcTemplate(source);
        assertEquals("aetheris_audit",jdbc.queryForObject("SELECT current_database()",String.class));
        assertNull(jdbc.queryForObject("SELECT to_regclass('system.flyway_schema_history')::text",String.class),"Fresh-install proof requires a fresh schema, not an upgrade test's leftover history");
        var flyway=Flyway.configure().dataSource(source).schemas("system").defaultSchema("system").locations("classpath:db/migration").load();
        flyway.migrate();assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        assertEquals(8L,Arrays.stream(flyway.info().applied()).filter(row->row.getVersion()!=null).count());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM system.notification",Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO system.notification(recipient,source,title) VALUES('admin','workflow','unbound')"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO system.notification(recipient,source,title,inbox_kind,recipient_uid) VALUES('alice','workflow','missing UID','personal',NULL)"));
        var store=new OperationsInboxStore(jdbc,new DataSourceTransactionManager(source));
        var event=new OperationsNotificationRequest(UUID.randomUUID(),null,"info","fresh-install event",null,null);
        long first=store.record("metadata",event);assertTrue(first>0);assertEquals(first,store.record("metadata",event));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM system.notification WHERE inbox_kind='operations' AND recipient='operations' AND recipient_uid IS NULL",Integer.class));
        long personal=jdbc.queryForObject("INSERT INTO system.notification(recipient,source,title,inbox_kind,recipient_uid) VALUES('alice','workflow','personal','personal',2) RETURNING id",Long.class);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE system.notification SET recipient_uid=3 WHERE id=?",personal));
    }
}
