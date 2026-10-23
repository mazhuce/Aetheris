package com.aetheris.aiagent.security;

import com.aetheris.aiagent.controller.ChatController;
import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.service.*;
import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.servlet.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Servlet dispatch + Spring DeferredResult + actual AI worker. No model provider or database. */
class AiAsyncTomcatTest {
    @Configuration @EnableWebMvc static class Mvc { }
    static final class MutableClock extends Clock {
        final AtomicLong seconds=new AtomicLong(Clock.systemUTC().instant().getEpochSecond());
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochSecond(seconds.get());}
    }
    @Test void actualDeferredResultAuthenticatesEveryResumeAndRejectsUnadmittedDispatch() throws Exception {
        var clock=new MutableClock();var keys=new AiTestKeys(clock);var active=new AtomicBoolean(true);var sessionChecks=new AtomicInteger();
        SessionValidator sessions=(uid,name,av)->{sessionChecks.incrementAndGet();return active.get()&&uid==7&&name.equals("alice")&&av==3;};
        var entered=new AtomicReference<CountDownLatch>();var release=new AtomicReference<CountDownLatch>();
        var service=mock(ChatService.class);var limiter=mock(UserRateLimiter.class);when(limiter.tryAcquire(ChatOwnerFixture.owner("alice"))).thenReturn(true);
        when(service.chatBi(anyString())).thenAnswer(call->{
            assertNull(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes());
            assertEquals("alice",AiProofContext.current().user().proof().username());assertNotNull(AiRunContext.current());
            entered.get().countDown();assertTrue(release.get().await(3,TimeUnit.SECONDS));
            return new LlmReply("local response","local-mock",1,1);
        });
        Path owned=Files.createTempDirectory(Path.of("target"),"ai-async-tomcat-").toAbsolutePath().normalize();
        var tomcat=new Tomcat();tomcat.setBaseDir(owned.toString());tomcat.setHostname("127.0.0.1");tomcat.setPort(0);
        var connector=tomcat.getConnector();connector.setProperty("address","127.0.0.1");connector.setProperty("maxThreads","8");connector.setProperty("minSpareThreads","1");
        connector.setProperty("connectionTimeout","2000");connector.setProperty("asyncTimeout","4000");
        Path doc=owned.resolve("doc");Files.createDirectory(doc);var context=tomcat.addContext("",doc.toString());context.setParentClassLoader(getClass().getClassLoader());
        var app=new GenericWebApplicationContext();AnnotationConfigUtils.registerAnnotationConfigProcessors(app);
        var worker=new AiRequestExecutor(new AiBudgetLimits(2,2,10000,1000,1000,3000,1,1));
        var controller=new ChatController(service,limiter);controller.setRequestExecutor(worker);
        app.registerBean(Mvc.class);app.registerBean(AiRequestExecutor.class,()->worker);app.registerBean(ChatController.class,()->controller);
        app.registerBean(WebMvcConfigurer.class,()->new WebMvcConfigurer(){public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new IdentityInterceptor()).addPathPatterns("/api/ai/**");}});
        var dispatcher=new DispatcherServlet(app);var servlet=Tomcat.addServlet(context,"mvc",dispatcher);servlet.setAsyncSupported(true);servlet.setLoadOnStartup(1);context.addServletMappingDecoded("/","mvc");
        var asyncThreads=Executors.newSingleThreadExecutor();
        var dispatchServlet=Tomcat.addServlet(context,"test-dispatch",new HttpServlet(){
            @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws java.io.IOException,ServletException {
                if(request.getRequestURI().equals("/outside-forward")){request.getRequestDispatcher("/api/ai/conversations").forward(request,response);return;}
                var async=request.startAsync();asyncThreads.submit(()->async.dispatch("/api/ai/conversations"));
            }
        });dispatchServlet.setAsyncSupported(true);
        for(String path:List.of("/outside-async","/outside-forward","/api/ai/dispatch"))context.addServletMappingDecoded(path,"test-dispatch");
        filter(context,"workload",new WorkloadProofFilter("ai-agent",keys.verifier,keys.ai,(m,p)->Optional.empty(),(c,n,e)->{throw new AssertionError("No internal route");},sessions,clock,TimePolicy.defaults()),EnumSet.allOf(DispatcherType.class));
        filter(context,"direct",new DirectUserProofFilter("ai-agent",keys.verifier,sessions,(m,p)->p.equals("/api/ai")||p.startsWith("/api/ai/")?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT),EnumSet.of(DispatcherType.REQUEST,DispatcherType.FORWARD,DispatcherType.INCLUDE,DispatcherType.ERROR));
        filter(context,"resume",new AiAsyncAdmissionFilter(keys.verifier,sessions,clock),EnumSet.allOf(DispatcherType.class));
        int port=0;
        try {
            tomcat.start();port=connector.getLocalPort();assertTrue(port>0);
            try(var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                long now=clock.instant().getEpochSecond();
                String wrongAudience=keys.user.sign(new com.aetheris.security.protocol.UserProof(2,"gateway","metadata",7,"alice",3,now,now+60,"request-wrong"));
                String wrongPurpose=keys.ai.sign(new com.aetheris.security.protocol.WorkloadResponseProof(2,"ai-agent","metadata",com.aetheris.security.protocol.Nonce.random(),200,com.aetheris.security.protocol.BodyHash.sha256(new byte[0]),now,now+30));
                for(String proof:List.of("", "invalid",wrongAudience,wrongPurpose)) {
                    var bad=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ai/conversations")).timeout(Duration.ofSeconds(3))
                            .header("X-Aetheris-Identity","old-hmac").header("X-Internal-Token","old-token").header("X-User-Name","alice");
                    if(!proof.isEmpty())bad.header("X-Aetheris-User-Proof",proof);
                    assertEquals(401,http.send(bad.GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
                }
                for(String mode:List.of("valid","disabled","expired")){
                    clock.seconds.set(Clock.systemUTC().instant().getEpochSecond());active.set(true);entered.set(new CountDownLatch(1));release.set(new CountDownLatch(1));
                    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ai/chatbi")).timeout(Duration.ofSeconds(5))
                            .header("X-Aetheris-User-Proof",keys.token("alice")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"local question\"}")).build();
                    int before=sessionChecks.get();var result=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());assertTrue(entered.get().await(2,TimeUnit.SECONDS));
                    if(mode.equals("disabled"))active.set(false);if(mode.equals("expired"))clock.seconds.addAndGet(61);
                    release.get().countDown();var response=result.get(5,TimeUnit.SECONDS);
                    assertEquals(mode.equals("valid")?200:403,response.statusCode(),mode+": "+response.body());
                    if(mode.equals("valid")){assertTrue(response.body().contains("local response"));assertTrue(sessionChecks.get()>=before+2);}
                }
                clock.seconds.set(Clock.systemUTC().instant().getEpochSecond());active.set(true);
                for(String path:List.of("/outside-async","/api/ai/dispatch","/outside-forward")) {
                    var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(4))
                            .header("X-Aetheris-User-Proof",keys.token("alice")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                    assertTrue(response.statusCode()==400||response.statusCode()==403,path+": "+response.statusCode());
                }
                verify(service,never()).listConversations(any(ChatOwner.class));
            }
        } finally {
            if(release.get()!=null)release.get().countDown();worker.close();asyncThreads.shutdownNow();assertTrue(asyncThreads.awaitTermination(3,TimeUnit.SECONDS));
            try{tomcat.stop();}finally{tomcat.destroy();app.close();}
            try(var paths=Files.walk(owned)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
            assertFalse(Files.exists(owned));
            int stoppedPort=port;
            if(stoppedPort>0)try(var probe=new java.net.Socket()){assertThrows(java.io.IOException.class,()->probe.connect(new InetSocketAddress("127.0.0.1",stoppedPort),300));}
        }
    }
    private static void filter(org.apache.catalina.Context context,String name,Filter filter,EnumSet<DispatcherType> dispatchers){
        var definition=new FilterDef();definition.setFilterName(name);definition.setFilter(filter);definition.setAsyncSupported("true");context.addFilterDef(definition);
        var mapping=new FilterMap();mapping.setFilterName(name);mapping.addURLPattern("/*");for(var dispatcher:dispatchers)mapping.setDispatcher(dispatcher.name());context.addFilterMap(mapping);
    }
}
