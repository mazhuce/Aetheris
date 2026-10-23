package com.aetheris.metadata.security;

import com.aetheris.metadata.controller.*;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.mapper.BusinessDomainMapper;
import com.aetheris.metadata.mapper.MetricDefinitionMapper;
import com.aetheris.metadata.mapper.VisibleListMapper;
import com.aetheris.metadata.service.*;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Existing API regressions, not tests of a proposed pagination implementation.
 * H2 executes the actual MyBatis LIMIT/domain predicates; auth is a loopback
 * HTTP peer with real Ed25519 request/response proofs. No external DB/service.
 */
class MetadataListVisibilityRegressionTest {
    private final MetadataProofFixture proof = new MetadataProofFixture();
    private final ObjectMapper json = new ObjectMapper();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AtomicReference<Throwable> peerFailure = new AtomicReference<>();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final AtomicReference<String> visibility = new AtomicReference<>("OPAQUE");
    private final AtomicLong allowedThrough = new AtomicLong(Long.MAX_VALUE);
    private final AtomicBoolean forgeAllowedId = new AtomicBoolean();
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    private HttpServer peer;
    private ExecutorService handlers;
    private SignedHttpClient transport;
    private Connection keeper;
    private SqlSession session;
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private MetadataTableController tableController;

    @BeforeEach void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:p03_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        keeper = dataSource.getConnection();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA metadata");
        jdbc.execute("""
            CREATE TABLE metadata.metadata_table (
              id BIGINT PRIMARY KEY, datasource_name VARCHAR, table_name VARCHAR, table_comment VARCHAR,
              business_domain_id BIGINT, domain VARCHAR, layer VARCHAR, source VARCHAR, status VARCHAR DEFAULT 'active')
            """);
        jdbc.batchUpdate("INSERT INTO metadata.metadata_table(id,datasource_name,table_name,business_domain_id,domain,layer,source) VALUES (?,'fixture',?,1,'销售','ods','manual')",
                java.util.stream.LongStream.rangeClosed(1,1201).mapToObj(id -> new Object[]{id,"table_"+id}).toList());
        jdbc.execute("""
            CREATE TABLE metadata.business_domain (
              id BIGINT PRIMARY KEY, domain_code VARCHAR, domain_name VARCHAR, owner VARCHAR,
              status VARCHAR, description VARCHAR, created_by VARCHAR,
              created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE)
            """);
        jdbc.execute("""
            CREATE TABLE metadata.metric_definition (
              id BIGINT PRIMARY KEY, metric_code VARCHAR, metric_name VARCHAR, business_domain VARCHAR,
              business_domain_id BIGINT, logical_model_id BIGINT, source_table_id BIGINT, layer VARCHAR,
              calibre_formula VARCHAR, aggregation VARCHAR, measure_column VARCHAR, filters_json VARCHAR,
              time_column VARCHAR, default_time_grain VARCHAR, dimension_ids_json VARCHAR, unit VARCHAR,
              owner VARCHAR, status VARCHAR, version INT, published_version INT, description VARCHAR,
              created_by VARCHAR, published_by VARCHAR, published_at TIMESTAMP WITH TIME ZONE,
              created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE)
            """);
        jdbc.execute("""
            CREATE TABLE metadata.metric_definition_version (
              id BIGINT PRIMARY KEY, metric_definition_id BIGINT, version_no INT, snapshot_json VARCHAR,
              published_by VARCHAR, published_at TIMESTAMP WITH TIME ZONE)
            """);
        jdbc.update("INSERT INTO metadata.business_domain(id,domain_code,domain_name,status) VALUES (1,'SALES','销售','active'),(2,'OPS','运营','active')");
        jdbc.batchUpdate("INSERT INTO metadata.metric_definition(id,metric_code,metric_name,business_domain_id,status,version,updated_at) VALUES (?,?,?,1,'draft',1,CURRENT_TIMESTAMP)",
                java.util.stream.LongStream.rangeClosed(1, 1001)
                        .mapToObj(id -> new Object[]{id, "METRIC_" + id, "指标 " + id}).toList());

        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(MetricDefinitionMapper.class);
        configuration.addMapper(BusinessDomainMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.afterPropertiesSet();
        session = Objects.requireNonNull(factory.getObject()).openSession(true);
        var domains = new BusinessDomainService(session.getMapper(BusinessDomainMapper.class), jdbc);
        var metricService = new MetricDefinitionService(session.getMapper(MetricDefinitionMapper.class), domains,
                mock(LogicalModelService.class), mock(SemanticMetricQueryService.class), jdbc, json);

        startAuthPeer();
        var security = new MetadataSecurity(new AuthPermissionClient(new AuthProtocolClient(transport), metrics));
        var tables = mock(MetadataTableService.class);
        tableController = new MetadataTableController(tables, mock(DataSourceService.class),
                mock(MetadataSyncService.class), mock(WarehouseMapService.class), domains, security);
        var metricController = new MetricDefinitionController(metricService, security, mock(SemanticAssetReviewService.class));
        var semanticController = new SemanticMetricController(metricService, security, mock(QueryAuditService.class));
        var pager = new VisibleListPager(new VisibleListMapper(jdbc,json),metricService,security,json);
        tableController.setVisiblePager(pager);
        metricController.setVisiblePager(pager);
        semanticController.setVisiblePager(pager);
        var context = new DomainPermissionContext(domains);
        tableController.setPermissionContext(context);
        metricController.setPermissionContext(context);
        semanticController.setPermissionContext(context);
        mvc = MockMvcBuilders.standaloneSetup(tableController, metricController, semanticController)
                .setControllerAdvice(new MetadataAccessExceptionHandler())
                .addFilters(proof.direct((uid, name, version) -> true)).build();
    }

