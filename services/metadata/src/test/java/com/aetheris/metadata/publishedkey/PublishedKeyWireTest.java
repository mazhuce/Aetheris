package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static com.aetheris.metadata.publishedkey.PublishedKeyContract.*;

class PublishedKeyWireTest {
    static final String PATH="/internal/v2/published-api-assets/key-query",OP="metadata.published-key.query";
    @Test void fixedPeersValidateAllBindingsAndNeverSendUserOrRetiredHeaders()throws Exception{
        try(var f=new Peers()){
            var input=f.peers.redeem(f.ref);assertEquals("用户甲",input.owner().username());f.peers.authorize(f.ref,input,new Target("table",13,9L,"warehouse"));assertEquals(1,f.redeems.get());assertEquals(1,f.checks.get());
            for(String fault:List.of("binding","check","unsigned","tampered","reason")){f.fault=fault;assertThrows(Unavailable.class,()->f.peers.authorize(f.ref,input,new Target("table",13,9L,"warehouse")));}
            f.fault="denied";assertThrows(Rejected.class,()->f.peers.authorize(f.ref,input,new Target("table",13,9L,"warehouse")));
            f.fault="inactive";assertThrows(Rejected.class,()->f.peers.authorize(f.ref,input,new Target("table",13,9L,"warehouse")));
            f.fault="source-hash";assertThrows(Unavailable.class,()->f.peers.redeem(f.ref));
            f.fault="source-extra";assertThrows(Unavailable.class,()->f.peers.redeem(f.ref));
        }
    }
    @Test void droppedRedeemOrAuthorizationResponseIsNotAutomaticallyReissued()throws Exception{
        try(var f=new Peers()){
            f.fault="drop-source";assertThrows(Unavailable.class,()->f.peers.redeem(f.ref));assertEquals(1,f.redeems.get());
            f.fault="";var input=f.peers.redeem(f.ref);f.fault="drop-auth";assertThrows(Unavailable.class,()->f.peers.authorize(f.ref,input,new Target("table",13,null,null)));assertEquals(1,f.checks.get());
        }
    }
    MockMvc mvc(MetadataProofFixture keys,PublishedKeyQueryService service){Set<String> used=ConcurrentHashMap.newKeySet();return MockMvcBuilders.standaloneSetup(new PublishedKeyController(service))
            .setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter())
            .addFilters(keys.filter((c,n,e)->used.add(c+":"+n),(u,n,a)->{throw new AssertionError("USER forbidden");})).build();}
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder call(MetadataProofFixture keys,String caller,String raw,String user){byte[] body=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request=post(PATH).contentType("application/json").content(body).header(SignedHttpClient.REQUEST_PROOF,keys.request(caller,PATH,OP,body,user));if(user!=null)request.header(SignedHttpClient.USER_PROOF,user);return request;}
    @Test void onlyExactServingWorkloadCanExecuteAndEveryActualResponseIsSigned()throws Exception{
        var keys=new MetadataProofFixture();var service=mock(PublishedKeyQueryService.class);var mvc=mvc(keys,service);var ref=new Reference("7feb99a1-1801-46b1-9bba-d8254406de99","a".repeat(64),"b".repeat(64));var raw=new String(bytes(ref),java.nio.charset.StandardCharsets.UTF_8);
        when(service.query(ref)).thenReturn(Map.of("rowCount",1));var request=call(keys,"serving",raw,null);var built=request.buildRequest(new org.springframework.mock.web.MockServletContext());
        var verified=keys.verifier.verifyRequest(built.getHeader(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata","POST",PATH,BodyHash.sha256(bytes(ref)),OP,BodyHash.userProof(null)));
        var result=mvc.perform(request).andReturn();assertEquals(200,result.getResponse().getStatus());keys.verifier.verifyResponse(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("metadata","serving",verified.proof().nonce(),200,BodyHash.sha256(result.getResponse().getContentAsByteArray())));
        assertEquals(409,mvc.perform(request).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(call(keys,"workflow",raw,null)).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(call(keys,"serving",raw,keys.user("serving"))).andReturn().getResponse().getStatus());
        assertEquals(401,mvc.perform(post(PATH).content(bytes(ref)).header("X-Internal-Token","old")).andReturn().getResponse().getStatus());verify(service,times(1)).query(ref);
    }
    @Test void bodyCannotSupplyAnOwnerOrTargetAndDependencyFailuresStay503()throws Exception{
        var keys=new MetadataProofFixture();var service=mock(PublishedKeyQueryService.class);var mvc=mvc(keys,service);var ref=new Reference("7feb99a1-1801-46b1-9bba-d8254406de99","a".repeat(64),"b".repeat(64));String raw=new String(bytes(ref),java.nio.charset.StandardCharsets.UTF_8);
        for(String bad:List.of(raw+" {}",raw.substring(0,raw.length()-1)+",\"owner\":\"admin\"}",raw.substring(0,raw.length()-1)+",\"sql\":\"SELECT 1\"}")){
            var result=mvc.perform(call(keys,"serving",bad,null)).andReturn();assertEquals(400,result.getResponse().getStatus());assertNotNull(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF));}
        verifyNoInteractions(service);when(service.query(ref)).thenThrow(new Unavailable());assertEquals(503,mvc.perform(call(keys,"serving",raw,null)).andReturn().getResponse().getStatus());
        doThrow(new Rejected()).when(service).query(ref);assertEquals(403,mvc.perform(call(keys,"serving",raw,null)).andReturn().getResponse().getStatus());
    }
    static final class Peers implements AutoCloseable{
        final MetadataProofFixture keys=new MetadataProofFixture();final AtomicInteger redeems=new AtomicInteger(),checks=new AtomicInteger();final AtomicReference<Throwable> failure=new AtomicReference<>();
        final ExecutorService handlers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8));final HttpServer server;final SignedHttpClient source,auth;final PublishedKeyPeers peers;final Reference ref;final String canonical;volatile String fault="";
        Peers()throws Exception{
            var vector=PublishedKeyContractTest.vector();var n=(ObjectNode)JSON.readTree(vector.get("canonicalAdmission").asText());long now=keys.clock.millis();n.put("createdAtEpochMillis",now).put("expiresAtEpochMillis",now+30000);canonical=JSON.writeValueAsString(n);ref=new Reference(n.get("admissionId").asText(),sha(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)),n.get("parametersSha256").asText());
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setExecutor(handlers);server.createContext("/",exchange->{try{
                boolean redeem=exchange.getRequestURI().getPath().equals("/internal/v2/published-key-admissions/redeem");String path=exchange.getRequestURI().getPath(),receiver=redeem?"serving":"auth",op=redeem?"serving.published-key.redeem":"auth.published-key-query.authorize";
                assertTrue(redeem||path.equals("/internal/v2/permissions/published-key-query-authorize"));assertNull(exchange.getRequestURI().getRawQuery());
                for(String header:List.of(SignedHttpClient.USER_PROOF,"X-Internal-Token","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(header));
                byte[] raw=exchange.getRequestBody().readNBytes(16385);assertTrue(raw.length<=16384);var request=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest(receiver,"POST",path,BodyHash.sha256(raw),op,BodyHash.userProof(null)));assertEquals("metadata",request.caller());var body=parse(raw,16384);var result=JSON.createObjectNode();
                if(redeem){redeems.incrementAndGet();assertEquals(parse(bytes(ref),4096),body);result.put("canonicalAdmission",canonical).put("admissionSha256",ref.admissionSha256());if(fault.equals("source-hash"))result.put("admissionSha256","c".repeat(64));if(fault.equals("source-extra"))result.put("owner","admin");}
                else{checks.incrementAndGet();fields(body,"schemaVersion","binding","owner","target");assertEquals(7,number(body.get("owner"),"uid"));assertEquals("table",text(body.get("target"),"resourceType"));
                    result.set("binding",body.get("binding"));result.put("allowed",!fault.equals("denied"));if(fault.equals("denied"))result.put("reason","PERMISSION_DENIED");else result.putNull("reason");var list=result.putArray("checks");
                    list.add(JSON.valueToTree(Map.of("subject","owner","resourceType","data_service","resourceId",11,"action","execute","allowed",true)));list.add(JSON.valueToTree(Map.of("subject","owner","resourceType","table","resourceId",13,"action","query","allowed",!fault.equals("denied"))));
                    if(fault.equals("binding"))((ObjectNode)result.get("binding")).put("keyId",99);if(fault.equals("check"))list.remove(1);if(fault.equals("reason"))result.put("reason","unexpected");if(fault.equals("inactive")){result.put("allowed",false).put("reason","SUBJECT_INACTIVE");list.removeAll();}}
                if(fault.equals(redeem?"drop-source":"drop-auth"))return;byte[] response=bytes(result);long seconds=keys.clock.instant().getEpochSecond();
                if(!fault.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,keys.workload(receiver).sign(new WorkloadResponseProof(2,receiver,"metadata",request.proof().nonce(),200,BodyHash.sha256(response),seconds,seconds+30)));
                if(fault.equals("tampered"))response=bytes(Map.of());exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }catch(Throwable caught){failure.compareAndSet(null,caught);}finally{exchange.close();}});server.start();
            var origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());var limits=new SignedHttpClient.Limits(65536,65536,4,Duration.ofMillis(500),Duration.ofSeconds(2));
            source=new SignedHttpClient("metadata",keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("serving",origin),Map.of("serving",Set.of("serving.published-key.redeem")),limits);
            auth=new SignedHttpClient("metadata",keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("auth",origin),Map.of("auth",Set.of("auth.published-key-query.authorize")),limits);peers=new PublishedKeyPeers(source,auth,keys.clock);
        }
        @Override public void close()throws Exception{source.close();auth.close();int port=server.getAddress().getPort();server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));try(var socket=new Socket()){assertThrows(java.io.IOException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),500));}if(failure.get()!=null)throw new AssertionError("Peer fixture failed",failure.get());}
    }
}
