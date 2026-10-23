package com.aetheris.security.protocol.transport;

import com.aetheris.security.protocol.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class SignedHttpClientTest {
    static final String PATH="/internal/v2/query", OP="table.query";
    static final byte[] JSON="{\"value\":7}".getBytes(StandardCharsets.UTF_8);
    static final Clock CLOCK=Clock.systemUTC();
    static final TimePolicy POLICY=TimePolicy.defaults();
    static ProtocolSigner workflow,metadata;
    static ProtocolVerifier verifier;
    final List<HttpServer> servers=new ArrayList<>();
    final ExecutorService handlers=Executors.newVirtualThreadPerTaskExecutor();
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    final AtomicInteger requests=new AtomicInteger();
    final AtomicReference<String> userHeader=new AtomicReference<>();

    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");var w=generator.generateKeyPair();var m=generator.generateKeyPair();
        var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("workflow-1",KeyPurpose.WORKLOAD,"workflow",w.getPublic()),
                new KeyRegistry.Entry("metadata-1",KeyPurpose.WORKLOAD,"metadata",m.getPublic())));
        workflow=new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"workflow",w.getPrivate(),registry,CLOCK,POLICY);
        metadata=new ProtocolSigner("metadata-1",KeyPurpose.WORKLOAD,"metadata",m.getPrivate(),registry,CLOCK,POLICY);
        verifier=new ProtocolVerifier(registry,CLOCK,POLICY);
    }
    @AfterEach void shutdown() throws Exception {
        servers.forEach(server->server.stop(0));handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));
        if(serverFailure.get()!=null)throw new AssertionError("Local HTTP fixture failed",serverFailure.get());
    }
    URI server(HttpHandler handler) throws IOException {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        server.createContext("/",exchange->{try{handler.handle(exchange);}catch(IOException peerClosed){exchange.close();}catch(Throwable error){serverFailure.compareAndSet(null,error);exchange.close();}});
        server.start();servers.add(server);return URI.create("http://127.0.0.1:"+server.getAddress().getPort());
    }
    WorkloadRequestProof request(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();byte[] bytes=exchange.getRequestBody().readAllBytes();
        assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
        userHeader.set(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));
        return verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                new ExpectedRequest("metadata",exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),BodyHash.sha256(bytes),OP,
                        BodyHash.userProof(userHeader.get()))).proof();
    }
    String responseProof(WorkloadRequestProof request,int status,byte[] body,String mode) {
        long now=CLOCK.instant().getEpochSecond();String nonce=mode.equals("nonce")?Nonce.random():request.nonce();
        String receiver=mode.equals("receiver")?"workflow":"metadata";
        var proof=new WorkloadResponseProof(2,receiver,mode.equals("caller")?"quality":"workflow",nonce,
                mode.equals("status")?201:status,BodyHash.sha256(mode.equals("body")?new byte[]{42}:body),now,now+30);
        String token=(mode.equals("receiver")?workflow:metadata).sign(proof);
        if(mode.equals("key")){int i=token.lastIndexOf('.')+5;token=token.substring(0,i)+(token.charAt(i)=='A'?'B':'A')+token.substring(i+1);}
        return token;
    }
    URI responding(int status,String mode) throws IOException {
        return server(exchange->{var p=request(exchange);String token=responseProof(p,status,JSON,mode);
            if(!mode.equals("missing"))exchange.getResponseHeaders().add(SignedHttpClient.RESPONSE_PROOF,token);
            if(mode.equals("duplicate"))exchange.getResponseHeaders().add(SignedHttpClient.RESPONSE_PROOF,token);
            if(mode.equals("encoding"))exchange.getResponseHeaders().set("Content-Encoding","gzip");
            exchange.sendResponseHeaders(status,JSON.length);try(var out=exchange.getResponseBody()){out.write(JSON);}
        });
    }
    SignedHttpClient client(URI origin) {return client(origin,new SignedHttpClient.Limits(1024,1024,32,Duration.ofMillis(300),Duration.ofSeconds(3)));}
    SignedHttpClient client(URI origin,SignedHttpClient.Limits limits) {
        return new SignedHttpClient("workflow",workflow,verifier,CLOCK,POLICY,Map.of("metadata",origin),Map.of("metadata",Set.of(OP)),limits);
    }
    SignedHttpClient.Request call(){return new SignedHttpClient.Request("metadata","POST",PATH,OP,JSON);}

    @Test void realSignedRequestResponseAndImmutableBytes() throws Exception {
        byte[] input=JSON.clone();var request=new SignedHttpClient.Request("metadata","POST",PATH,OP,input,"explicit.user.proof");
        input[0]=0;request.body()[0]=0;
        try(var client=client(responding(200,"valid"))){
            var response=client.send(request);assertEquals(200,response.status());assertArrayEquals(JSON,response.body());
            response.body()[0]=0;assertEquals(7,response.readJson(new ObjectMapper(),Map.class).get("value"));
            assertEquals("metadata",response.verified().caller());assertEquals("explicit.user.proof",userHeader.get());
            assertFalse(request.toString().contains("explicit"));assertFalse(response.toString().contains("value"));
        }
        assertEquals(1,requests.get());
    }
    @Test void validSignedNonSuccessKeepsActualStatus() throws Exception {
        for(int status:List.of(401,403,409,500,503))try(var client=client(responding(status,"valid"))){assertEquals(status,client.send(call()).status());}
    }
    @Test void forwardedUserProofIsIncludedInSignedRequest() throws Exception {
        assertSignedUserBinding("exact.user.compact");
    }
    @Test void absenceOfUserProofIsAlsoSigned() throws Exception {
        assertSignedUserBinding(null);
    }
    void assertSignedUserBinding(String user) throws Exception {
        AtomicReference<String> hash=new AtomicReference<>();
        URI origin=server(exchange->{
            String token=exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF);
            hash.set(new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
                    .path("userProofSha256").asText());
            var proof=request(exchange);
            exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responseProof(proof,200,JSON,"valid"));
            exchange.sendResponseHeaders(200,JSON.length);
            try(var out=exchange.getResponseBody()){out.write(JSON);}
        });
        try(var client=client(origin)){
            assertEquals(200,client.send(new SignedHttpClient.Request("metadata","POST",PATH,OP,JSON,user)).status());
        }
        assertEquals(BodyHash.sha256(user==null?new byte[0]:user.getBytes(StandardCharsets.US_ASCII)),hash.get());
    }
    @TestFactory java.util.stream.Stream<DynamicTest> rejectsUntrustedResponseBeforeBusinessJson() {
        return java.util.stream.Stream.of("nonce","receiver","caller","status","body","key","missing","duplicate","encoding")
                .map(mode->DynamicTest.dynamicTest(mode,()->{try(var client=client(responding(200,mode))){
                    assertEquals(SignedHttpClient.Failure.UNTRUSTED_RESPONSE,assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());
                }}));
    }
    @Test void unsignedDependencyFailureDoesNotBecomeBusiness401() throws Exception {
        try(var client=client(responding(500,"missing"))){assertEquals(SignedHttpClient.Failure.UNTRUSTED_RESPONSE,
                assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());}
        try(var client=client(responding(500,"status"))){assertEquals(SignedHttpClient.Failure.UNTRUSTED_RESPONSE,
                assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());}
    }
    @Test void redirectIsNeverFollowedEvenWithValidSignature() throws Exception {
        AtomicInteger targetHits=new AtomicInteger();URI target=server(exchange->{targetHits.incrementAndGet();exchange.close();});
        URI origin=server(exchange->{var p=request(exchange);exchange.getResponseHeaders().set("Location",target+PATH);
            exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responseProof(p,307,JSON,"valid"));
            exchange.sendResponseHeaders(307,JSON.length);try(var out=exchange.getResponseBody()){out.write(JSON);}});
        try(var client=client(origin)){assertEquals(SignedHttpClient.Failure.UNTRUSTED_RESPONSE,assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());}
        assertEquals(0,targetHits.get());assertEquals(1,requests.get());
    }
    @Test void fixedOriginAclAndRequestBoundsRejectBeforeWire() throws Exception {
        URI origin=responding(200,"valid");Map<String,URI> origins=new HashMap<>(Map.of("metadata",origin));
        Set<String> ops=new HashSet<>(Set.of(OP));Map<String,Set<String>> acl=new HashMap<>(Map.of("metadata",ops));
        try(var client=new SignedHttpClient("workflow",workflow,verifier,CLOCK,POLICY,origins,acl,SignedHttpClient.Limits.defaults())) {
            origins.put("metadata",URI.create("http://127.0.0.1:1"));ops.clear();
            assertEquals(200,client.send(call()).status());
            for(var bad:List.of(new SignedHttpClient.Request("auth","POST",PATH,OP,JSON),new SignedHttpClient.Request("metadata","POST",PATH,"manage.all",JSON),
                    new SignedHttpClient.Request("metadata","POST","http://127.0.0.1/admin",OP,JSON),new SignedHttpClient.Request("metadata","POST",PATH+"?url=x",OP,JSON)))
                assertThrows(RuntimeException.class,()->client.sendAsync(bad));
        }
        try(var client=client(origin,new SignedHttpClient.Limits(4,100,1,Duration.ofMillis(100),Duration.ofMillis(300)))) {
            assertEquals(SignedHttpClient.Failure.TOO_LARGE,assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());
        }
        assertThrows(SignedHttpClient.TransportException.class,()->new SignedHttpClient.Request("metadata","POST",PATH,OP,JSON,"x\r\nCookie:session"));
        assertThrows(SignedHttpClient.TransportException.class,()->new SignedHttpClient.Request("metadata","POST",PATH,OP,JSON,"x".repeat(12289)));
        for(String suffix:List.of("/admin","?x=1","#fragment"))assertThrows(SignedHttpClient.TransportException.class,()->client(URI.create(origin+suffix)));
        assertEquals(1,requests.get());
    }
    @Test void chunkedInfiniteBodyStopsAtHardLimitWithoutReadingEverything() throws Exception {
        AtomicInteger written=new AtomicInteger();CountDownLatch stopped=new CountDownLatch(1);
        URI origin=server(exchange->{var p=request(exchange);exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responseProof(p,200,JSON,"valid"));
            exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){
                while(written.get()<1024*1024){out.write(new byte[256]);out.flush();written.addAndGet(256);Thread.sleep(2);}
            }catch(IOException closed){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{stopped.countDown();}});
        try(var client=client(origin,new SignedHttpClient.Limits(1024,512,1,Duration.ofMillis(100),Duration.ofSeconds(2)))){
            assertEquals(SignedHttpClient.Failure.TOO_LARGE,assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());
        }
        assertTrue(stopped.await(2,TimeUnit.SECONDS));assertTrue(written.get()<1024*1024);
    }
    @Test void declaredOversizeIsRefusedBeforeBody() throws Exception {
        URI origin=server(exchange->{var p=request(exchange);exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responseProof(p,200,JSON,"valid"));
            exchange.sendResponseHeaders(200,1000000);exchange.close();});
        try(var client=client(origin)){assertEquals(SignedHttpClient.Failure.TOO_LARGE,assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());}
    }
    @Test void deadlineIncludesUnfinishedStreamingBody() throws Exception {
        URI origin=server(exchange->{var p=request(exchange);exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responseProof(p,200,JSON,"valid"));
            exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){
                while(true){out.write(' ');out.flush();Thread.sleep(30);}
            }catch(IOException closed){}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        try(var client=client(origin,new SignedHttpClient.Limits(1024,1024,1,Duration.ofMillis(100),Duration.ofMillis(250)))){
            long start=System.nanoTime();assertEquals(SignedHttpClient.Failure.DEADLINE,assertThrows(SignedHttpClient.TransportException.class,()->client.send(call())).failure());
            assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(2))<0);
        }
    }
    @Test void cancellationConcurrencyLimitAndCloseReleaseOwnedResources() throws Exception {
        CountDownLatch arrived=new CountDownLatch(1),release=new CountDownLatch(1);
        URI origin=server(exchange->{request(exchange);arrived.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
        var client=client(origin,new SignedHttpClient.Limits(1024,1024,1,Duration.ofMillis(100),Duration.ofSeconds(3)));
        var first=client.sendAsync(call());assertTrue(arrived.await(2,TimeUnit.SECONDS));
        assertEquals(SignedHttpClient.Failure.BUSY,assertThrows(SignedHttpClient.TransportException.class,()->client.sendAsync(call())).failure());
        assertTrue(first.cancel(true));assertTrue(first.isCancelled());
        var second=client.sendAsync(call());client.close();client.close();release.countDown();assertTrue(second.isCancelled());assertTrue(client.executorsTerminated());
        assertEquals(SignedHttpClient.Failure.CLOSED,assertThrows(SignedHttpClient.TransportException.class,()->client.sendAsync(call())).failure());
        awaitNoOwnedThreads();
    }
    @Test void concurrentCallsHaveIndependentNonceAndVerificationState() throws Exception {
        Set<String> nonces=ConcurrentHashMap.newKeySet();
        URI origin=server(exchange->{var p=request(exchange);assertTrue(nonces.add(p.nonce()));
            exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responseProof(p,200,JSON,"valid"));exchange.sendResponseHeaders(200,JSON.length);
            try(var out=exchange.getResponseBody()){out.write(JSON);}});
        try(var client=client(origin)){
            var calls=new ArrayList<CompletableFuture<SignedHttpClient.Response>>();for(int i=0;i<24;i++)calls.add(client.sendAsync(call()));
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(5,TimeUnit.SECONDS);
            for(var future:calls)assertArrayEquals(JSON,future.join().body());
        }
        assertEquals(24,nonces.size());awaitNoOwnedThreads();
    }
    @Test void peerDisconnectDoesNotReplayGetOrPostOnFreshConnections() throws Exception {
        AtomicInteger hits=new AtomicInteger();
        try(ServerSocket socket=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))){
            socket.setSoTimeout(200);AtomicBoolean stop=new AtomicBoolean();
            Future<?> acceptor=handlers.submit(()->{while(!stop.get())try(var connection=socket.accept()){
                connection.setSoTimeout(1000);var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));
                String line;while((line=input.readLine())!=null&&!line.isEmpty()){}hits.incrementAndGet();
            }catch(SocketTimeoutException ignored){}catch(IOException closed){break;}});
            try(var client=client(URI.create("http://127.0.0.1:"+socket.getLocalPort()))){
                for(String method:List.of("GET","POST"))assertThrows(SignedHttpClient.TransportException.class,
                        ()->client.send(new SignedHttpClient.Request("metadata",method,PATH,OP,new byte[0])));
            }
            stop.set(true);acceptor.get(2,TimeUnit.SECONDS);assertEquals(2,hits.get(),"Exactly one HTTP request per explicit call");
        }
    }
    @Test void shutdownCancelsActiveExchangeAndReleasesSelector() throws Exception {
        Set<String> prior=selectorThreads();CountDownLatch arrived=new CountDownLatch(1),release=new CountDownLatch(1);
        URI origin=server(exchange->{request(exchange);arrived.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}exchange.close();});
        var client=client(origin);var future=client.sendAsync(call());assertTrue(arrived.await(2,TimeUnit.SECONDS));client.close();release.countDown();assertTrue(future.isCancelled());
        assertTrue(client.executorsTerminated(),"Includes actual virtual-thread executor termination, not only platform thread enumeration");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){var current=selectorThreads();current.removeAll(prior);if(current.isEmpty())break;Thread.sleep(20);}
        var remaining=selectorThreads();remaining.removeAll(prior);assertTrue(remaining.isEmpty(),remaining.toString());awaitNoOwnedThreads();
    }
    @Test void latePropertyMutationCannotReplaceJvmStartupFlag() throws Exception {
        String javaExecutable=java.nio.file.Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var child=new ProcessBuilder(javaExecutable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),StartupProbe.class.getName()).redirectErrorStream(true).start();
        try {assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8));}
        finally {if(child.isAlive())child.destroyForcibly().waitFor();}
    }
    @Test void runtimeRetryPropertyChangesFailClosedIncludingEmptyEnableFlag() throws Exception {
        for(var setting:List.of(Map.entry("jdk.httpclient.disableRetryConnect","false"),Map.entry("jdk.httpclient.redirects.retrylimit","2"),
                Map.entry("jdk.httpclient.enableAllMethodRetry","true"),Map.entry("jdk.httpclient.enableAllMethodRetry",""))){
            String old=System.getProperty(setting.getKey());
            try{System.setProperty(setting.getKey(),setting.getValue());assertThrows(SignedHttpClient.TransportException.class,()->client(URI.create("http://127.0.0.1:1")));}
            finally{if(old==null)System.clearProperty(setting.getKey());else System.setProperty(setting.getKey(),old);}
        }
    }
    @Test void completedExchangesReleaseSelectorsBeforeTransportClose() throws Exception {
        var prior=selectorThreads();
        try(var client=client(responding(200,"valid"))){
            for(int i=0;i<4;i++)assertEquals(200,client.send(call()).status());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<end){var current=selectorThreads();current.removeAll(prior);if(current.isEmpty())break;Thread.sleep(20);}
            var remaining=selectorThreads();remaining.removeAll(prior);assertTrue(remaining.isEmpty(),remaining.toString());
        }
    }
    public static final class StartupProbe {
        public static void main(String[] args) {
            System.setProperty("jdk.httpclient.disableRetryConnect","true");
            try {new SignedHttpClient("workflow",null,null,null,null,null,null,null);System.exit(2);}
            catch(SignedHttpClient.TransportException error){if(!error.getMessage().contains("JVM startup"))System.exit(3);}
        }
    }
    static Set<String> selectorThreads(){var names=new HashSet<String>();Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName).filter(name->name.startsWith("HttpClient-")&&name.endsWith("-SelectorManager")).forEach(names::add);return names;}
    static void awaitNoOwnedThreads() throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<end){if(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().startsWith("aetheris-signed-http-")))return;Thread.sleep(20);}
        fail("Signed HTTP platform threads remain after close");
    }
}
