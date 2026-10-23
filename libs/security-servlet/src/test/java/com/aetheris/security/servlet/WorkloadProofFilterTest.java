package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class WorkloadProofFilterTest {
    @Test void springExceptionResolverCanClearHeadersAndKeepItsSignedBadRequest() throws Exception {
        String nonce=Nonce.random();
        var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),request(nonce),(req,res)->{
            var http=(HttpServletResponse)res;
            http.setContentType("application/json");http.setHeader("Content-Type",null);
            assertNull(http.getHeader("Content-Type"));
            http.setHeader("X-Example","one");http.addHeader("X-Example",null);
            assertEquals(List.of("one"),new ArrayList<>(http.getHeaders("X-Example")));
            http.setHeader("X-Example",null);assertFalse(http.containsHeader("X-Example"));
            http.setHeader(SignedHttpClient.RESPONSE_PROOF,null);http.setHeader("Content-Encoding",null);
            http.setStatus(400);http.getOutputStream().write("{\"message\":\"invalid request\"}".getBytes(StandardCharsets.UTF_8));
        });
        verify(response,nonce,400);assertTrue(response.getContentAsString().contains("invalid request"));
    }
    static final long NOW=1800000000;
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC);
    static final TimePolicy POLICY=TimePolicy.defaults();
    static final String PATH="/internal/v2/query",OP="table.query";
    static final byte[] BODY="{ \"id\" : 7, \"name\":\"测试\" }".getBytes(StandardCharsets.UTF_8);
    static ProtocolSigner workflow,metadata,quality,gateway;
    static ProtocolVerifier verifier;
    static KeyRegistry registry;
    static PrivateKey userPrivateKey;
    NonceStore nonces;
    AtomicInteger claims,executed;
    AtomicBoolean sessionActive;

    @BeforeAll static void keys() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");var w=generator.generateKeyPair();var m=generator.generateKeyPair();var q=generator.generateKeyPair();var u=generator.generateKeyPair();
        registry=new KeyRegistry(List.of(new KeyRegistry.Entry("workflow-1",KeyPurpose.WORKLOAD,"workflow",w.getPublic()),new KeyRegistry.Entry("metadata-1",KeyPurpose.WORKLOAD,"metadata",m.getPublic()),new KeyRegistry.Entry("quality-1",KeyPurpose.WORKLOAD,"quality",q.getPublic()),new KeyRegistry.Entry("gateway-1",KeyPurpose.USER,"gateway",u.getPublic())));userPrivateKey=u.getPrivate();
        workflow=new ProtocolSigner("workflow-1",KeyPurpose.WORKLOAD,"workflow",w.getPrivate(),registry,CLOCK,POLICY);
        metadata=new ProtocolSigner("metadata-1",KeyPurpose.WORKLOAD,"metadata",m.getPrivate(),registry,CLOCK,POLICY);
        quality=new ProtocolSigner("quality-1",KeyPurpose.WORKLOAD,"quality",q.getPrivate(),registry,CLOCK,POLICY);
        gateway=new ProtocolSigner("gateway-1",KeyPurpose.USER,"gateway",u.getPrivate(),registry,CLOCK,POLICY);
        verifier=new ProtocolVerifier(registry,CLOCK,POLICY);
    }
    @BeforeEach void fixture(){claims=new AtomicInteger();executed=new AtomicInteger();sessionActive=new AtomicBoolean(true);
        Set<String> claimed=ConcurrentHashMap.newKeySet();nonces=(caller,nonce,expires)->{claims.incrementAndGet();assertEquals(NOW+30,expires);return claimed.add(caller+":"+nonce);};}
    WorkloadProofFilter filter(RoutePolicy.UserMode mode){return filter(mode,nonces,(uid,name,av)->uid==7&&name.equals("alice")&&av==3&&sessionActive.get());}
    WorkloadProofFilter filter(RoutePolicy.UserMode mode,NonceStore store,SessionValidator sessions){
        return new WorkloadProofFilter("metadata",verifier,metadata,(method,path)->method.equals("POST")&&path.equals(PATH)
                ?Optional.of(new RoutePolicy.Route(OP,Set.of("workflow"),mode)):Optional.empty(),store,sessions,CLOCK,POLICY,512);
    }
    String token(String method,String path,byte[] body,String nonce,String op,ProtocolSigner signer,String caller){
        return token(method,path,body,nonce,op,signer,caller,null);
    }
    String token(String method,String path,byte[] body,String nonce,String op,ProtocolSigner signer,String caller,String userProof){
        return signer.sign(new WorkloadRequestProof(2,caller,"metadata",method,path,BodyHash.sha256(body),nonce,NOW,NOW+30,op,
                BodyHash.userProof(userProof)));
    }
    MockHttpServletRequest request(){return request(Nonce.random());}
    MockHttpServletRequest request(String nonce){
        var request=new MockHttpServletRequest("POST",PATH);request.setAttribute("fixture.nonce",nonce);request.setContent(BODY);request.addHeader(SignedHttpClient.REQUEST_PROOF,token("POST",PATH,BODY,nonce,OP,workflow,"workflow"));return request;
    }
    void bindUser(MockHttpServletRequest request,String userProof){
        request.removeHeader(SignedHttpClient.USER_PROOF);request.addHeader(SignedHttpClient.USER_PROOF,userProof);
        request.removeHeader(SignedHttpClient.REQUEST_PROOF);request.addHeader(SignedHttpClient.REQUEST_PROOF,
                token("POST",PATH,BODY,(String)request.getAttribute("fixture.nonce"),OP,workflow,"workflow",userProof));
    }
    String user(String audience,long av){return gateway.sign(new UserProof(2,"gateway",audience,7,"alice",av,NOW,NOW+120,"request-1"));}
    MockHttpServletResponse run(WorkloadProofFilter filter,MockHttpServletRequest request,FilterChain chain) throws Exception {
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{executed.incrementAndGet();chain.doFilter(req,res);});return response;
    }
    void verify(MockHttpServletResponse response,String nonce,int status){
        assertEquals(status,response.getStatus());var verified=verifier.verifyResponse(response.getHeader(SignedHttpClient.RESPONSE_PROOF),
                new ExpectedResponse("metadata","workflow",nonce,status,BodyHash.sha256(response.getContentAsByteArray())));
        assertEquals("metadata",verified.caller());
    }
    @Test void exactRawBytesReplayableTrustedIdentityAndActualResponseSignature() throws Exception {
        String nonce=Nonce.random();var input=request(nonce);input.addHeader("X-Internal-Token","old-token");input.addHeader("X-User-Id","999");input.addHeader("X-Aetheris-Identity","old-identity");input.addHeader("Cookie","session=fake");input.addHeader("Authorization","Bearer fake");input.setAttribute(WorkloadProofFilter.TRUSTED_ATTRIBUTE,"forged");
        var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),input,(req,res)->{
            var r=(HttpServletRequest)req;var s=(HttpServletResponse)res;assertArrayEquals(BODY,r.getInputStream().readAllBytes());assertArrayEquals(BODY,r.getInputStream().readAllBytes());
            var trusted=WorkloadProofFilter.trusted(r);assertEquals("workflow",trusted.workload().caller());assertTrue(trusted.user().isEmpty());
            for(String header:List.of("X-Internal-Token","X-User-Id","X-Aetheris-Identity","Cookie","Authorization",SignedHttpClient.REQUEST_PROOF))assertNull(r.getHeader(header));
            assertFalse(r.getHeaderNames().asIterator().hasNext());assertNull(r.getCookies());assertNull(r.getUserPrincipal());
            assertThrows(IllegalStateException.class,()->r.setAttribute(WorkloadProofFilter.TRUSTED_ATTRIBUTE,"replace"));
            s.setStatus(201);s.getOutputStream().write(BODY);s.flushBuffer();assertFalse(s.isCommitted());
        });
        verify(response,nonce,201);assertArrayEquals(BODY,response.getContentAsByteArray());assertEquals(1,claims.get());assertEquals(1,executed.get());
    }
    @TestFactory Stream<DynamicTest> bindingAndPurposeFailuresNeverReachChain(){
        return Stream.of("method","path","body","operation","caller","purpose","signature","missing").map(mode->DynamicTest.dynamicTest(mode,()->{
            var input=request();String nonce=Nonce.random();
            switch(mode){
                case "method"->input.setMethod("GET");
                case "path"->input.setRequestURI(PATH+"/other");
                case "body"->input.setContent(new byte[]{1});
                case "operation"->input.removeHeader(SignedHttpClient.REQUEST_PROOF);
                case "caller"->{input.removeHeader(SignedHttpClient.REQUEST_PROOF);input.addHeader(SignedHttpClient.REQUEST_PROOF,token("POST",PATH,BODY,nonce,OP,quality,"quality"));}
                case "purpose"->{input.removeHeader(SignedHttpClient.REQUEST_PROOF);input.addHeader(SignedHttpClient.REQUEST_PROOF,user("workflow",3));}
                case "signature"->{String old=input.getHeader(SignedHttpClient.REQUEST_PROOF);input.removeHeader(SignedHttpClient.REQUEST_PROOF);input.addHeader(SignedHttpClient.REQUEST_PROOF,old.substring(0,old.length()-4)+"AAAA");}
                case "missing"->input.removeHeader(SignedHttpClient.REQUEST_PROOF);
            }
            if(mode.equals("operation"))input.addHeader(SignedHttpClient.REQUEST_PROOF,token("POST",PATH,BODY,nonce,"manage.all",workflow,"workflow"));
            int before=executed.get(),claimBefore=claims.get();var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),input,(req,res)->fail("Rejected input reached chain"));
            assertTrue(response.getStatus()>=400);assertNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));assertEquals(before,executed.get());assertEquals(claimBefore,claims.get());
        }));
    }
    @TestFactory Stream<DynamicTest> canonicalAndHeaderBoundaries(){
        return Stream.of("percent","encodedPrefix","query","slash","dot","backslash","old","root","duplicate","oversizedHeader","compressed","duplicateEncoding","bothLengthAndTransfer","responseProof","oversizedInput","wrongLength","asyncDispatch","contextPath")
                .map(mode->DynamicTest.dynamicTest(mode,()->{
                    var input=request();switch(mode){
                        case "percent"->input.setRequestURI("/internal/v2/%71uery");
                        case "encodedPrefix"->{input.setRequestURI("/int%65rnal/v2/query");input.setServletPath(PATH);}
                        case "query"->input.setQueryString("");case "slash"->input.setRequestURI("/internal/v2//query");case "dot"->input.setRequestURI("/internal/v2/../query");
                        case "backslash"->input.setRequestURI("/internal/v2/\\query");case "old"->{input.setRequestURI("/internal/query");input.addHeader("X-Internal-Token","old-shared");}
                        case "root"->input.setRequestURI("/internal/v2");case "duplicate"->input.addHeader(SignedHttpClient.REQUEST_PROOF,input.getHeader(SignedHttpClient.REQUEST_PROOF));
                        case "oversizedHeader"->{input.removeHeader(SignedHttpClient.REQUEST_PROOF);input.addHeader(SignedHttpClient.REQUEST_PROOF,"x".repeat(12289));}
                        case "compressed"->input.addHeader("Content-Encoding","gzip");case "duplicateEncoding"->{input.addHeader("Content-Encoding","identity");input.addHeader("Content-Encoding","identity");}
                        case "bothLengthAndTransfer"->{input.addHeader("Content-Length",BODY.length);input.addHeader("Transfer-Encoding","chunked");}
                        case "responseProof"->input.addHeader(SignedHttpClient.RESPONSE_PROOF,"client-forged");
                        case "oversizedInput"->input.setContent(new byte[WorkloadProofFilter.MAX_BODY_BYTES+1]);
                        case "wrongLength"->input.addHeader("Content-Length","2");case "asyncDispatch"->input.setDispatcherType(DispatcherType.ASYNC);case "contextPath"->input.setContextPath("/internal");
                    }
                    int before=executed.get();var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),input,(req,res)->fail("Invalid input reached chain"));assertTrue(response.getStatus()>=400);assertNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));assertEquals(before,executed.get());
                }));
    }
    @Test void forwardedUserIsVerifiedAgainstAuthenticatedCallerAndLiveVersion() throws Exception {
        String nonce=Nonce.random();var input=request(nonce);bindUser(input,user("workflow",3));
        var response=run(filter(RoutePolicy.UserMode.REQUIRED),input,(req,res)->{
            var trusted=WorkloadProofFilter.trusted((HttpServletRequest)req);assertEquals(7,trusted.user().orElseThrow().proof().uid());assertEquals(user("workflow",3),trusted.userProof().orElseThrow());assertEquals(7,TrustedUser.require((HttpServletRequest)req).proof().uid());
            ((HttpServletResponse)res).getWriter().write("{\"ok\":true}");
        });verify(response,nonce,200);
    }
    @TestFactory Stream<DynamicTest> userForwardingFailures(){
        return Stream.of("missing","wrongAudience","expired","disabled","accessVersion","forbidden","duplicate").map(mode->DynamicTest.dynamicTest(mode,()->{
            var input=request();if(!mode.equals("missing"))bindUser(input,user(mode.equals("wrongAudience")?"metadata":"workflow",mode.equals("accessVersion")?4:3));
            if(mode.equals("expired")){var oldClock=Clock.fixed(Instant.ofEpochSecond(NOW-200),ZoneOffset.UTC);
                var oldSigner=new ProtocolSigner("gateway-1",KeyPurpose.USER,"gateway",userPrivateKey,registry,oldClock,POLICY);
                bindUser(input,oldSigner.sign(new UserProof(2,"gateway","workflow",7,"alice",3,NOW-200,NOW-80,"request-old")));
            }
            if(mode.equals("duplicate"))input.addHeader(SignedHttpClient.USER_PROOF,user("workflow",3));sessionActive.set(!mode.equals("disabled"));
            int before=executed.get();var response=run(filter(mode.equals("forbidden")?RoutePolicy.UserMode.FORBIDDEN:RoutePolicy.UserMode.REQUIRED),input,(req,res)->fail("Invalid user reached chain"));
            assertTrue(response.getStatus()>=400);assertNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));assertEquals(before,executed.get());sessionActive.set(true);
        }));
    }
    @Test void duplicateNonceReturnsSignedConflictWithoutSecondExecution() throws Exception {
        String nonce=Nonce.random();var filter=filter(RoutePolicy.UserMode.FORBIDDEN);
        verify(run(filter,request(nonce),(req,res)->{}),nonce,200);verify(run(filter,request(nonce),(req,res)->fail("Replay reached chain")),nonce,409);assertEquals(1,executed.get());
    }
    @Test void concurrentAtomicNonceClaimExecutesOnce() throws Exception {
        String nonce=Nonce.random();var filter=filter(RoutePolicy.UserMode.FORBIDDEN);var results=new ArrayList<Future<Integer>>();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            for(int i=0;i<24;i++)results.add(executor.submit(()->run(filter,request(nonce),(req,res)->{}).getStatus()));
            int accepted=0,replayed=0;for(var result:results){int status=result.get(3,TimeUnit.SECONDS);if(status==200)accepted++;if(status==409)replayed++;}
            assertEquals(1,accepted);assertEquals(23,replayed);assertEquals(1,executed.get());
        }
    }
    @Test void nonceStoreUnavailableIsSigned503AndNeverRunsChain() throws Exception {
        String nonce=Nonce.random();var filter=filter(RoutePolicy.UserMode.FORBIDDEN,(c,n,e)->{throw new IOException("private SQL connection details");},(id,name,av)->true);
        var response=run(filter,request(nonce),(req,res)->fail("Unavailable store reached chain"));verify(response,nonce,503);assertFalse(response.getContentAsString().contains("SQL"));assertEquals(0,executed.get());
    }
    @Test void sessionServiceFailureRejectsWithoutClaimOrSigning() throws Exception {
        var input=request();bindUser(input,user("workflow",3));var response=run(filter(RoutePolicy.UserMode.REQUIRED,nonces,(id,name,av)->{throw new IOException("private auth endpoint");}),input,(req,res)->fail("Unavailable session reached chain"));
        assertEquals(503,response.getStatus());assertEquals(0,claims.get());assertNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));
    }
    @Test void applicationErrorsAndExceptionsHaveSignedBoundedSafeResponses() throws Exception {
        for(int status:List.of(400,403,409,500,503)){
            String nonce=Nonce.random();var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),request(nonce),(req,res)->{var out=(HttpServletResponse)res;out.setStatus(status);out.getWriter().write("{\"error\":\"controlled\"}");});verify(response,nonce,status);
        }
        String nonce=Nonce.random();var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),request(nonce),(req,res)->{res.getWriter().write("partial-private");throw new ServletException("password SQL private");});
        verify(response,nonce,500);assertFalse(response.getContentAsString().contains("private"));
    }
    @Test void responseHeadersFlushRedirectAsyncAndOverflowCannotBypassSigning() throws Exception {
        for(String mode:List.of("headers","redirect","statusRedirect","overflow","writerOverflow","caughtOverflow","async","listener","sendError")){
            String nonce=Nonce.random();var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),request(nonce),(req,res)->{var r=(HttpServletRequest)req;var s=(HttpServletResponse)res;
                switch(mode){
                    case "headers"->{s.setHeader(SignedHttpClient.RESPONSE_PROOF,"forged");s.addHeader("Transfer-Encoding","chunked");s.setContentLength(999);s.getWriter().write("ok");s.flushBuffer();}
                    case "redirect"->s.sendRedirect("https://outside.invalid");case "statusRedirect"->{s.setStatus(302);s.setHeader("Location","https://outside.invalid");}
                    case "overflow"->s.getOutputStream().write(new byte[513]);case "writerOverflow"->s.getWriter().write("x".repeat(1000));
                    case "caughtOverflow"->{try{s.getOutputStream().write(new byte[513]);}catch(RuntimeException ignored){}}
                    case "async"->r.startAsync();case "listener"->s.getOutputStream().setWriteListener(null);case "sendError"->s.sendError(403,"private SQL");
                }
            });int expected=mode.equals("headers")?200:mode.contains("Redirect")||mode.equals("redirect")?502:mode.equals("sendError")?403:500;
            verify(response,nonce,expected);assertNull(response.getHeader("Transfer-Encoding"));assertNull(response.getHeader("Location"));assertEquals(response.getContentAsByteArray().length,Integer.parseInt(response.getHeader("Content-Length")));assertTrue(response.getContentAsByteArray().length<=512);
            assertFalse(response.getContentAsString().contains("private"));
        }
    }
    @Test void unrelatedPublicPathPassesWithoutInternalAuthentication() throws Exception {
        var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),new MockHttpServletRequest("GET","/api/public"),(req,res)->((HttpServletResponse)res).setStatus(204));assertEquals(204,response.getStatus());assertEquals(1,executed.get());assertEquals(0,claims.get());
    }
    @Test void unknownLengthInputStopsReadingAtLimitAndClosesStream() throws Exception {
        AtomicInteger read=new AtomicInteger();AtomicBoolean closed=new AtomicBoolean();
        var input=new MockHttpServletRequest("POST",PATH){
            @Override public long getContentLengthLong(){return -1;}
            @Override public ServletInputStream getInputStream(){return new ServletInputStream(){
                public int read(){read.incrementAndGet();return 1;}
                public int read(byte[] bytes,int off,int length){Arrays.fill(bytes,off,off+length,(byte)1);read.addAndGet(length);return length;}
                public boolean isFinished(){return false;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){}
                public void close(){closed.set(true);}
            };}
        };
        input.addHeader(SignedHttpClient.REQUEST_PROOF,request().getHeader(SignedHttpClient.REQUEST_PROOF));
        var response=run(filter(RoutePolicy.UserMode.FORBIDDEN),input,(req,res)->fail("Infinite input reached chain"));
        assertEquals(413,response.getStatus());assertTrue(read.get()<=WorkloadProofFilter.MAX_BODY_BYTES+8192);assertTrue(closed.get());assertEquals(0,claims.get());
    }
    @Test void firstDeliveryCannotSubstituteAnotherIndependentlyValidUserProof() throws Exception {
        String nonce=Nonce.random(),alice=user("workflow",3);
        String bob=gateway.sign(new UserProof(2,"gateway","workflow",8,"bob",4,NOW,NOW+120,"request-bob"));
        assertEquals(8,verifier.verifyUser(bob,"workflow").proof().uid());
        var input=request(nonce);bindUser(input,alice);input.removeHeader(SignedHttpClient.USER_PROOF);input.addHeader(SignedHttpClient.USER_PROOF,bob);
        AtomicInteger liveChecks=new AtomicInteger();var filter=filter(RoutePolicy.UserMode.REQUIRED,nonces,(id,name,av)->{liveChecks.incrementAndGet();return true;});
        var rejected=run(filter,input,(req,res)->fail("Substituted user reached chain"));assertEquals(401,rejected.getStatus());assertEquals(0,claims.get());assertEquals(0,liveChecks.get());
        var original=request(nonce);bindUser(original,alice);verify(run(filter,original,(req,res)->assertEquals(7,TrustedUser.require((HttpServletRequest)req).proof().uid())),nonce,200);
        assertEquals(1,claims.get());assertEquals(1,liveChecks.get());
    }
    @Test void exactMaximumInputIsAcceptedAndEmptyWireBodiesAreSignedAsEmpty() throws Exception {
        String nonce=Nonce.random();byte[] maximum=new byte[WorkloadProofFilter.MAX_BODY_BYTES];
        var input=request(nonce);input.setContent(maximum);input.removeHeader(SignedHttpClient.REQUEST_PROOF);
        input.addHeader(SignedHttpClient.REQUEST_PROOF,token("POST",PATH,maximum,nonce,OP,workflow,"workflow"));
        verify(run(filter(RoutePolicy.UserMode.FORBIDDEN),input,(req,res)->assertEquals(maximum.length,req.getInputStream().readAllBytes().length)),nonce,200);
        for(String mode:List.of("HEAD","204","205")){
            String current=Nonce.random(),method=mode.equals("HEAD")?"HEAD":"POST";
            var filter=new WorkloadProofFilter("metadata",verifier,metadata,(m,p)->Optional.of(new RoutePolicy.Route(OP,Set.of("workflow"),RoutePolicy.UserMode.FORBIDDEN)),nonces,(id,name,av)->true,CLOCK,POLICY);
            var request=new MockHttpServletRequest(method,PATH);request.setContent(new byte[0]);request.addHeader(SignedHttpClient.REQUEST_PROOF,token(method,PATH,new byte[0],current,OP,workflow,"workflow"));
            int status=mode.equals("HEAD")?200:Integer.parseInt(mode);
            var response=run(filter,request,(req,res)->{((HttpServletResponse)res).setStatus(status);res.getWriter().write("must-not-be-on-wire");});
            verify(response,current,status);assertEquals(0,response.getContentAsByteArray().length);
        }
    }
    @Test void connectorCommittedReadTimeoutIsNotResetAndNeverReachesAuthenticationOrBusiness() throws Exception {
        var response=new MockHttpServletResponse();
        var input=new MockHttpServletRequest("POST",PATH){
            @Override public ServletInputStream getInputStream(){return new ServletInputStream(){
                @Override public int read() throws IOException {response.setStatus(408);response.flushBuffer();throw new java.net.SocketTimeoutException("controlled connector deadline");}
                @Override public boolean isFinished(){return false;}
                @Override public boolean isReady(){return true;}
                @Override public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };}
        };
        input.addHeader(SignedHttpClient.REQUEST_PROOF,token("POST",PATH,BODY,Nonce.random(),OP,workflow,"workflow"));
        assertDoesNotThrow(()->filter(RoutePolicy.UserMode.FORBIDDEN).doFilter(input,response,(req,res)->fail("Timed out body reached business")));
        assertEquals(408,response.getStatus());assertEquals(0,claims.get());assertNull(response.getHeader(SignedHttpClient.RESPONSE_PROOF));
    }
    @Test void bothFiltersCanCoexistWithoutRemovingEachOthersTrustedUser() throws Exception {
        var workloadFilter=filter(RoutePolicy.UserMode.REQUIRED);
        var direct=new DirectUserProofFilter("metadata",verifier,(id,name,av)->id==7&&av==3,(method,path)->DirectRoutePolicy.Decision.PROTECTED);
        String nonce=Nonce.random();var internal=request(nonce);bindUser(internal,user("workflow",3));
        var response=run(workloadFilter,internal,(req,res)->direct.doFilter(req,res,(r,s)->assertEquals(7,TrustedUser.require((HttpServletRequest)r).proof().uid())));verify(response,nonce,200);
        var api=new MockHttpServletRequest("GET","/api/tables");api.addHeader(SignedHttpClient.USER_PROOF,user("metadata",3));
        AtomicInteger accepted=new AtomicInteger();direct.doFilter(api,new MockHttpServletResponse(),(req,res)->workloadFilter.doFilter(req,res,(r,s)->{assertEquals(7,TrustedUser.require((HttpServletRequest)r).proof().uid());accepted.incrementAndGet();}));assertEquals(1,accepted.get());
    }
}
