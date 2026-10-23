package com.aetheris.auth.mapper;

import com.aetheris.auth.entity.ResourceGrant;
import com.aetheris.auth.model.DataScope;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Requires the disposable audit wrapper; no mocks or alternate result maps. */
class ResourceGrantMapperPostgresIT {
    static SqlSessionFactory sessions;
    static JdbcTemplate jdbc;
    static final long SUBJECT=876543210L;

    @BeforeAll static void connectDisposableRuntimeAccount() throws Exception {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aetheris_api_key_audit"))throw new IllegalStateException("Use isolated API-key-chain audit database");
        assertEquals("aetheris_auth",System.getenv("AETHERIS_TEST_POSTGRES_USER"));
        var source=new DriverManagerDataSource(url,"aetheris_auth",System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD"));
        jdbc=new JdbcTemplate(source);
        assertEquals("aetheris_api_key_audit",jdbc.queryForObject("SELECT current_database()",String.class));
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ResourceGrantMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);factory.afterPropertiesSet();sessions=factory.getObject();
    }
    @AfterEach void cleanup() {jdbc.update("DELETE FROM auth.resource_grant WHERE grantee_id=? AND granted_by='api-chain-mapper-fixture'",SUBJECT);}

    ResourceGrant grant(String resource,List<String> actions,DataScope scope,boolean enabled) {
        var value=new ResourceGrant();value.setGranteeType("user");value.setGranteeId(SUBJECT);value.setResourceType(resource);value.setResourceId(71L);
        value.setActions(actions);value.setDataScope(scope);value.setEnabled(enabled);value.setGrantedBy("api-chain-mapper-fixture");return value;
    }

    @Test void decisionQueryPreservesActualJsonbActionsAndDataScope() {
        try(var session=sessions.openSession(true)) {
            var mapper=session.getMapper(ResourceGrantMapper.class);
            var value=grant("table",List.of("view","query"),new DataScope("domain",List.of("5","6")),true);mapper.insert(value);
            assertEquals(List.of("view","query"),mapper.selectById(value.getId()).getActions(),"BaseMapper uses the entity's typed result map");
            var decoded=mapper.selectEnabledFor("user",SUBJECT);assertEquals(1,decoded.size());
            assertEquals(List.of("view","query"),decoded.getFirst().getActions(),"The real decision query must not lose JSONB actions");
            assertNotNull(decoded.getFirst().getDataScope());assertEquals("domain",decoded.getFirst().getDataScope().getKind());assertEquals(List.of("5","6"),decoded.getFirst().getDataScope().getValues());
            assertFalse(jdbc.queryForObject("SELECT jsonb_exists(data_scope,'empty') FROM auth.resource_grant WHERE id=?",Boolean.class,value.getId()),"Derived predicate must not be stored in JSONB");
        }
    }
    @Test void historicalDerivedEmptyFieldsRemainReadableWithoutIgnoringUnknownData() {
        for(boolean empty:List.of(false,true)) {
            jdbc.update("INSERT INTO auth.resource_grant(grantee_type,grantee_id,resource_type,resource_id,actions,data_scope,granted_by) VALUES('user',?,'table',71,'[\"query\"]'::jsonb,?::jsonb,'api-chain-mapper-fixture')",SUBJECT,"{\"kind\":\"domain\",\"values\":[\"5\"],\"empty\":"+empty+"}");
            try(var session=sessions.openSession(true)) {
                var decoded=session.getMapper(ResourceGrantMapper.class).selectEnabledFor("user",SUBJECT);
                assertEquals(1,decoded.size());assertEquals(List.of("query"),decoded.getFirst().getActions());
                assertEquals("domain",decoded.getFirst().getDataScope().getKind());assertEquals(List.of("5"),decoded.getFirst().getDataScope().getValues());
                assertFalse(decoded.getFirst().getDataScope().isEmpty(),"Derived old flag cannot override kind/values semantics");
            }
            cleanup();
        }
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,()->new com.fasterxml.jackson.databind.ObjectMapper().readValue("{\"kind\":\"domain\",\"values\":[\"5\"],\"otherUnknown\":true}",DataScope.class));
    }
    @Test void unscopedActionsAndEnabledFilterSurviveRealDatabaseRead() {
        try(var session=sessions.openSession(true)) {
            var mapper=session.getMapper(ResourceGrantMapper.class);
            mapper.insert(grant("data_service",List.of("execute"),null,true));mapper.insert(grant("table",List.of("query"),null,false));
            var decoded=mapper.selectEnabledFor("user",SUBJECT);assertEquals(1,decoded.size());
            assertEquals(List.of("execute"),decoded.getFirst().getActions());assertNull(decoded.getFirst().getDataScope());
        }
    }
}
