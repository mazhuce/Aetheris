package com.aetheris.serving.service;

import com.aetheris.serving.security.*;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class PublishedKeyClientTest {
    @Test void realSignedMetadataRequestHasOnlyReferenceAndNeverUserOrSecretAndDoesNotRetryLostResponse()throws Exception {
        try(var f=new V2ServingFixture()) {
            var calls=new AtomicInteger();var status=new AtomicInteger(200);var fault=new AtomicReference<>("");var failure=new AtomicReference<Throwable>();
            var pool=Executors.newFixedThreadPool(2);var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
            server.createContext("/internal/v2/published-api-assets/key-query",exchange->{try{
                calls.incrementAndGet();byte[] raw=exchange.getRequestBody().readAllBytes();assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getQuery());
                for(String header:List.of(SignedHttpClient.USER_PROOF,"Cookie","Authorization","X-API-Key","X-Internal-Token"))assertNull(exchange.getRequestHeaders().getFirst(header));
                var verified=f.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata","POST",exchange.getRequestURI().getRawPath(),BodyHash.sha256(raw),"metadata.published-key.query",BodyHash.userProof(null)));assertEquals("serving",verified.caller());PublishedKeyAdmissionService.Reference.parse(raw);
                if(fault.get().equals("lost"))return;
                byte[] body=PublishedKeyJson.bytes(Map.of("rowCount",1));long now=f.clock.instant().getEpochSecond();String response=f.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(new WorkloadResponseProof(2,"metadata","serving",verified.proof().nonce(),status.get(),BodyHash.sha256(body),now,now+30));
                if(!fault.get().equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,response);exchange.sendResponseHeaders(status.get(),body.length);exchange.getResponseBody().write(body);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}});server.start();int port=server.getAddress().getPort();
            try(var transport=new SignedHttpClient("serving",f.servingSigner,f.verifier,f.clock,TimePolicy.defaults(),Map.of("metadata",URI.create("http://127.0.0.1:"+port)),Map.of("metadata",Set.of("metadata.published-key.query")),new SignedHttpClient.Limits(16384,1048576,2,Duration.ofMillis(200),Duration.ofMillis(800)))) {
                var client=new MetadataQueryClient(transport);var input=new PublishedKeyAdmissionService.Reference(UUID.randomUUID(),"a".repeat(64),"b".repeat(64));assertEquals(1,client.publishedKeyQuery(input).get("rowCount"));
                status.set(403);assertThrows(ServingSecurity.ForbiddenException.class,()->client.publishedKeyQuery(input));status.set(404);assertThrows(MetadataQueryClient.ApiAssetNotFoundException.class,()->client.publishedKeyQuery(input));
                for(int code:List.of(401,503)){status.set(code);assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.publishedKeyQuery(input)).getStatusCode().value());}
                status.set(200);fault.set("unsigned");assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.publishedKeyQuery(input)).getStatusCode().value());
                fault.set("lost");int before=calls.get();assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.publishedKeyQuery(input)).getStatusCode().value());assertEquals(before+1,calls.get());
            }finally{server.stop(0);pool.shutdownNow();assertTrue(pool.awaitTermination(3,TimeUnit.SECONDS));try(var socket=new Socket()){assertThrows(java.io.IOException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),300));}}
            if(failure.get()!=null)throw new AssertionError(failure.get());
        }
    }
}
