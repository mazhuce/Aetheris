package com.aetheris.serving.service;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.serving.security.*;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class MetadataQueryClientAuthorizationTest {
    @Test void preservesAuthorizationStatusForAllGovernedQueryMethods()throws Exception{
        for(int status:List.of(401,403))withResponse(status,"",client->{
            Class<? extends RuntimeException> expected=status==401?ServingSecurity.UnauthorizedException.class:ServingSecurity.ForbiddenException.class;
            assertThrows(expected,()->client.query(1L,"select 1",client.currentUserProof()));
            assertThrows(expected,()->client.queryMetric("orders",null,client.currentUserProof()));
            assertThrows(expected,()->client.queryTable(1L,null,client.currentUserProof()));
        });
    }
    @Test void dependencyFailureIsUnavailableAndDoesNotExposeResponseText()throws Exception{
        withResponse(503,"",client->{
            var failure=assertThrows(ResponseStatusException.class,()->client.queryTable(1L,null,client.currentUserProof()));
            assertEquals(503,failure.getStatusCode().value());assertFalse(failure.getMessage().contains("dependency"));
        });
    }
    @Test void signedQueriesPreserveOriginalProofAndFixedOperationBodies()throws Exception{
        withResponse(200,"",client->{
            assertEquals(1,client.query(1L,"select 1").get("rowCount"));
            assertEquals(1,client.queryTable(1L,Map.of("columns",List.of("id"),"limit",10)).get("rowCount"));
            assertEquals(1,client.queryMetric("orders",Map.of("limit",10)).get("rowCount"));
            assertThrows(ServingSecurity.UnauthorizedException.class,()->client.query(1L,"select 1","forged-user"));
        });
    }
    @Test void unsignedTamperedAndDroppedResponsesAreNeverConsumedOrReissued()throws Exception{
        for(String fault:List.of("unsigned","tampered","drop"))withResponse(200,fault,client->{
            assertEquals(503,assertThrows(ResponseStatusException.class,()->client.queryTable(1L,null)).getStatusCode().value());
        });
    }
    @Test void absentVerifiedContextAndLegacyConstructorMakeNoHttpRequest(){
        var legacy=new MetadataQueryClient(org.springframework.web.client.RestClient.builder(),"http://127.0.0.1:1","old-token");
        assertThrows(ServingSecurity.UnauthorizedException.class,()->legacy.query(1L,"select 1","forged"));
        assertThrows(ResponseStatusException.class,()->legacy.publishedApiAsset("orders"));
    }
    private static void withResponse(int status,String fault,Consumer<MetadataQueryClient> action)throws Exception{
        try(var fixture=new V2ServingFixture()){
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
            var failure=new AtomicReference<Throwable>();var count=new AtomicInteger();var json=new ObjectMapper();
            server.createContext("/",exchange->{
                try{
                    count.incrementAndGet();byte[] raw=exchange.getRequestBody().readAllBytes();String path=exchange.getRequestURI().getRawPath();
                    String operation=switch(path){case "/internal/v2/agent/query"->"metadata.user.sql-query";case "/internal/v2/tables/query"->"metadata.user.table-query";case "/internal/v2/semantic/metrics/query"->"metadata.user.metric-query";default->throw new AssertionError("Unfixed route");};
                    String user=exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF);
                    var request=fixture.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata","POST",path,BodyHash.sha256(raw),operation,BodyHash.userProof(user)));
                    assertEquals("serving",request.caller());assertEquals("alice",fixture.verifier.verifyUser(user,"serving").proof().username());
                    assertNull(exchange.getRequestURI().getRawQuery());
                    for(String header:List.of("X-Internal-Token","X-Aetheris-Identity","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(header));
                    var body=json.readTree(raw);assertFalse(body.has("username"));
                    if(operation.endsWith("sql-query")){assertEquals(Set.of("datasourceId","sql"),json.convertValue(body,Map.class).keySet());assertEquals("select 1",body.path("sql").asText());}
                    if(operation.endsWith("table-query")){assertEquals(1,body.path("tableId").asLong());assertTrue(body.path("query").isObject());}
                    if(operation.endsWith("metric-query")){assertEquals("orders",body.path("metricCode").asText());assertTrue(body.path("query").isObject());}
                    if(fault.equals("drop"))return;
                    byte[] bytes=json.writeValueAsBytes(status==200?Map.of("rowCount",1):Map.of("message","dependency message must not leak"));
                    long now=fixture.clock.instant().getEpochSecond();
                    String proof=fixture.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(new WorkloadResponseProof(2,"metadata","serving",request.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                    if(!fault.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof);
                    if(fault.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
            });server.start();
            try(var transport=new SignedHttpClient("serving",fixture.servingSigner,fixture.verifier,fixture.clock,TimePolicy.defaults(),
                    Map.of("metadata",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),Map.of("metadata",Set.of("metadata.user.sql-query","metadata.user.table-query","metadata.user.metric-query")),
                    new SignedHttpClient.Limits(262144,1048576,4,Duration.ofMillis(500),Duration.ofSeconds(2)))){
                fixture.request(request->action.accept(new MetadataQueryClient(transport)));
            }finally{
                server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
            }
            if(failure.get()!=null)throw new AssertionError("Metadata replacement failed",failure.get());
            if(!fault.isEmpty())assertEquals(1,count.get());
        }
    }
}