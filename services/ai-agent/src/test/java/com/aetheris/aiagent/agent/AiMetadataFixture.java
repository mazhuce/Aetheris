package com.aetheris.aiagent.agent;

import com.aetheris.aiagent.security.*;
import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.config.AiV2SecurityConfiguration;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP metadata replacement, exact proof/body/route verification. No database/model provider. */
public final class AiMetadataFixture implements AutoCloseable {
    public final AiTestKeys keys;public final AgentToolClient client;public final SignedHttpClient transport;
    public final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    public volatile String fault="";public final List<Observed> requests=new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure=new AtomicReference<>();
    public record Observed(String path,String operation,JsonNode body,String userProof) { }
    public AiMetadataFixture() throws Exception {this(new AiTestKeys());}
    public AiMetadataFixture(AiTestKeys keys) throws Exception {
        this.keys=keys;var json=new ObjectMapper();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.createContext("/",exchange->{
            try {
                byte[] raw=exchange.getRequestBody().readNBytes(1024*1024+1);assertTrue(raw.length<=1024*1024);String path=exchange.getRequestURI().getRawPath();assertNull(exchange.getRequestURI().getRawQuery());
                String op=switch(path){case "/internal/v2/agent/tables/search"->"metadata.agent.search";case "/internal/v2/agent/tables/describe"->"metadata.agent.describe";
                    case "/internal/v2/agent/query"->"metadata.user.sql-query";case "/internal/v2/semantic/metrics/list"->"metadata.agent.metric-list";
                    case "/internal/v2/semantic/metrics/query"->"metadata.user.metric-query";default->throw new AssertionError("Unexpected metadata path");};
                String user=exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF);assertNotNull(user);
                assertNull(exchange.getRequestHeaders().getFirst("X-Aetheris-Identity"));assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                assertNull(exchange.getRequestHeaders().getFirst("Cookie"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                var request=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata",exchange.getRequestMethod(),path,BodyHash.sha256(raw),op,BodyHash.userProof(user)));
                assertEquals("ai-agent",request.caller());assertEquals("POST",exchange.getRequestMethod());var verifiedUser=keys.verifier.verifyUser(user,"ai-agent");
                var body=json.readTree(raw);assertFalse(body.has("username"));requests.add(new Observed(path,op,body,user));
                Object reply=op.equals("metadata.agent.search")||op.equals("metadata.agent.metric-list")?List.of(Map.of("id",8,"name","fixture")):Map.of("rowCount",1,"rows",List.of(List.of(7)),"owner",verifiedUser.proof().username());
                int status=fault.equals("denied")?403:fault.equals("signed500")?500:200;if(status!=200)reply=Map.of("error","backend refused");
                byte[] bytes=json.writeValueAsBytes(reply);long now=keys.clock.instant().getEpochSecond();
                String response=keys.metadata.sign(new WorkloadResponseProof(2,"metadata","ai-agent",fault.equals("nonce")?Nonce.random():request.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                if(!fault.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,response);
                if(fault.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
        });server.start();
        transport=new SignedHttpClient("ai-agent",keys.ai,keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("metadata",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),
                Map.of("metadata",AiV2SecurityConfiguration.METADATA_OPERATIONS),new SignedHttpClient.Limits(1024*1024,1024*1024,16,Duration.ofMillis(500),Duration.ofSeconds(3)));
        client=new AgentToolClient(transport,keys.verifier,keys.clock);
    }
    public void attach(String name) throws Exception {AiProofContext.attach(AiProofContext.snapshot(keys.authenticated(name)));AiRunContext.attach(new AiRunContext(AiBudgetLimits.defaults()));}
    @Override public void close() throws Exception {
        AiProofContext.clear();AiRunContext.clear();transport.close();server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
        if(failure.get()!=null)throw new AssertionError("Metadata replacement failed",failure.get());
    }
}
