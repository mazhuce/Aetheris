package com.aetheris.quality.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import com.aetheris.quality.config.QualityV2SecurityConfiguration;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import java.net.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated loopback auth replacement and Ed25519; never a platform account/database. */
public final class V2QualityFixture implements AutoCloseable {
    public final Clock clock=Clock.systemUTC();
    public final Map<String,KeyPair> keys=new HashMap<>();
    public final KeyRegistry registry;public final ProtocolVerifier verifier;
    public final ProtocolSigner userSigner,authSigner,qualitySigner;
    public final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    public final SignedHttpClient transport;public final AuthProtocolClient auth;
    public final ResourceAuthorization authorization;
    public final AtomicBoolean active=new AtomicBoolean(true),allowed=new AtomicBoolean(true),manage=new AtomicBoolean(false),mutate=new AtomicBoolean(true);
    public volatile String fault="";public volatile boolean outsideBatch,permissionFaultOnly;
    public final List<Observed> requests=new CopyOnWriteArrayList<>();
    final AtomicReference<Throwable> failure=new AtomicReference<>();
    static final ObjectMapper JSON=new ObjectMapper();
    public record Observed(String operation,JsonNode body,String userProof) { }
    public V2QualityFixture() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");
        for(String name:List.of("user","auth","quality","workflow","system","metadata"))keys.put(name,generator.generateKeyPair());
        registry=new KeyRegistry(List.of(new KeyRegistry.Entry("user",KeyPurpose.USER,"gateway",keys.get("user").getPublic()),
                new KeyRegistry.Entry("auth",KeyPurpose.WORKLOAD,"auth",keys.get("auth").getPublic()),
                new KeyRegistry.Entry("quality",KeyPurpose.WORKLOAD,"quality",keys.get("quality").getPublic()),
                new KeyRegistry.Entry("metadata",KeyPurpose.WORKLOAD,"metadata",keys.get("metadata").getPublic()),new KeyRegistry.Entry("workflow",KeyPurpose.WORKLOAD,"workflow",keys.get("workflow").getPublic()),new KeyRegistry.Entry("system",KeyPurpose.WORKLOAD,"system",keys.get("system").getPublic())));
        verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
        userSigner=signer("user",KeyPurpose.USER,"gateway");authSigner=signer("auth",KeyPurpose.WORKLOAD,"auth");qualitySigner=signer("quality",KeyPurpose.WORKLOAD,"quality");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.createContext("/",exchange->{
            try {
                byte[] raw=exchange.getRequestBody().readNBytes(262145);assertTrue(raw.length<=262144);
                String path=exchange.getRequestURI().getRawPath();assertNull(exchange.getRequestURI().getRawQuery());
                String operation=switch(path){case "/internal/v2/sessions/check"->"auth.session.check";case "/internal/v2/permissions/check"->"auth.permission.check";
                    case "/internal/v2/permissions/check-batch"->"auth.permission.batch";case "/internal/v2/permissions/may-manage"->"auth.permission.manage";
                    case "/internal/v2/permissions/may-mutate"->"auth.permission.mutate";default->throw new AssertionError("Unfixed auth route");};
                String user=exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF);
                var verified=verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest("auth",exchange.getRequestMethod(),path,BodyHash.sha256(raw),operation,BodyHash.userProof(user)));
                assertEquals("POST",exchange.getRequestMethod());assertEquals("quality",verified.caller());
                assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
                var body=JSON.readTree(raw);Object response;
                if(operation.equals("auth.session.check")) {
                    assertNull(user);response=Map.of("active",active.get()&&body.path("uid").asLong()==42&&body.path("username").asText().equals("alice")&&body.path("accessVersion").asLong()==3);
                } else {
                    assertNotNull(user);assertEquals("alice",verifier.verifyUser(user,"quality").proof().username());
                    assertFalse(body.has("username"));assertFalse(body.has("uid"));assertFalse(body.has("role"));
                    if(operation.equals("auth.permission.batch")) {
                        boolean requested=false;for(var item:body.path("items"))if(item.path("resourceId").asLong()==8)requested=true;
                        response=Map.of("allowedIds",outsideBatch?List.of(999L):allowed.get()&&requested?List.of(8L):List.of());
                    }
                    else {
                        boolean result=switch(operation){case "auth.permission.manage"->manage.get();case "auth.permission.mutate"->{assertEquals("quality",body.path("service").asText());yield mutate.get();}
                            default->allowed.get()&&(!body.hasNonNull("resourceId")||body.get("resourceId").asLong()==8);};
                        Map<String,Object> decision=new LinkedHashMap<>();decision.put("allowed",result);decision.put("reason",result?null:"revoked");response=decision;
                    }
                }
                requests.add(new Observed(operation,body,user));byte[] bytes=JSON.writeValueAsBytes(response);
                String actualFault=permissionFaultOnly&&operation.equals("auth.session.check")?"":fault;
                int status=actualFault.equals("signed500")?500:200;long now=clock.instant().getEpochSecond();
                String proof=authSigner.sign(new WorkloadResponseProof(2,"auth",actualFault.equals("wrongCaller")?"workflow":"quality",verified.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                if(actualFault.equals("wrongPurpose"))proof=token();
                if(!actualFault.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof);
                if(actualFault.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
        });server.start();
        transport=new SignedHttpClient("quality",qualitySigner,verifier,clock,TimePolicy.defaults(),Map.of("auth",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),
                Map.of("auth",Set.of("auth.session.check","auth.permission.check","auth.permission.batch","auth.permission.manage","auth.permission.mutate")),
                new SignedHttpClient.Limits(262144,262144,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
        auth=new AuthProtocolClient(transport);authorization=new ResourceAuthorization(auth);
    }
    public ProtocolSigner signer(String name,KeyPurpose purpose,String caller){return new ProtocolSigner(name,purpose,caller,keys.get(name).getPrivate(),registry,clock,TimePolicy.defaults());}
    public String token(){return token("quality",42,3);}
    public String token(String audience,long uid,long av){long now=clock.instant().getEpochSecond();return userSigner.sign(new UserProof(2,"gateway",audience,uid,"alice",av,now,now+60,"quality-v2-test"));}
    public SessionValidator sessions(){return (uid,name,av)->auth.sessionActive(uid,name,av).get();}
    public DirectUserProofFilter direct(){return new DirectUserProofFilter("quality",verifier,sessions(),QualityV2SecurityConfiguration.directRoutes());}
    private final Set<String> nonces=ConcurrentHashMap.newKeySet();
    public WorkloadProofFilter workload(){return new WorkloadProofFilter("quality",verifier,qualitySigner,QualityV2SecurityConfiguration.workloadRoutes(),(c,n,e)->nonces.add(c+":"+n),sessions(),clock,TimePolicy.defaults());}
    public void request(Consumer<jakarta.servlet.http.HttpServletRequest> action) throws Exception {
        var input=new MockHttpServletRequest("GET","/api/quality/policies");input.addHeader(SignedHttpClient.USER_PROOF,token());
        direct().doFilter(input,new MockHttpServletResponse(),(request,response)->{
            var r=(jakarta.servlet.http.HttpServletRequest)request;RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
            try{action.accept(r);}finally{RequestContextHolder.resetRequestAttributes();}
        });
    }
    @Override public void close() throws Exception {
        RequestContextHolder.resetRequestAttributes();transport.close();int port=server.getAddress().getPort();server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
        try(var probe=new java.net.Socket()){assertThrows(java.io.IOException.class,()->probe.connect(new InetSocketAddress("127.0.0.1",port),300));}
        if(failure.get()!=null)throw new AssertionError("Isolated auth replacement failed",failure.get());
    }
}
