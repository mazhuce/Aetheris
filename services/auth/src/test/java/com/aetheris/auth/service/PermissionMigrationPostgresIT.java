package com.aetheris.auth.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit acceptance only; refuses every non-disposable database URL. */
class PermissionMigrationPostgresIT {
    @Test void catalogUpgradePreservesExplicitGrantsAndNeverGrantsEveryUser() {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit"))
            throw new IllegalStateException("Requires isolated loopback aetheris_audit PostgreSQL");
        var source=new DriverManagerDataSource(url,"audit_admin",System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertEquals("aetheris_audit",jdbc.queryForObject("SELECT current_database()",String.class));
        Flyway.configure().dataSource(source).schemas("auth").defaultSchema("auth")
                .locations("classpath:db/migration").target("7").load().migrate();
        jdbc.update("INSERT INTO auth.rbac_role(code,name) VALUES('audit_custom','audit custom')");
        jdbc.update("""
                INSERT INTO auth.rbac_role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM auth.rbac_role r CROSS JOIN auth.rbac_permission p
                WHERE r.code='audit_custom' AND p.code='datasource.query'
                """);
        jdbc.update("""
                INSERT INTO auth.resource_grant(grantee_type,grantee_id,resource_type,resource_id,actions,granted_by)
                VALUES('user',123456,'datasource',8,'["view","query"]'::jsonb,'audit')
                """);
        var grants=jdbc.queryForList("SELECT * FROM auth.resource_grant ORDER BY id");
        Long version=jdbc.queryForObject("SELECT version FROM auth.permission_version WHERE id=1",Long.class);
        Flyway.configure().dataSource(source).schemas("auth").defaultSchema("auth")
                .locations("classpath:db/migration").load().migrate();
        assertEquals(grants,jdbc.queryForList("SELECT * FROM auth.resource_grant ORDER BY id"));
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM auth.rbac_role_permission rp JOIN auth.rbac_role r ON r.id=rp.role_id
                WHERE r.code='audit_custom'
                """,Integer.class));
        assertEquals(0,jdbc.queryForObject("""
                SELECT count(*) FROM auth.rbac_role_permission rp JOIN auth.rbac_role r ON r.id=rp.role_id
                WHERE r.code='sys_viewer'
                """,Integer.class));
        for(String type:List.of("workflow_task","ingestion_task","quality_rule","governance_policy","data_standard"))
            assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM auth.rbac_permission WHERE resource_type=?",Integer.class,type));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM auth.rbac_permission WHERE code IN ('dataset.publish','dashboard.publish')",Integer.class));
        assertEquals(version+1,jdbc.queryForObject("SELECT version FROM auth.permission_version WHERE id=1",Long.class));
    }
}
