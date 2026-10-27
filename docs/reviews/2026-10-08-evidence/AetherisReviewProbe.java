import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.SqlWriteGuard;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.entity.ResourceGrant;
import com.aetheris.auth.mapper.*;
import com.aetheris.auth.model.DataScope;
import com.aetheris.auth.service.*;
import org.flywaydb.core.Flyway;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;

public class AetherisReviewProbe {
  public static void main(String[] args) throws Exception {
    JdbcService jdbc = new JdbcService();
    Method guard = JdbcService.class.getDeclaredMethod("validateReadOnlySql", String.class);
    guard.setAccessible(true);
    for (String sql : List.of("SELECT pg_sleep(1)", "SELECT\npg_sleep(1)",
        "EXPLAIN ANALYZE\nDELETE FROM review_probe", "SELECT 1 INTO OUTFILE '/tmp/review-not-executed'")) {
      try { guard.invoke(jdbc, sql); System.out.println("READ_GUARD ACCEPTED: " + sql.replace("\n", "\\n")); }
      catch (InvocationTargetException e) { System.out.println("READ_GUARD REJECTED: " + sql.replace("\n", "\\n")); }
    }
    for (String sql : List.of("INSERT INTO t SELECT pg_sleep(1)", "INSERT INTO t\nSELECT pg_sleep(1)")) {
      try { SqlWriteGuard.validate(sql); System.out.println("WRITE_GUARD ACCEPTED: " + sql.replace("\n", "\\n")); }
      catch (RuntimeException e) { System.out.println("WRITE_GUARD REJECTED: " + sql.replace("\n", "\\n")); }
    }
    // Execute only against a new process-local in-memory H2 database.
    String url = "jdbc:h2:mem:aetheris_review_only;DB_CLOSE_DELAY=-1";
    try (Connection c = DriverManager.getConnection(url, "sa", ""); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE review_probe(id INT)");
      s.execute("INSERT INTO review_probe VALUES (1),(2)");
      DataSource ds = new DataSource(); ds.setType("h2"); ds.setJdbcUrl(url); ds.setUsername("sa");
      jdbc.query(ds, "EXPLAIN ANALYZE\nDELETE FROM review_probe");
      try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM review_probe")) {
        r.next(); System.out.println("ACTUAL JdbcService.query ROWS_AFTER_DELETE=" + r.getInt(1));
      }
    }
    Method build = JdbcService.class.getDeclaredMethod("buildUrl", DataSource.class, String.class, int.class);
    build.setAccessible(true);
    DataSource ds = new DataSource(); ds.setType("h2");
    ds.setJdbcUrl("jdbc:h2:mem:aetheris_review_init;INIT=CREATE TABLE initialized_by_url(id INT)");
    try { build.invoke(jdbc, ds, "h2", 0); System.out.println("H2 INIT URL ACCEPTED"); }
    catch (InvocationTargetException e) { System.out.println("H2 INIT URL REJECTED"); }
    try {
      Flyway.configure().dataSource("jdbc:h2:mem:aetheris_review_flyway", "sa", "")
          .locations("filesystem:" + args[0] + "/services/serving/src/main/resources/db/migration")
          .load().info();
      System.out.println("FLYWAY_INFO SUCCEEDED");
    } catch (Exception e) { System.out.println("FLYWAY_INFO FAILED: " + e.getMessage()); }

    AuthUserService users = mock(AuthUserService.class);
    ResourceGrantMapper grants = mock(ResourceGrantMapper.class);
    UserGroupMapper groups = mock(UserGroupMapper.class);
    UserRoleMapper roles = mock(UserRoleMapper.class);
    RbacRoleMapper rbac = mock(RbacRoleMapper.class);
    AuthUser user = new AuthUser(); user.setId(1L); user.setUsername("review_viewer");
    user.setRole("viewer"); user.setStatus("active");
    when(users.findByUsername("review_viewer")).thenReturn(Optional.of(user));
    ResourceGrant grant = new ResourceGrant(); grant.setResourceType("table"); grant.setResourceId(10L);
    grant.setActions(List.of("query"));
    when(grants.selectEnabledFor("user", 1L)).thenReturn(List.of(grant));
    PermissionDecisionService permissions = new PermissionDecisionService(users, grants, groups, roles, rbac);
    System.out.println("BEFORE_REVOKE_OTHER_TABLE_ALLOWED=" + permissions.check("review_viewer", "table", 20L, "query", Map.of()).allowed());
    when(grants.selectEnabledFor("user", 1L)).thenReturn(List.of());
    System.out.println("AFTER_REVOKE_OTHER_TABLE_ALLOWED=" + permissions.check("review_viewer", "table", 20L, "query", Map.of()).allowed());
    DataScope scope = new DataScope(); scope.setKind("domain"); scope.setValues(List.of("ECOM"));
    grant.setResourceType("domain"); grant.setResourceId(null); grant.setDataScope(scope); grant.setActions(List.of("view"));
    when(grants.selectEnabledFor("user", 1L)).thenReturn(List.of(grant));
    System.out.println("DOMAIN_CODE_VISIBILITY=" + permissions.visibility("review_viewer", "domain", "view"));
    System.out.println("DOMAIN_CODE_SINGLE_CHECK=" + permissions.check("review_viewer", "domain", 30L, "view", Map.of("domainId", "30", "domainCode", "ECOM")).allowed());

    DataSource injected = new DataSource(); injected.setType("postgresql"); injected.setHost("127.0.0.1");
    injected.setDatabaseName("review?socketFactory=review.not.executed.Factory");
    System.out.println("GENERATED_URL_DANGEROUS_PARAMETER=" + build.invoke(jdbc, injected, "postgresql", 5432));

    // Metadata synchronization probe uses mocks only; no project database is accessed.
    JdbcService discovery = mock(JdbcService.class);
    com.aetheris.metadata.service.MetadataTableService tables = mock(com.aetheris.metadata.service.MetadataTableService.class);
    com.aetheris.metadata.entity.MetadataTable a = new com.aetheris.metadata.entity.MetadataTable();
    a.setId(10L); a.setDatasourceName("review"); a.setSchemaName("schema_a"); a.setTableName("table_a"); a.setSource("auto"); a.setStatus("active");
    com.aetheris.metadata.entity.MetadataTable b = new com.aetheris.metadata.entity.MetadataTable();
    b.setId(20L); b.setDatasourceName("review"); b.setSchemaName("schema_b"); b.setTableName("table_b"); b.setSource("auto"); b.setStatus("active");
    when(tables.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(a,b));
    when(discovery.listTables(any(DataSource.class), eq("schema_a"))).thenReturn(List.of(new com.aetheris.metadata.dto.DataSourceTableDto("table_a", "TABLE", null)));
    DataSource syncSource = new DataSource(); syncSource.setId(99L); syncSource.setName("review");
    new com.aetheris.metadata.service.MetadataSyncService(discovery, tables).sync(syncSource, "schema_a");
    System.out.println("SYNC_SCHEMA_A_CHANGED_UNSCANNED_SCHEMA_B_STATUS=" + b.getStatus());
  }
}
