package com.aetheris.aiagent.service;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Shared disposable fixture stages genuine V1/V2 legacy rows before V5, independent of test-class order. */
final class ChatPostgresFixture {
    private ChatPostgresFixture() { }
    static synchronized DriverManagerDataSource database() {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL"), password=System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_audit")||password==null||password.length()<24)
            throw new IllegalStateException("Requires isolated loopback aetheris_audit PostgreSQL");
        var source=new DriverManagerDataSource(url,"audit_admin",password);var jdbc=new JdbcTemplate(source);
        assertEquals("aetheris_audit",jdbc.queryForObject("SELECT current_database()",String.class));
        if(jdbc.queryForObject("SELECT to_regclass('ai.flyway_schema_history')::text",String.class)==null){
            flyway(source,"1").migrate();
            jdbc.update("INSERT INTO ai.chat_conversation(title) VALUES('uid-legacy-before-v2')");
            flyway(source,"2").migrate();
            assertEquals("admin",jdbc.queryForObject("SELECT owner_username FROM ai.chat_conversation WHERE title='uid-legacy-before-v2'",String.class));
            jdbc.update("INSERT INTO ai.chat_conversation(title,owner_username) VALUES('uid-legacy-before-v5','alice')");
        }
        var latest=flyway(source,null);latest.migrate();
        assertTrue(latest.validateWithResult().validationSuccessful);assertEquals(0,latest.info().pending().length);
        assertEquals(5L,java.util.Arrays.stream(latest.info().applied()).filter(m->m.getVersion()!=null).count());
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM ai.chat_conversation WHERE title IN ('uid-legacy-before-v2','uid-legacy-before-v5') AND owner_uid IS NULL",Integer.class),
                "Real old rows, including V2's guessed admin, must survive without guessed UIDs");
        return source;
    }
    private static Flyway flyway(DriverManagerDataSource source,String target){
        var config=Flyway.configure().dataSource(source).schemas("ai").defaultSchema("ai").locations("classpath:db/migration");
        if(target!=null)config.target(target);return config.load();
    }
}