    private void startAuthPeer() throws Exception {
        peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        peer.setExecutor(handlers);
        peer.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getRawPath();
                String operation = switch (path) {
                    case "/internal/v2/permissions/visibility" -> "auth.permission.visibility";
                    case "/internal/v2/permissions/check-batch" -> "auth.permission.batch";
                    default -> throw new AssertionError("Unexpected permission route");
                };
                byte[] body = exchange.getRequestBody().readAllBytes();
                String user = exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF);
                var request = proof.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest("auth", "POST", path, BodyHash.sha256(body), operation, BodyHash.userProof(user))).proof();
                assertEquals("metadata", request.caller());
                assertEquals(42L, proof.verifier.verifyUser(user, "metadata").proof().uid());
                var input = json.readTree(body);
                assertFalse(input.has("username"));
                byte[] response;
                int status = unavailable.get() ? 503 : 200;
                if (status == 503) response = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
                else if (path.endsWith("/visibility")) {
                    response = json.writeValueAsBytes(Map.of("mode", visibility.get(),
                            "ids", visibility.get().equals("IDS") ? List.of(1L) : List.of()));
                } else {
                    var allowed = new ArrayList<Long>();
                    batchSizes.add(input.get("items").size());
                    for (var item : input.get("items")) {
                        assertEquals("1", item.path("ctx").path("domainId").asText());
                        assertEquals("SALES", item.path("ctx").path("domainCode").asText());
                        if(item.get("resourceId").longValue()<=allowedThrough.get()) allowed.add(item.get("resourceId").longValue());
                    }
                    if(forgeAllowedId.get()) allowed.add(999999L);
                    response = json.writeValueAsBytes(Map.of("allowedIds", allowed));
                }
                long now = proof.clock.instant().getEpochSecond();
                exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,
                        proof.workload("auth").sign(new WorkloadResponseProof(2, "auth", "metadata", request.nonce(),
                                status, BodyHash.sha256(response), now, now + 30)));
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                peerFailure.compareAndSet(null, failure);
            } finally { exchange.close(); }
        });
        peer.start();
        transport = new SignedHttpClient("metadata", proof.workload("metadata"), proof.verifier, proof.clock, TimePolicy.defaults(),
                Map.of("auth", URI.create("http://127.0.0.1:" + peer.getAddress().getPort())),
                Map.of("auth", Set.of("auth.permission.visibility", "auth.permission.batch")),
                new SignedHttpClient.Limits(1024 * 1024, 1024 * 1024, 8, Duration.ofMillis(500), Duration.ofSeconds(2)));
    }

    @AfterEach void close() throws Exception {
        if (transport != null) transport.close();
        if (peer != null) peer.stop(0);
        if (handlers != null) { handlers.shutdownNow(); assertTrue(handlers.awaitTermination(3, TimeUnit.SECONDS)); }
        if (session != null) session.close();
        if (keeper != null) keeper.close();
        metrics.close();
        if (peerFailure.get() != null) throw new AssertionError("Signed auth fixture failed", peerFailure.get());
    }

    @Test void opaqueTableListDoesNotDisappearBeyondOneThousandCandidates() throws Exception {
        mvc.perform(get("/api/metadata/tables").header(SignedHttpClient.USER_PROOF, proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1201));
        assertFalse(batchSizes.isEmpty());
        assertTrue(batchSizes.stream().allMatch(size -> size <= 500));
    }

    @Test void opaqueMetricSecondPageReachesRowsBeyondFirstFiveHundred() throws Exception {
        mvc.perform(get("/api/metadata/metric-definitions").param("page", "2").param("size", "500")
                        .header(SignedHttpClient.USER_PROOF, proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(500));
    }

    @Test void signedAuthorizationOutageReturns503InsteadOfSuccessfulEmptyList() throws Exception {
        unavailable.set(true);
        mvc.perform(get("/api/metadata/tables").header(SignedHttpClient.USER_PROOF, proof.user("metadata")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
    }

    @Test void legacyArrayRejectsOverBudgetForEveryScopeBeforeReturningPartialRows() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(tableController,"compatMaxCandidates",2);
        org.springframework.test.util.ReflectionTestUtils.setField(tableController,"compatMaxRows",2);
        for(String mode:List.of("ALL","IDS","OPAQUE")) {
            visibility.set(mode);batchSizes.clear();
            mvc.perform(get("/api/metadata/tables").header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LIST_SCAN_LIMIT"));
            assertTrue(batchSizes.isEmpty(),"Candidate overflow must reject before batch authorization");
        }
        org.springframework.test.util.ReflectionTestUtils.setField(tableController,"compatMaxCandidates",0);
        assertThrows(IllegalArgumentException.class,tableController::validateListLimits);
        org.springframework.test.util.ReflectionTestUtils.setField(tableController,"compatMaxCandidates",100001);
        assertThrows(IllegalArgumentException.class,tableController::validateListLimits);
    }

    @Test void searchContinuesAfterDeniedFirstBatchAndUsesLiteralWildcardsAndFixedStaleExclusion() throws Exception {
        allowedThrough.set(701);
        mvc.perform(get("/api/metadata/tables/search").param("keyword","table_").param("limit","10")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$[0].id").value(701)).andExpect(jsonPath("$[9].id").value(692));
        assertEquals(List.of(500,500),batchSizes);
        visibility.set("ALL");
        jdbc.update("INSERT INTO metadata.metadata_table(id,datasource_name,table_name,business_domain_id,status) VALUES(1300,'fixture','literal%_only',1,'active'),(1301,'fixture','literalABonly',1,'active'),(1302,'fixture','stale_only',1,'stale')");
        mvc.perform(get("/api/metadata/tables/search").param("keyword","literal%_")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(1300));
        mvc.perform(get("/api/metadata/tables/search").param("keyword","stale_only")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void searchBudgetAndInvalidBoundsCannotMasqueradeAsSuccessfulEmptySearch() throws Exception {
        allowedThrough.set(0);
        org.springframework.test.util.ReflectionTestUtils.setField(tableController,"searchMaxCandidates",500);
        mvc.perform(get("/api/metadata/tables/search").param("keyword","table_")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("LIST_SCAN_LIMIT"));
        mvc.perform(get("/api/metadata/tables/search").param("keyword","table_1201")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        for(String limit:List.of("0","51")) mvc.perform(get("/api/metadata/tables/search").param("keyword","table_").param("limit",limit)
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/metadata/tables/search").param("keyword","x".repeat(129))
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isBadRequest());
        unavailable.set(true);
        mvc.perform(get("/api/metadata/tables/search").param("keyword"," ")
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/metadata/tables/search").param("keyword","table_")
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isServiceUnavailable());
    }

    @Test void cursorTraversesAllRowsWithoutSkippingFetchedButUnconsumedCandidates() throws Exception {
        var ids = new ArrayList<Long>();
        String cursor = null;int requests=0;
        do {
            assertTrue(++requests<=20,"cursor must terminate without repeating pages");
            var request=get("/api/metadata/tables/page").param("size","200")
                    .header(SignedHttpClient.USER_PROOF,proof.user("metadata"));
            if(cursor!=null) request.param("cursor",cursor);
            var page=json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertTrue(page.has("nextCursor"));
            page.get("items").forEach(row->ids.add(row.get("id").longValue()));
            cursor=page.get("hasMore").booleanValue()?page.get("nextCursor").textValue():null;
            if(ids.size()==200) jdbc.update("INSERT INTO metadata.metadata_table(id,datasource_name,table_name,business_domain_id) VALUES(1300,'fixture','new_after_anchor',1)");
        } while(cursor!=null);
        assertEquals(java.util.stream.LongStream.iterate(1201,id->id-1).limit(1201).boxed().toList(),ids);
        assertTrue(batchSizes.stream().allMatch(size->size<=500));
    }

    @Test void opaqueEmptyCandidateBudgetPageStillHasUsableContinuation() throws Exception {
        allowedThrough.set(701);
        var first=json.readTree(mvc.perform(get("/api/metadata/tables/page").param("size","200")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsByteArray());
        assertEquals(List.of(500),batchSizes);
        mvc.perform(get("/api/metadata/tables/page").param("size","200").param("cursor",first.get("nextCursor").textValue())
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(200))
                .andExpect(jsonPath("$.items[0].id").value(701)).andExpect(jsonPath("$.items[199].id").value(502));
    }

    @Test void metricCursorReachesPastFirstFiveHundredAndUsesLivePermissionsOnContinuation() throws Exception {
        String cursor=null,firstCursor=null;var ids=new ArrayList<Long>();int requests=0;
        do {
            assertTrue(++requests<=20,"cursor must terminate without repeating pages");
            var request=get("/api/metadata/metric-definitions/page").param("size","200")
                    .header(SignedHttpClient.USER_PROOF,proof.user("metadata"));
            if(cursor!=null) request.param("cursor",cursor);
            var page=json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            page.get("items").forEach(row->ids.add(row.get("id").longValue()));
            cursor=page.get("hasMore").booleanValue()?page.get("nextCursor").textValue():null;
            if(firstCursor==null) firstCursor=cursor;
        } while(cursor!=null);
        assertEquals(1001,ids.size());assertEquals(1001,new HashSet<>(ids).size());assertEquals(1L,ids.getLast());
        allowedThrough.set(0);
        assertNotNull(firstCursor);
        mvc.perform(get("/api/metadata/metric-definitions/page").param("size","200").param("cursor",firstCursor)
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test void pageRejectsChangedFilterMalformedCursorAndPermissionOutage() throws Exception {
        var first=json.readTree(mvc.perform(get("/api/metadata/tables/page").param("size","2")
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        mvc.perform(get("/api/metadata/tables/page").param("size","2").param("keyword","changed").param("cursor",first.get("nextCursor").textValue())
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/metadata/tables/page").param("cursor","v1.01.1.50.bad")
                .header(SignedHttpClient.USER_PROOF,proof.user("metadata"))).andExpect(status().isBadRequest());
        unavailable.set(true);
        mvc.perform(get("/api/metadata/tables/page").header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
    }

    @Test void pageRejectsAllowedIdsNotInRequestedBatch() throws Exception {
        forgeAllowedId.set(true);
        mvc.perform(get("/api/metadata/tables/page").header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test void catalogPagesCountAllVisibleRowsWithoutExposingTableDefinitions() throws Exception {
        long count=0;String cursor=null;int requests=0;
        do {
            assertTrue(++requests<=20,"catalog cursor must terminate without repeating pages");
            var request=get("/api/metadata/tables/catalog-page").param("size","200")
                    .header(SignedHttpClient.USER_PROOF,proof.user("metadata"));
            if(cursor!=null) request.param("cursor",cursor);
            var page=json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertFalse(page.has("items"));
            for(var group:page.get("groups")) {
                assertEquals(Set.of("domain","layer","count"),new HashSet<>(java.util.stream.StreamSupport.stream(
                        java.util.Spliterators.spliteratorUnknownSize(group.fieldNames(),0),false).toList()));
                count+=group.get("count").longValue();
            }
            cursor=page.get("hasMore").booleanValue()?page.get("nextCursor").textValue():null;
        } while(cursor!=null);
        assertEquals(1201,count);
    }

    @Test void publishedVisibilityUsesPublishedDomainAfterDraftMovesElsewhere() throws Exception {
        visibility.set("IDS");
        jdbc.update("INSERT INTO metadata.metric_definition(id,metric_code,metric_name,business_domain_id,status,version,published_version) VALUES (2000,'STABLE','新版草稿',2,'draft',2,1)");
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("metricCode", "STABLE"); snapshot.put("metricName", "已发布指标");
        snapshot.put("businessDomainId", 1); snapshot.put("sourceTableId", 9);
        snapshot.put("aggregation", "COUNT"); snapshot.set("filters", json.createArrayNode());
        snapshot.set("dimensionIds", json.createArrayNode());
        jdbc.update("INSERT INTO metadata.metric_definition_version(id,metric_definition_id,version_no,snapshot_json,published_by,published_at) VALUES (9000,2000,1,?,'approver',CURRENT_TIMESTAMP)", snapshot.toString());
        jdbc.update("INSERT INTO metadata.metric_definition(id,metric_code,metric_name,business_domain_id,status,version,published_version) VALUES (2001,'HIDDEN','移入销售域的草稿',1,'draft',2,1)");
        ObjectNode hidden = snapshot.deepCopy();
        hidden.put("metricCode", "HIDDEN"); hidden.put("metricName", "不可见的已发布指标"); hidden.put("businessDomainId", 2);
        jdbc.update("INSERT INTO metadata.metric_definition_version(id,metric_definition_id,version_no,snapshot_json,published_by,published_at) VALUES (9001,2001,1,?,'approver',CURRENT_TIMESTAMP)", hidden.toString());
        mvc.perform(get("/api/metadata/semantic/metrics").header(SignedHttpClient.USER_PROOF, proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(2000)).andExpect(jsonPath("$[0].businessDomainId").value(1))
                .andExpect(jsonPath("$[0].metricName").value("已发布指标"));
        mvc.perform(get("/api/metadata/semantic/metrics/page").param("keyword","已发布")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(2000)).andExpect(jsonPath("$.items[0].businessDomainId").value(1))
                .andExpect(jsonPath("$.items[0].businessDomain").value("销售"));
        mvc.perform(get("/api/metadata/metric-definitions/page").param("status","published")
                        .header(SignedHttpClient.USER_PROOF,proof.user("metadata")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(2000));
    }
}
