package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.VisibleListPage;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.mapper.BusinessDomainMapper;
import com.aetheris.metadata.mapper.MetricDefinitionMapper;
import com.aetheris.metadata.mapper.VisibleListMapper;
import com.aetheris.metadata.security.AuthPermissionClient;
import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.session.SqlSession;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicit disposable-Postgres integration test; ordinary Surefire does not select *PostgresIT.
 * Real migrations, SELECT-only runtime role, JDBC predicates/JSONB, and Ed25519 admission.
 * AuthPermissionClient is an explicit deterministic permission fixture, not a running auth app.
 * The parent runner owns the fresh database/container; this class cleans only its own rows/role.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VisibleListPostgresIT {
    private final ObjectMapper json=new ObjectMapper();
    private final MetadataProofFixture proofs=new MetadataProofFixture();
    private final String prefix="p03_"+UUID.randomUUID().toString().replace("-","").substring(0,10)+"_";
    private final List<Long> tableIds=new ArrayList<>(),metricIds=new ArrayList<>(),domainIds=new ArrayList<>();
    private final Set<Long> denied=new HashSet<>();
    private final List<Integer> batchSizes=new ArrayList<>();
    private final AtomicReference<AuthPermissionClient.Visibility> visibility=new AtomicReference<>(new AuthPermissionClient.Visibility("OPAQUE",List.of()));
    private JdbcTemplate admin,runtime;
    private SqlSession session;
    private VisibleListPager pager;
    private String readRole;
    private long sales,operations,visibleMetric,hiddenMetric,unassignedMetric;
    private String visibleCode,hiddenCode,unassignedCode;
    private ObjectNode visibleSnapshot;
    private final List<CandidateRead> candidateReads=new ArrayList<>();
    private record CandidateRead(String sql,List<Object> parameters,int rows) { }
    private final class RecordingJdbcTemplate extends JdbcTemplate {
        RecordingJdbcTemplate(javax.sql.DataSource source) { super(source); }
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {
            var result=super.query(sql,mapper,args);
            if(sql.startsWith("SELECT * FROM metadata.metadata_table")) candidateReads.add(new CandidateRead(sql,List.copyOf(Arrays.asList(args)),result.size()));
            return result;
        }
    }

    @BeforeAll void prepareFreshOwnedDatabase() throws Exception {
        String url=System.getenv("AETHERIS_TEST_POSTGRES_URL"),password=System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD");
        assertNotNull(url,"Disposable runner URL is required");assertNotNull(password,"Disposable runner password is required");
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/aetheris_audit"),"Only the explicit loopback audit DB is accepted");
        var endpoint=URI.create(url.substring(5));
        assertTrue(endpoint.getPort()>1024 && endpoint.getPort()<=65535 && endpoint.getPort()!=5432,"Use an isolated random loopback port");
        assertNull(endpoint.getQuery());assertNull(endpoint.getUserInfo());
        assertTrue(password.matches("[A-Za-z0-9_-]{24,}"),"Runner secret must be at least 24 safe characters");
        var adminSource=source(url,"audit_admin",password);
        admin=new JdbcTemplate(adminSource);admin.setQueryTimeout(10);
        assertEquals("aetheris_audit",admin.queryForObject("SELECT current_database()",String.class));
        assertEquals("audit_admin",admin.queryForObject("SELECT current_user",String.class));
        assertFalse(admin.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='metadata')",Boolean.class),
                "Refuse a pre-existing metadata schema instead of resetting an unknown database");
        var flyway=Flyway.configure().dataSource(adminSource).schemas("metadata").defaultSchema("metadata")
                .locations("classpath:db/migration").load();
        assertEquals(32,flyway.migrate().migrationsExecuted);
        assertEquals(32L,Arrays.stream(flyway.info().applied()).filter(migration->migration.getVersion()!=null).count());
        assertTrue(flyway.validateWithResult().validationSuccessful);assertEquals(0,flyway.info().pending().length);
        assertEquals(0,flyway.migrate().migrationsExecuted);

        sales=domain("SALES","销售");operations=domain("OPS","运营");
        tableIds.addAll(admin.queryForList("INSERT INTO metadata.metadata_table(datasource_name,table_name,business_domain_id,domain,layer,source) "
                +"SELECT 'fixture',?||'table_'||n,?,?,'ods','manual' FROM generate_series(0,1204) n RETURNING id",Long.class,prefix,sales,prefix+"销售"));
        table(prefix+"literal%_mark");table(prefix+"literalABmark");
        visibleCode=(prefix+"VISIBLE").toUpperCase(Locale.ROOT);
        hiddenCode=(prefix+"HIDDEN").toUpperCase(Locale.ROOT);
        unassignedCode=(prefix+"UNASSIGNED").toUpperCase(Locale.ROOT);
        visibleSnapshot=snapshot(visibleCode,"已发布 中文 %_",sales);
        visibleMetric=metric(visibleCode,operations,visibleSnapshot);
        hiddenMetric=metric(hiddenCode,sales,snapshot(hiddenCode,"不可见的已发布指标",operations));
        unassignedMetric=metric(unassignedCode,operations,snapshot(unassignedCode,"未归属发布指标",0));
        admin.update("INSERT INTO metadata.metric_definition_version(metric_definition_id,version_no,snapshot_json,published_by) VALUES(?,2,?::jsonb,'fixture')",
                visibleMetric,snapshot(visibleCode,"不得读取的第二版",operations).toString());
        assertEquals("jsonb",admin.queryForObject("SELECT pg_typeof(snapshot_json)::text FROM metadata.metric_definition_version WHERE metric_definition_id=? AND version_no=1",String.class,visibleMetric));

        String readPassword=Base64.getUrlEncoder().withoutPadding().encodeToString(new java.security.SecureRandom().generateSeed(32));
        String candidateRole="p03_reader_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        assertTrue(candidateRole.matches("p03_reader_[a-f0-9]{16}"));
        try {
            admin.execute("CREATE ROLE "+candidateRole+" LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE PASSWORD '"+readPassword+"'");
        } catch(RuntimeException ignored) { throw new AssertionError("Owned SELECT role creation failed (secret omitted)"); }
        readRole=candidateRole;
        admin.execute("GRANT USAGE ON SCHEMA metadata TO "+readRole);
        admin.execute("GRANT SELECT ON metadata.metadata_table,metadata.metric_definition,metadata.metric_definition_version,metadata.business_domain TO "+readRole);
        runtime=new RecordingJdbcTemplate(source(url,readRole,readPassword));runtime.setQueryTimeout(10);
        assertEquals(readRole,runtime.queryForObject("SELECT current_user",String.class));
        assertFalse(runtime.queryForObject("SELECT rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname=current_user",Boolean.class));
        assertThrows(DataAccessException.class,()->runtime.update("INSERT INTO metadata.metadata_table(datasource_name,table_name) VALUES('forbidden','forbidden')"));
        assertThrows(DataAccessException.class,()->runtime.queryForObject("SELECT count(*) FROM metadata.data_source",Long.class));

        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MetricDefinitionMapper.class);configuration.addMapper(BusinessDomainMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(Objects.requireNonNull(runtime.getDataSource()));factory.setConfiguration(configuration);factory.afterPropertiesSet();
        session=Objects.requireNonNull(factory.getObject()).openSession(true);
        var domains=new BusinessDomainService(session.getMapper(BusinessDomainMapper.class),runtime);
        var metrics=new MetricDefinitionService(session.getMapper(MetricDefinitionMapper.class),domains,
                mock(LogicalModelService.class),mock(SemanticMetricQueryService.class),runtime,json);
        var auth=mock(AuthPermissionClient.class);
        when(auth.visibilityStrict(eq("alice"),anyString(),eq("view"))).thenAnswer(call->visibility.get());
        when(auth.checkBatchStrict(eq("alice"),anyString(),eq("view"),anyMap())).thenAnswer(call->{
            Map<Long,Map<String,String>> contexts=call.getArgument(3);
            assertTrue(contexts.size()<=500);batchSizes.add(contexts.size());
            var allowed=new HashSet<Long>();
            for(var item:contexts.entrySet()) {
                String id=item.getValue().get("domainId");
                if(id!=null) {
                    assertTrue(id.equals(Long.toString(sales)) || id.equals(Long.toString(operations)));
                    assertEquals(id.equals(Long.toString(sales))?(prefix+"SALES").toUpperCase(Locale.ROOT):(prefix+"OPS").toUpperCase(Locale.ROOT),item.getValue().get("domainCode"));
                } else assertTrue(item.getValue().isEmpty());
                if(!denied.contains(item.getKey())) allowed.add(item.getKey());
            }
            return allowed;
        });
        pager=new VisibleListPager(new VisibleListMapper(runtime,json),metrics,new MetadataSecurity(auth),json);
    }

    private DriverManagerDataSource source(String url,String user,String password) {
        var result=new DriverManagerDataSource(url,user,password);var properties=new Properties();
        properties.setProperty("connectTimeout","3");properties.setProperty("socketTimeout","15");
        result.setConnectionProperties(properties);return result;
    }
    private long domain(String code,String name) {
        long id=admin.queryForObject("INSERT INTO metadata.business_domain(domain_code,domain_name) VALUES(?,?) RETURNING id",Long.class,
                (prefix+code).toUpperCase(Locale.ROOT),prefix+name);domainIds.add(id);return id;
    }
    private long table(String name) {
        long id=admin.queryForObject("INSERT INTO metadata.metadata_table(datasource_name,table_name,business_domain_id,domain,layer,source) VALUES('fixture',?,?,?,'ods','manual') RETURNING id",Long.class,
                name,sales,prefix+"销售");tableIds.add(id);return id;
    }
    private ObjectNode snapshot(String code,String name,long domain) {
        var value=json.createObjectNode().put("metricCode",code).put("metricName",name).put("businessDomainId",domain)
                .put("sourceTableId",tableIds.getFirst()).put("aggregation","COUNT");
        value.set("filters",json.createArrayNode());value.set("dimensionIds",json.createArrayNode());return value;
    }
    private long metric(String code,long draftDomain,ObjectNode published) {
        long id=admin.queryForObject("INSERT INTO metadata.metric_definition(metric_code,metric_name,business_domain_id,status,version,published_version) VALUES(?,?,?,'draft',2,1) RETURNING id",Long.class,
                code,prefix+"private-draft-name",draftDomain);metricIds.add(id);
        admin.update("INSERT INTO metadata.metric_definition_version(metric_definition_id,version_no,snapshot_json,published_by) VALUES(?,1,?::jsonb,'fixture')",id,published.toString());return id;
    }
    private <T> T verified(Function<HttpServletRequest,T> action) throws Exception {
        var request=new MockHttpServletRequest("GET","/api/metadata/tables/page");
        request.addHeader(SignedHttpClient.USER_PROOF,proofs.user("metadata"));
        var response=new MockHttpServletResponse();var result=new AtomicReference<T>();
        try {
            proofs.direct((uid,name,av)->uid==42 && name.equals("alice") && av==3).doFilter(request,response,(checked,out)->{
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((HttpServletRequest)checked));
                result.set(action.apply((HttpServletRequest)checked));
            });
            assertEquals(200,response.getStatus());assertNotNull(result.get());return result.get();
        } finally { RequestContextHolder.resetRequestAttributes(); }
    }
    private VisibleListPage<MetricDefinition> published(String keyword) throws Exception {
        return verified(request->pager.metrics(request,keyword,null,200,null,true));
    }
    @BeforeEach void resetPermissions() { denied.clear();batchSizes.clear();candidateReads.clear();visibility.set(new AuthPermissionClient.Visibility("OPAQUE",List.of())); }

    @Test void actualPostgresBoundedCursorConsumesOnlyReturnedPositionAndEscapesLiteralWildcards() throws Exception {
        var original=new ArrayList<>(tableIds);original.sort(Comparator.reverseOrder());
        denied.addAll(original.subList(0,500));
        var expected=new ArrayList<>(original.subList(500,original.size()));
        var filters=new VisibleListMapper.TableFilters(prefix,null,null,null,false,null);
        var first=verified(request->pager.tables(request,filters,200,null,false));
        assertTrue(first.items().isEmpty());assertTrue(first.hasMore());assertEquals(List.of(500),batchSizes);
        assertEquals(Long.toString(original.get(499)),first.nextCursor().split("\\.")[2]);
        long insertedAfterAnchor=table(prefix+"after_anchor");
        var collected=new ArrayList<Long>();String cursor=first.nextCursor();int requests=0;
        while(cursor!=null) {
            assertTrue(++requests<=20,"Cursor must make bounded progress");String next=cursor;
            var page=verified(request->pager.tables(request,filters,200,next,false));
            collected.addAll(page.items().stream().map(MetadataTable::getId).toList());
            if(requests==1) {
                assertEquals(expected.subList(0,200),page.items().stream().map(MetadataTable::getId).toList());
                assertEquals(Long.toString(expected.get(199)),page.nextCursor().split("\\.")[2]);
            }
            cursor=page.nextCursor();assertEquals(cursor!=null,page.hasMore());
        }
        assertEquals(expected,collected);assertEquals(collected.size(),new HashSet<>(collected).size());assertFalse(collected.contains(insertedAfterAnchor));
        assertTrue(batchSizes.stream().allMatch(size->size<=500));
        visibility.set(new AuthPermissionClient.Visibility("ALL",List.of()));
        var literal=verified(request->pager.tables(request,new VisibleListMapper.TableFilters(prefix+"literal%_",null,null,null,false,null),50,null,false));
        assertEquals(List.of(prefix+"literal%_mark"),literal.items().stream().map(MetadataTable::getTableName).toList());
    }

    @Test void actualJsonbActiveVersionControlsDomainNamesCodesAndDamagedSnapshotFailsClosed() throws Exception {
        visibility.set(new AuthPermissionClient.Visibility("IDS",List.of(sales)));
        var visible=published(prefix);
        assertEquals(List.of(visibleMetric),visible.items().stream().map(MetricDefinition::getId).toList());
        assertEquals("已发布 中文 %_",visible.items().getFirst().getMetricName());
        assertEquals(sales,visible.items().getFirst().getBusinessDomainId());assertEquals(prefix+"销售",visible.items().getFirst().getBusinessDomain());
        assertEquals(1,visible.items().getFirst().getVersion());assertEquals(1,visible.items().getFirst().getPublishedVersion());
        assertEquals(List.of(visibleMetric),published(visibleCode.toLowerCase(Locale.ROOT)).items().stream().map(MetricDefinition::getId).toList());
        assertEquals(List.of(visibleMetric),published("已发布 中文").items().stream().map(MetricDefinition::getId).toList());
        assertTrue(published("private-draft-name").items().isEmpty());assertTrue(published("不得读取的第二版").items().isEmpty());
        assertFalse(visible.items().stream().anyMatch(value->value.getId()==hiddenMetric));
        visibility.set(new AuthPermissionClient.Visibility("ALL",List.of()));
        var unassigned=published(unassignedCode).items();assertEquals(1,unassigned.size());assertEquals(unassignedMetric,unassigned.getFirst().getId());
        assertNull(unassigned.getFirst().getBusinessDomainId());assertNull(unassigned.getFirst().getBusinessDomain());
        var damaged=new ArrayList<ObjectNode>();
        var missingName=visibleSnapshot.deepCopy();missingName.remove("metricName");damaged.add(missingName);
        damaged.add(visibleSnapshot.deepCopy().put("metricName",123));damaged.add(visibleSnapshot.deepCopy().put("metricCode","OTHER"));
        damaged.add(visibleSnapshot.deepCopy().put("businessDomainId",-1));damaged.add(visibleSnapshot.deepCopy().put("businessDomainId","1"));
        try {
            for(var snapshot:damaged) {
                admin.update("UPDATE metadata.metric_definition_version SET snapshot_json=?::jsonb WHERE metric_definition_id=? AND version_no=1",snapshot.toString(),visibleMetric);
                assertThrows(VisibleListMapper.InvalidSnapshotException.class,()->published(visibleCode));
            }
        } finally {
            admin.update("UPDATE metadata.metric_definition_version SET snapshot_json=?::jsonb WHERE metric_definition_id=? AND version_no=1",visibleSnapshot.toString(),visibleMetric);
        }
        assertEquals(visibleCode,published(visibleCode).items().getFirst().getMetricCode());
    }

    @Test void legacyScopeBudgetsAreInActualSqlAndSearchContinuesPastDeniedCandidates() throws Exception {
        for(var scope:List.of(new AuthPermissionClient.Visibility("ALL",List.of()),
                new AuthPermissionClient.Visibility("IDS",List.of(sales)),new AuthPermissionClient.Visibility("OPAQUE",List.of()))) {
            visibility.set(scope);candidateReads.clear();batchSizes.clear();
            assertThrows(VisibleListPager.ListLimitException.class,()->verified(request->pager.completeTables(request,2,2)));
            assertEquals(1,candidateReads.size());var read=candidateReads.getFirst();
            assertTrue(read.sql().endsWith("ORDER BY id DESC LIMIT ?"));
            assertEquals(3,read.parameters().getLast());assertEquals(3,read.rows(),"Postgres must apply budget+1 before JDBC materializes candidates");
            if(scope.mode().equals("IDS")) {
                assertTrue(read.sql().contains("WHERE business_domain_id IN (?)"));assertEquals(sales,read.parameters().getFirst());
            }
            assertTrue(batchSizes.isEmpty(),"Candidate overflow cannot proceed into authorization or return partial data");
        }
        assertThrows(IllegalArgumentException.class,()->verified(request->pager.completeTables(request,0,2)));
        assertThrows(IllegalArgumentException.class,()->verified(request->pager.completeTables(request,100001,2)));

        long stale=table(prefix+"stale_search");admin.update("UPDATE metadata.metadata_table SET status='stale' WHERE id=?",stale);
        var ordered=tableIds.stream().filter(id->id!=stale).sorted(Comparator.reverseOrder()).toList();
        denied.addAll(ordered.subList(0,500));
        visibility.set(new AuthPermissionClient.Visibility("OPAQUE",List.of()));candidateReads.clear();batchSizes.clear();
        var found=verified(request->pager.search(request,prefix,5,1000));
        assertEquals(ordered.subList(500,505),found.stream().map(MetadataTable::getId).toList());assertFalse(found.stream().anyMatch(row->row.getId()==stale));
        assertEquals(List.of(500,500),batchSizes);
        assertEquals(List.of(500,501),candidateReads.stream().map(read->(Integer)read.parameters().getLast()).toList());
        assertTrue(candidateReads.stream().allMatch(read->read.sql().contains("AND status<>'stale'") && read.sql().endsWith("LIMIT ?")));
        denied.addAll(tableIds);
        assertThrows(VisibleListPager.ListLimitException.class,()->verified(request->pager.search(request,prefix,5,500)));
        assertTrue(verified(request->pager.search(request,prefix+"literal%_",5,500)).isEmpty(),"A denied exact match may be empty only after actual EOF");
        for(int limit:List.of(0,51)) assertThrows(IllegalArgumentException.class,()->verified(request->pager.search(request,prefix,limit,500)));
        assertThrows(IllegalArgumentException.class,()->verified(request->pager.search(request,"x".repeat(129),5,500)));
        visibility.set(new AuthPermissionClient.Visibility("ALL",List.of()));
        assertTrue(verified(request->pager.search(request,prefix+"stale_search",5,500)).isEmpty());
    }

    @AfterAll void cleanOnlyOwnedRowsAndRole() {
        RequestContextHolder.resetRequestAttributes();
        if(session!=null) session.close();
        if(admin==null) return;
        deleteOwn("metric_definition",metricIds);
        deleteOwn("metadata_table",tableIds);
        deleteOwn("business_domain",domainIds);
        if(readRole!=null) {
            admin.execute("REVOKE SELECT ON metadata.metadata_table,metadata.metric_definition,metadata.metric_definition_version,metadata.business_domain FROM "+readRole);
            admin.execute("REVOKE USAGE ON SCHEMA metadata FROM "+readRole);
            admin.execute("DROP ROLE "+readRole);
            assertEquals(0L,admin.queryForObject("SELECT count(*) FROM pg_roles WHERE rolname=?",Long.class,readRole));
        }
    }
    private void deleteOwn(String table,List<Long> ids) {
        assertTrue(Set.of("metric_definition","metadata_table","business_domain").contains(table));
        if(ids.isEmpty()) return;
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        admin.update("DELETE FROM metadata."+table+" WHERE id IN ("+marks+")",ids.toArray());
        assertEquals(0L,admin.queryForObject("SELECT count(*) FROM metadata."+table+" WHERE id IN ("+marks+")",Long.class,ids.toArray()));
    }
}
