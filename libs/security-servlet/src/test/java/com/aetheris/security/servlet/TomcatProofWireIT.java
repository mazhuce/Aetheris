package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardThreadExecutor;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP + disposable PostgreSQL. Session state is a local test double, not auth/RBAC. */
class TomcatProofWireIT {
    static final String PATH="/internal/v2/wire/echo", OP="wire.echo";
    static final byte[] BODY="{ \"id\":7, \"text\":\"真实 HTTP\" }\n".getBytes(StandardCharsets.UTF_8);
    final Clock clock=Clock.systemUTC();
    final AtomicInteger sessionState=new AtomicInteger(1), executions=new AtomicInteger(), directExecutions=new AtomicInteger();
    final Queue<Throwable> containerFailures=new ConcurrentLinkedQueue<>();
    ProtocolSigner workflow,auth,gateway;
    ProtocolVerifier verifier;
    SessionValidator sessions=(uid,name,av)->{if(sessionState.get()==2)throw new IllegalStateException("private session details");return sessionState.get()==1&&uid==7&&name.equals("alice")&&av==3;};

    @Test void actualTomcatWireProofsSharedNonceDirectIdentityAndHttpBoundaries() throws Exception {
        String url=System.getenv("AETHERIS_TEST_NONCE_POSTGRES_URL"),password=System.getenv("AETHERIS_TEST_NONCE_POSTGRES_PASSWORD");
        assertNotNull(url,"Use audit-nonce-store.py --with-servlet-wire");assertNotNull(password);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/aetheris_nonce_audit"));
        assertTrue(password.matches("[A-Za-z0-9_-]{24,}"));keys();
        try(Connection db=DriverManager.getConnection(url,"nonce_admin",password);Statement sql=db.createStatement()) {
            sql.execute("CREATE SCHEMA auth");
            try(var input=getClass().getResourceAsStream("/internal-request-nonce.sql.template")) {
                assertNotNull(input);sql.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("SCHEMA","auth"));
            }
            sql.execute("CREATE ROLE nonce_wire_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD '"+password+"'");
            sql.execute("GRANT USAGE ON SCHEMA auth TO nonce_wire_runtime; GRANT SELECT,INSERT,DELETE ON auth.internal_request_nonce TO nonce_wire_runtime");
            var source=new TestDataSource(()->DriverManager.getConnection(url,"nonce_wire_runtime",password));
            Path owned=Files.createTempDirectory("aetheris-tomcat-wire-");
            var servers=new ArrayList<Receiver>();
            var logHandler=new Handler(){public void publish(LogRecord record){if(record.getThrown()!=null&&record.getLevel().intValue()>=Level.SEVERE.intValue())containerFailures.add(record.getThrown());}public void flush(){}public void close(){}};
            Logger.getLogger("").addHandler(logHandler);
            boolean exercised=false;
            try {
                servers.add(new Receiver(owned.resolve("one"),new PostgresNonceStore(source,"auth",clock)));
                servers.add(new Receiver(owned.resolve("two"),new PostgresNonceStore(source,"auth",clock)));
                Receiver one=servers.getFirst(),two=servers.getLast();
                try(var http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                    try(var client=new SignedHttpClient("workflow",workflow,verifier,clock,TimePolicy.defaults(),Map.of("auth",one.origin()),Map.of("auth",Set.of(OP)),
                            new SignedHttpClient.Limits(1024*1024,1024*1024,4,Duration.ofSeconds(2),Duration.ofSeconds(8)))) {
                        var response=client.send(new SignedHttpClient.Request("auth","POST",PATH,OP,BODY,user("workflow")));
                        assertEquals(201,response.status());assertArrayEquals(BODY,response.body());assertEquals("auth",response.verified().caller());
                    }
                    assertEquals(1,executions.get());
                    sharedNonce(http,one,two);
                    directIdentity(http,one);
                    internalBoundaries(http,one);
                    deadlineBoundary(one,false);deadlineBoundary(one,true);
                }
                // Close the client's keep-alive connections before measuring connector quiescence.
                // The request deadline assertions remain independent of bounded input-drain cleanup.
                awaitQuiescence(one);awaitQuiescence(two);
                assertTrue(containerFailures.isEmpty(),"Unexpected severe Tomcat container exceptions: "+containerFailures.size());
                assertEquals(16,one.executor.getMaxThreads());assertEquals(64,one.executor.getMaxQueueSize());
                assertEquals(2000,((AbstractProtocol<?>)one.connector.getProtocolHandler()).getConnectionTimeout());
                for(var server:servers)for(var mapping:server.context.findFilterMaps())
                    assertEquals(Set.of("REQUEST","ASYNC","FORWARD","INCLUDE","ERROR"),Set.of(mapping.getDispatcherNames()));
                exercised=true;
            } finally {
                var cleanupFailures=new ArrayList<Exception>();
                for(var server:servers.reversed())try{server.close();}catch(Exception failure){cleanupFailures.add(failure);}
                try {try(var files=Files.walk(owned)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.delete(file);}}
                finally {Logger.getLogger("").removeHandler(logHandler);}
                assertFalse(Files.exists(owned));
                for(var server:servers){assertPortClosed(server.port);assertNoThreads(server);}
                assertTrue(cleanupFailures.isEmpty(),"Owned Tomcat shutdown failed");
            }
            assertTrue(exercised);assertEquals(2,servers.size());
            try(var rows=sql.executeQuery("SELECT count(*) FROM auth.internal_request_nonce")){assertTrue(rows.next());assertEquals(5,rows.getInt(1));}
            try(var privileges=sql.executeQuery("SELECT has_table_privilege('nonce_wire_runtime','auth.internal_request_nonce','UPDATE')")){assertTrue(privileges.next());assertFalse(privileges.getBoolean(1));}
            System.out.println("AETHERIS_SERVLET_WIRE_RESULT="+new ObjectMapper().writeValueAsString(Map.of(
                    "tomcatVersion",org.apache.catalina.util.ServerInfo.getServerNumber(),"servers",2,"duplicateRequests",24,
                    "duplicateWinners",1,"duplicateSignedConflicts",23,"nonceRows",5,"portsClosed",true,"threadsStopped",true,
                    "ownedTempRemoved",true,"sessionValidator","local-test-state")));
        }
    }

    void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");var w=generator.generateKeyPair();var a=generator.generateKeyPair();var g=generator.generateKeyPair();
        var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("workflow-wire",KeyPurpose.WORKLOAD,"workflow",w.getPublic()),
                new KeyRegistry.Entry("auth-wire",KeyPurpose.WORKLOAD,"auth",a.getPublic()),new KeyRegistry.Entry("gateway-wire",KeyPurpose.USER,"gateway",g.getPublic())));
        verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
        workflow=new ProtocolSigner("workflow-wire",KeyPurpose.WORKLOAD,"workflow",w.getPrivate(),registry,clock,TimePolicy.defaults());
        auth=new ProtocolSigner("auth-wire",KeyPurpose.WORKLOAD,"auth",a.getPrivate(),registry,clock,TimePolicy.defaults());
        gateway=new ProtocolSigner("gateway-wire",KeyPurpose.USER,"gateway",g.getPrivate(),registry,clock,TimePolicy.defaults());
    }
    String user(String audience){long now=clock.instant().getEpochSecond();return gateway.sign(new UserProof(2,"gateway",audience,7,"alice",3,now,now+120,"wire-request"));}
    String proof(String path,byte[] body,String nonce,String user){long now=clock.instant().getEpochSecond();return workflow.sign(new WorkloadRequestProof(2,"workflow","auth","POST",path,BodyHash.sha256(body),nonce,now,now+30,OP,BodyHash.userProof(user)));}
    HttpResponse<byte[]> send(HttpClient http,Receiver server,String path,byte[] body,String... headers) throws Exception {
        var request=HttpRequest.newBuilder(server.origin().resolve(path)).timeout(Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        for(int i=0;i<headers.length;i+=2)request.header(headers[i],headers[i+1]);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    void signed(HttpResponse<byte[]> response,String nonce,int status){assertEquals(status,response.statusCode());
        var header=response.headers().allValues(SignedHttpClient.RESPONSE_PROOF);assertEquals(1,header.size());
        verifier.verifyResponse(header.getFirst(),new ExpectedResponse("auth","workflow",nonce,status,BodyHash.sha256(response.body())));
    }
    void sharedNonce(HttpClient http,Receiver one,Receiver two) throws Exception {
        int baseline=executions.get();String nonce=Nonce.random(),user=user("workflow"),proof=proof(PATH,BODY,nonce,user);
        var start=new CountDownLatch(1);var winners=new AtomicInteger();var conflicts=new AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<24;i++){var receiver=i%2==0?one:two;futures.add(workers.submit(()->{start.await();
                var response=send(http,receiver,PATH,BODY,SignedHttpClient.REQUEST_PROOF,proof,SignedHttpClient.USER_PROOF,user);
                if(response.statusCode()==201){signed(response,nonce,201);assertArrayEquals(BODY,response.body());winners.incrementAndGet();}
                else{signed(response,nonce,409);assertEquals("{\"message\":\"Internal request replay rejected\"}",new String(response.body(),StandardCharsets.UTF_8));conflicts.incrementAndGet();}return null;}));}
            start.countDown();for(var future:futures)future.get(15,TimeUnit.SECONDS);
        }
        assertEquals(1,winners.get());assertEquals(23,conflicts.get());assertEquals(baseline+1,executions.get());
    }
    void directIdentity(HttpClient http,Receiver one) throws Exception {
        String token=user("auth");
        var response=send(http,one,"/api/private",new byte[0],SignedHttpClient.USER_PROOF,token,"X-User-Id","999","X-Aetheris-Identity","old-HMAC");
        assertEquals(200,response.statusCode());assertEquals("alice",new String(response.body(),StandardCharsets.UTF_8));assertEquals(1,directExecutions.get());
        for(String mode:List.of("missing","duplicate","audience","disabled","unavailable","old")){
            sessionState.set(mode.equals("disabled")?0:mode.equals("unavailable")?2:1);
            response=switch(mode){
                case "missing"->send(http,one,"/api/private",new byte[0]);
                case "duplicate"->send(http,one,"/api/private",new byte[0],SignedHttpClient.USER_PROOF,token,SignedHttpClient.USER_PROOF,token);
                case "old"->send(http,one,"/api/private",new byte[0],"X-Aetheris-Identity","old-HMAC","X-User-Name","admin");
                default->send(http,one,"/api/private",new byte[0],SignedHttpClient.USER_PROOF,mode.equals("audience")?user("metadata"):token);
            };
            assertEquals(mode.equals("disabled")?403:mode.equals("unavailable")?503:401,response.statusCode(),mode);
            assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains("private session"));
        }
        sessionState.set(1);assertEquals(1,directExecutions.get());
        assertEquals(400,send(http,one,"/api/public/forward",new byte[0]).statusCode());
    }
    void internalBoundaries(HttpClient http,Receiver one) throws Exception {
        int baseline=executions.get();String user=user("workflow");
        for(String path:List.of("/internal/v2/wire/%65cho",PATH+"?x=1","/internal/legacy"))
            assertEquals(400,send(http,one,path,new byte[0],"X-Internal-Token","legacy").statusCode(),path);
        assertEquals(401,send(http,one,PATH,BODY,"X-Internal-Token","legacy").statusCode());
        assertEquals(415,send(http,one,PATH,BODY,SignedHttpClient.REQUEST_PROOF,proof(PATH,BODY,Nonce.random(),user),SignedHttpClient.USER_PROOF,user,"Content-Encoding","gzip").statusCode());
        byte[] large=new byte[WorkloadProofFilter.MAX_BODY_BYTES+1];
        assertEquals(413,send(http,one,PATH,large,SignedHttpClient.REQUEST_PROOF,proof(PATH,large,Nonce.random(),user),SignedHttpClient.USER_PROOF,user).statusCode());
        assertEquals(baseline,executions.get());
        for(String mode:List.of("failure","async","stream")) {
            String path="/internal/v2/wire/"+mode,nonce=Nonce.random();
            var response=send(http,one,path,BODY,SignedHttpClient.REQUEST_PROOF,proof(path,BODY,nonce,user),SignedHttpClient.USER_PROOF,user);
            signed(response,nonce,500);String text=new String(response.body(),StandardCharsets.UTF_8);
            assertEquals("{\"message\":\"Internal request failed\"}",text);assertFalse(text.contains("private"));
        }
        assertEquals(baseline+3,executions.get());
    }
    void deadlineBoundary(Receiver receiver,boolean body) throws Exception {
        int baseline=executions.get();long start=System.nanoTime();
        try(var socket=new Socket()){
            socket.connect(new InetSocketAddress("127.0.0.1",receiver.port),2000);socket.setSoTimeout(6000);
            String request="POST "+PATH+" HTTP/1.1\r\nHost: 127.0.0.1\r\n";
            if(body){String user=user("workflow");request+=SignedHttpClient.REQUEST_PROOF+": "+proof(PATH,BODY,Nonce.random(),user)+"\r\n"+SignedHttpClient.USER_PROOF+": "+user+"\r\nContent-Length: 8\r\n\r\n{}";}
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            try {
                String line=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                System.out.println("AETHERIS_SERVLET_WIRE_DEADLINE="+(body?"body:":"header:")+(line==null?"closed":line.substring(9,12)));
                // Tomcat may close the socket instead of sending an error after an input timeout.
                assertTrue(line==null||line.startsWith("HTTP/1.1 400 ")||line.startsWith("HTTP/1.1 408 "),"Unexpected input timeout HTTP status");
            }catch(SocketTimeoutException error){fail("Tomcat input deadline not applied");}
        }
        assertTrue(Duration.ofNanos(System.nanoTime()-start).compareTo(Duration.ofSeconds(5))<0);assertEquals(baseline,executions.get());
    }
    final class Receiver implements AutoCloseable {
        final Tomcat tomcat=new Tomcat();final StandardThreadExecutor executor=new StandardThreadExecutor();
        final Connector connector=new Connector("org.apache.coyote.http11.Http11NioProtocol");final Context context;
        final String prefix="nonce-wire-"+UUID.randomUUID()+"-";final int port;final String protocolName;
        Receiver(Path base,NonceStore store) throws Exception {
            Files.createDirectories(base.resolve("web"));tomcat.setBaseDir(base.toString());tomcat.setHostname("127.0.0.1");
            executor.setName(prefix);executor.setNamePrefix(prefix);executor.setMaxThreads(16);executor.setMinSpareThreads(2);executor.setMaxQueueSize(64);
            tomcat.getService().addExecutor(executor);connector.setPort(0);connector.setProperty("address","127.0.0.1");
            var protocol=(AbstractProtocol<?>)connector.getProtocolHandler();protocol.setExecutor(executor);protocol.setConnectionTimeout(2000);protocol.setKeepAliveTimeout(1000);protocol.setMaxConnections(32);protocol.setAcceptCount(16);
            connector.setProperty("maxHttpRequestHeaderSize","32768");connector.setProperty("disableUploadTimeout","true");connector.setProperty("compression","off");
            tomcat.getService().addConnector(connector);tomcat.setConnector(connector);
            context=tomcat.addContext("",base.resolve("web").toString());context.setParentClassLoader(getClass().getClassLoader());context.setIgnoreAnnotations(true);
            RoutePolicy routes=(method,path)->method.equals("POST")&&Set.of(PATH,"/internal/v2/wire/failure","/internal/v2/wire/async","/internal/v2/wire/stream").contains(path)
                    ?Optional.of(new RoutePolicy.Route(OP,Set.of("workflow"),RoutePolicy.UserMode.REQUIRED)):Optional.empty();
            addFilter("workload",new WorkloadProofFilter("auth",verifier,auth,routes,store,sessions,clock,TimePolicy.defaults()));
            addFilter("direct",new DirectUserProofFilter("auth",verifier,sessions,(method,path)->path.equals("/api/public/forward")?DirectRoutePolicy.Decision.PUBLIC:DirectRoutePolicy.Decision.PROTECTED));
            var wrapper=Tomcat.addServlet(context,"wire",new HttpServlet(){
                @Override protected void service(HttpServletRequest request,HttpServletResponse response) throws IOException,ServletException {
                    String path=request.getRequestURI();
                    if(path.equals("/api/public/forward")){request.getRequestDispatcher(PATH).forward(request,response);return;}
                    if(path.startsWith("/api/")){
                        var trusted=TrustedUser.require(request);assertEquals(7,trusted.proof().uid());assertEquals("alice",trusted.proof().username());
                        assertNull(request.getHeader(SignedHttpClient.USER_PROOF));assertNull(request.getHeader("X-User-Id"));assertNull(request.getHeader("X-Aetheris-Identity"));
                        assertThrows(IllegalStateException.class,()->request.removeAttribute(TrustedUser.ATTRIBUTE));directExecutions.incrementAndGet();response.getWriter().write("alice");return;
                    }
                    var trusted=WorkloadProofFilter.trusted(request);assertEquals("workflow",trusted.workload().caller());assertEquals(7,trusted.user().orElseThrow().proof().uid());
                    assertEquals(BodyHash.userProof(trusted.userProof().orElseThrow()),trusted.workload().proof().userProofSha256());
                    assertEquals(7,TrustedUser.require(request).proof().uid());assertNull(request.getHeader(SignedHttpClient.REQUEST_PROOF));assertNull(request.getHeader(SignedHttpClient.USER_PROOF));
                    assertFalse(request.isAsyncSupported());assertArrayEquals(BODY,request.getInputStream().readAllBytes());assertArrayEquals(BODY,request.getInputStream().readAllBytes());executions.incrementAndGet();
                    if(path.endsWith("failure")){response.getWriter().write("private partial details");throw new ServletException("private exception details");}
                    if(path.endsWith("async")){request.startAsync();return;}
                    if(path.endsWith("stream")){request.getInputStream().setReadListener(new ReadListener(){public void onDataAvailable(){}public void onAllDataRead(){}public void onError(Throwable t){}});return;}
                    response.setStatus(201);response.getOutputStream().write(BODY);
                }
            });wrapper.setAsyncSupported(false);context.addServletMappingDecoded("/*","wire");
            try {tomcat.start();port=connector.getLocalPort();assertTrue(port>0);protocolName=protocol.getName();}
            catch(Exception|Error error){tomcat.stop();tomcat.destroy();throw error;}
        }
        URI origin(){return URI.create("http://127.0.0.1:"+port);}
        void addFilter(String name,Filter filter){var definition=new FilterDef();definition.setFilterName(name);definition.setFilter(filter);definition.setAsyncSupported("false");context.addFilterDef(definition);
            var mapping=new FilterMap();mapping.setFilterName(name);mapping.addURLPattern("/*");for(var dispatcher:DispatcherType.values())mapping.setDispatcher(dispatcher.name());context.addFilterMap(mapping);}
        @Override public void close() throws Exception {try{tomcat.stop();}finally{tomcat.destroy();}}
    }
    void assertPortClosed(int port) throws Exception {try(var socket=new Socket()){assertThrows(ConnectException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),500));}}
    String ownedThreadDiagnostics(Receiver server) {
        var result=new StringBuilder("Owned executor active=").append(server.executor.getActiveCount()).append(" queued=").append(server.executor.getQueueSize());
        Thread.getAllStackTraces().forEach((thread,stack)->{
            if(thread.isAlive()&&thread.getName().startsWith(server.prefix)) {
                result.append('\n').append(thread.getName()).append(' ').append(thread.getState());
                for(var frame:stack)result.append("\n  ").append(frame);
            }
        });return result.toString();
    }
    void awaitQuiescence(Receiver server) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5),idleSince=0;
        while(System.nanoTime()<end) {
            if(server.executor.getActiveCount()==0&&server.executor.getQueueSize()==0) {
                if(idleSince==0)idleSince=System.nanoTime();
                if(System.nanoTime()-idleSince>=TimeUnit.MILLISECONDS.toNanos(100))return;
            }else idleSince=0;
            Thread.sleep(10);
        }
        assertEquals(0,server.executor.getActiveCount(),()->ownedThreadDiagnostics(server));
        assertEquals(0,server.executor.getQueueSize(),()->ownedThreadDiagnostics(server));
        fail("Owned executor did not remain idle within cleanup deadline");
    }
    void assertNoThreads(Receiver server) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end&&Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&(t.getName().startsWith(server.prefix)||t.getName().contains(server.protocolName))))Thread.sleep(25);
        assertFalse(Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&(t.getName().startsWith(server.prefix)||t.getName().contains(server.protocolName))),"Owned Tomcat threads remain");
    }
}
