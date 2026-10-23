package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.mapper.DataSourceMapper;
import com.aetheris.metadata.service.impl.DataSourceServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Invoked only by audit-datasource-credentials.py against its disposable loopback container. */
class DataSourceCredentialsPostgresIT {
    @Test void actualUpgradeRuntimeCredentialsAndMapperNullClearing() throws Exception {
        String url=System.getenv("AETHERIS_DS_AUDIT_URL"),adminPassword=System.getenv("AETHERIS_DS_AUDIT_ADMIN_PASSWORD");
        assertNotNull(url,"Use scripts/audit-datasource-credentials.py");URI endpoint=URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1",endpoint.getHost());assertTrue(endpoint.getPort()>0);assertEquals("/aetheris_ds_credentials_audit",endpoint.getPath());
        String readPassword=System.getenv("AETHERIS_DS_AUDIT_READ_PASSWORD"),writePassword=System.getenv("AETHERIS_DS_AUDIT_WRITE_PASSWORD");
        for(String secret:List.of(adminPassword,readPassword,writePassword))assertTrue(secret.matches("[a-zA-Z0-9_-]{24,}"));
        var secrets=new DataSourceSecretService(System.getenv("AETHERIS_DS_AUDIT_MASTER_KEY"));
        String location="filesystem:"+Path.of("src/main/resources/db/migration").toAbsolutePath();
        var before=Flyway.configure().dataSource(url,"audit_admin",adminPassword).schemas("metadata").locations(location).target("27").load();
        assertTrue(before.migrate().migrationsExecuted>0);
        try(var admin=DriverManager.getConnection(url,"audit_admin",adminPassword)){
            try(var insert=admin.prepareStatement("INSERT INTO metadata.data_source(name,type,host,port,database_name,username,password) VALUES('legacy','postgresql','127.0.0.1',?,'aetheris_ds_credentials_audit','audit_reader',?)")){
                insert.setInt(1,endpoint.getPort());insert.setString(2,secrets.encrypt(readPassword));assertEquals(1,insert.executeUpdate());
            }
        }
        var flyway=Flyway.configure().dataSource(url,"audit_admin",adminPassword).schemas("metadata").locations(location).load();
        assertEquals(1,flyway.migrate().migrationsExecuted);assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        try(var admin=DriverManager.getConnection(url,"audit_admin",adminPassword);var sql=admin.createStatement()){
            try(var legacy=sql.executeQuery("SELECT username,password,write_username,write_password FROM metadata.data_source WHERE name='legacy'")){
                assertTrue(legacy.next());assertEquals("audit_reader",legacy.getString(1));assertEquals(readPassword,secrets.decrypt(legacy.getString(2)));assertNull(legacy.getString(3));assertNull(legacy.getString(4));
            }
            sql.execute("CREATE SCHEMA business; REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            sql.execute("CREATE TABLE business.input(id INT PRIMARY KEY); INSERT INTO business.input VALUES(10)");
            sql.execute("CREATE TABLE business.target(id INT PRIMARY KEY,read_actor TEXT,write_actor TEXT DEFAULT CURRENT_USER)");
            sql.execute("CREATE ROLE audit_reader LOGIN PASSWORD '"+readPassword+"'");sql.execute("CREATE ROLE audit_writer LOGIN PASSWORD '"+writePassword+"'");
            sql.execute("GRANT USAGE ON SCHEMA business TO audit_reader,audit_writer; GRANT SELECT ON ALL TABLES IN SCHEMA business TO audit_reader");
            sql.execute("GRANT SELECT,INSERT,DELETE ON business.target TO audit_writer");
            var ds=new DataSource();ds.setType("postgresql");ds.setName("separated");ds.setHost("127.0.0.1");ds.setPort(endpoint.getPort());ds.setDatabaseName("aetheris_ds_credentials_audit");
            ds.setUsername("audit_reader");ds.setPassword(secrets.encrypt(readPassword));ds.setWriteUsername("audit_writer");ds.setWritePassword(secrets.encrypt(writePassword));
            var jdbc=new JdbcService(secrets,false,new OutboundTargetPolicy());
            assertEquals("audit_reader",((List<?>)((List<?>)jdbc.query(ds,"SELECT current_user AS actor").get("rows")).getFirst()).getFirst());
            assertNotNull(jdbc.probe(ds,5));assertTrue(jdbc.listSchemas(ds).stream().anyMatch(s->s.name().equals("business")));
            assertFalse(jdbc.listTables(ds,"business").isEmpty());assertFalse(jdbc.listColumns(ds,"business","input").isEmpty());assertEquals(1,jdbc.preview(ds,"business","input").get("rowCount"));
            assertEquals(true,jdbc.execute(ds,"INSERT INTO business.target(id,read_actor) VALUES(1,'execute')",5).get("success"));
            assertEquals(1L,jdbc.transfer(ds,ds,"SELECT id,current_user AS read_actor FROM business.input","business.target","append",10).get("rowsWritten"));
            try(var records=sql.executeQuery("SELECT id,read_actor,write_actor FROM business.target ORDER BY id")){
                assertTrue(records.next());assertEquals("audit_writer",records.getString(3));assertTrue(records.next());assertEquals(10,records.getInt(1));assertEquals("audit_reader",records.getString(2));assertEquals("audit_writer",records.getString(3));assertFalse(records.next());
            }
            for(String mode:List.of("missing","partial","same","case")){
                var bad=new DataSource();bad.setType(ds.getType());bad.setHost(ds.getHost());bad.setPort(ds.getPort());bad.setDatabaseName(ds.getDatabaseName());bad.setUsername(ds.getUsername());bad.setPassword(ds.getPassword());bad.setWriteUsername(mode.equals("same")?"audit_reader":mode.equals("case")?"AUDIT_READER":mode.equals("missing")?null:"audit_writer");bad.setWritePassword(mode.equals("partial")?null:ds.getWritePassword());
                assertThrows(IllegalArgumentException.class,()->jdbc.execute(bad,"INSERT INTO business.target(id) VALUES(99)",5));
                assertThrows(IllegalArgumentException.class,()->jdbc.transfer(ds,bad,"SELECT id FROM business.input","business.target","replace",10));
            }
            try(var count=sql.executeQuery("SELECT count(*) FROM business.target")){count.next();assertEquals(2,count.getInt(1));}
            try(var read=DriverManager.getConnection(url,"audit_reader",readPassword)){
                assertEquals("42501",assertThrows(SQLException.class,()->read.createStatement().execute("INSERT INTO business.target(id) VALUES(99)")).getSQLState());
            }
            try(var writer=DriverManager.getConnection(url,"audit_writer",writePassword)){
                assertEquals("42501",assertThrows(SQLException.class,()->writer.createStatement().executeQuery("SELECT * FROM metadata.data_source")).getSQLState());
            }
            var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(DataSourceMapper.class);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(new DriverManagerDataSource(url,"audit_admin",adminPassword));factory.setConfiguration(configuration);factory.afterPropertiesSet();
            try(var session=factory.getObject().openSession(true)){
                var mapper=session.getMapper(DataSourceMapper.class);var service=new DataSourceServiceImpl(secrets);ReflectionTestUtils.setField(service,"baseMapper",mapper);
                ds.setPassword(readPassword);ds.setWritePassword(writePassword);assertTrue(service.save(ds));assertTrue(secrets.isEncrypted(mapper.selectById(ds.getId()).getWritePassword()));
                var patch=new DataSource();patch.setId(ds.getId());assertTrue(service.updateById(patch));assertTrue(mapper.selectById(ds.getId()).isWriteConfigured());
                var heldBeforeRotation=mapper.selectById(ds.getId());String previousWriterCipher=heldBeforeRotation.getWritePassword();
                assertNotNull(heldBeforeRotation.getConnectionRevision());
                var rotate=new DataSource();rotate.setId(ds.getId());rotate.setWritePassword(writePassword+"-rotated");assertTrue(service.updateById(rotate));
                String rotatedCipher=mapper.selectById(ds.getId()).getWritePassword();assertNotEquals(previousWriterCipher,rotatedCipher);
                assertTrue(mapper.selectById(ds.getId()).getConnectionRevision()>heldBeforeRotation.getConnectionRevision(),"Writer-only rotation must advance the database revision");
                heldBeforeRotation.setDescription("ordinary edit from older complete entity");
                assertFalse(service.updateById(heldBeforeRotation),"A complete entity fetched before same-account password rotation must be rejected");
                assertEquals(rotatedCipher,mapper.selectById(ds.getId()).getWritePassword(),"Old cipher must not replace the rotated secret");
                var unversioned=new DataSource();unversioned.setId(ds.getId());unversioned.setWriteUsername("audit_writer");unversioned.setWritePassword(previousWriterCipher);
                assertThrows(IllegalArgumentException.class,()->service.updateById(unversioned),"An unversioned opaque old cipher cannot stand in for a new plaintext password");
                var rename=new DataSource();rename.setId(ds.getId());rename.setWriteUsername("new_writer");assertThrows(IllegalArgumentException.class,()->service.updateById(rename));assertEquals("audit_writer",mapper.selectById(ds.getId()).getWriteUsername());
                var staleSnapshot=mapper.selectById(ds.getId());
                var disable=new DataSource();disable.setId(ds.getId());disable.setDisableWriteCredentials(true);assertTrue(service.updateById(disable));
                var stored=mapper.selectById(ds.getId());assertNull(stored.getWriteUsername());assertNull(stored.getWritePassword());assertFalse(stored.isWriteConfigured());assertThrows(IllegalArgumentException.class,()->jdbc.execute(stored,"INSERT INTO business.target(id) VALUES(99)",5));
                assertTrue(stored.getConnectionRevision()>staleSnapshot.getConnectionRevision(),"Writer disable must also advance revision");
                staleSnapshot.setDescription("old complete entity fetched before disable");
                assertFalse(service.updateById(staleSnapshot),"A complete entity fetched before disable must not revive writer capability");
                assertNull(mapper.selectById(ds.getId()).getWritePassword());
                var staleService=new DataSourceServiceImpl(secrets){@Override public DataSource getById(java.io.Serializable id){return staleSnapshot;}};
                ReflectionTestUtils.setField(staleService,"baseMapper",mapper);var staleEdit=new DataSource();staleEdit.setId(ds.getId());staleEdit.setDescription("stale ordinary edit");
                assertFalse(staleService.updateById(staleEdit),"Old credential snapshot must not resurrect the disabled writer");
                assertNull(mapper.selectById(ds.getId()).getWritePassword());
            }
        }
    }
}
