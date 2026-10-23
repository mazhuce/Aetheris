package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.util.*;

/** Synchronous internal-v2 authentication only; business RBAC/receipt checks remain mandatory. */
public final class WorkloadProofFilter implements Filter {
    public static final String TRUSTED_ATTRIBUTE=WorkloadProofFilter.class.getName()+".trusted";
    public static final int MAX_BODY_BYTES=1024*1024;
    private static final byte[] DENIED=json("Internal request rejected"), FAILED=json("Internal request failed"),
            UNAVAILABLE=json("Internal authentication unavailable"), REPLAY=json("Internal request replay rejected"),
            TOO_LARGE=json("Internal body limit exceeded"), REDIRECT=json("Internal redirect refused");
    private final String audience;
    private final ProtocolVerifier verifier;
    private final ProtocolSigner signer;
    private final RoutePolicy routes;
    private final NonceStore nonces;
    private final SessionValidator sessions;
    private final Clock clock;
    private final TimePolicy timePolicy;
    private final int responseLimit;

    public static final class TrustedRequest {
        private final ProtocolVerifier.Verified<WorkloadRequestProof> workload;
        private final ProtocolVerifier.Verified<UserProof> user;
        private final String userProof;
        private TrustedRequest(ProtocolVerifier.Verified<WorkloadRequestProof> workload,ProtocolVerifier.Verified<UserProof> user,String rawUserProof){
            this.workload=workload;this.user=user;this.userProof=rawUserProof;
        }
        public ProtocolVerifier.Verified<WorkloadRequestProof> workload(){return workload;}
        public Optional<ProtocolVerifier.Verified<UserProof>> user(){return Optional.ofNullable(user);}
        public Optional<String> userProof(){return Optional.ofNullable(userProof);}
        @Override public String toString(){return "TrustedInternalRequest[redacted]";}
    }
    public static TrustedRequest trusted(HttpServletRequest request){
        if(request.getAttribute(TRUSTED_ATTRIBUTE) instanceof TrustedRequest trusted)return trusted;
        throw new IllegalStateException("Missing authenticated internal request");
    }
    public WorkloadProofFilter(String audience,ProtocolVerifier verifier,ProtocolSigner signer,RoutePolicy routes,
                               NonceStore nonces,SessionValidator sessions,Clock clock,TimePolicy timePolicy){
        this(audience,verifier,signer,routes,nonces,sessions,clock,timePolicy,MAX_BODY_BYTES);
    }
    public WorkloadProofFilter(String audience,ProtocolVerifier verifier,ProtocolSigner signer,RoutePolicy routes,
                               NonceStore nonces,SessionValidator sessions,Clock clock,TimePolicy timePolicy,int responseLimit){
        if(audience==null||!audience.matches("[a-z][a-z0-9-]{0,63}")||verifier==null||signer==null||routes==null
                ||nonces==null||sessions==null||clock==null||timePolicy==null||responseLimit<256||responseLimit>MAX_BODY_BYTES)
            throw new IllegalArgumentException("Incomplete internal filter configuration");
        // Fail before any business side effects if the response signer has the wrong purpose/receiver or registry.
        long now=clock.instant().getEpochSecond();String nonce=Nonce.random();
        String probe=signer.sign(new WorkloadResponseProof(2,audience,audience,nonce,200,BodyHash.sha256(new byte[0]),now,now+timePolicy.workloadMaxTtlSeconds()));
        verifier.verifyResponse(probe,new ExpectedResponse(audience,audience,nonce,200,BodyHash.sha256(new byte[0])));
        this.audience=audience;this.verifier=verifier;this.signer=signer;this.routes=routes;this.nonces=nonces;
        this.sessions=sessions;this.clock=clock;this.timePolicy=timePolicy;this.responseLimit=responseLimit;
    }

    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        if(!(input instanceof HttpServletRequest request)||!(output instanceof HttpServletResponse response))throw new ServletException("HTTP required");
        String raw=request.getRequestURI();
        boolean internal=internal(raw)||internal(request.getServletPath())||internal(request.getPathInfo());
        if(!internal){chain.doFilter(request,response);return;}
        if(request instanceof ReplayableRequest){unsigned(response,400,DENIED);return;}
        request.removeAttribute(TRUSTED_ATTRIBUTE);
        request.removeAttribute(TrustedUser.ATTRIBUTE);
        if(response.isCommitted())throw new ServletException("Internal response already committed");
        if(!request.getContextPath().isEmpty()||request.getDispatcherType()!=DispatcherType.REQUEST||request.isAsyncStarted()
                ||request.getQueryString()!=null||!canonical(raw)) {unsigned(response,400,DENIED);return;}
        RoutePolicy.Route route;
        try {route=routes.resolve(request.getMethod(),raw).orElse(null);}
        catch(RuntimeException unavailable){unsigned(response,503,UNAVAILABLE);return;}
        if(route==null){unsigned(response,404,DENIED);return;}
        byte[] body;String requestProof,userProof;
        try {
            requestProof=single(request,SignedHttpClient.REQUEST_PROOF,true);
            userProof=single(request,SignedHttpClient.USER_PROOF,false);
            if(single(request,SignedHttpClient.RESPONSE_PROOF,false)!=null)throw new Rejected(400);
            String encoding=single(request,"Content-Encoding",false);
            if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw new Rejected(415);
            String length=single(request,"Content-Length",false),transfer=single(request,"Transfer-Encoding",false);
            if(transfer!=null&&(!transfer.equalsIgnoreCase("chunked")||length!=null))throw new Rejected(400);
            long declared=request.getContentLengthLong();
            if(length!=null){try{declared=Long.parseLong(length);}catch(NumberFormatException bad){throw new Rejected(400);}if(declared<0)throw new Rejected(400);}
            if(declared>MAX_BODY_BYTES)throw new Rejected(413);
            body=readBounded(request.getInputStream());
            if(declared>=0&&declared!=body.length)throw new Rejected(400);
        }catch(Rejected rejected){unsigned(response,rejected.status,rejected.status==413?TOO_LARGE:DENIED);return;}
        catch(IOException unavailable){unsigned(response,400,DENIED);return;}
        ProtocolVerifier.Verified<WorkloadRequestProof> authenticated;
        ProtocolVerifier.Verified<UserProof> user=null;
        try {
            authenticated=verifier.verifyRequest(requestProof,new ExpectedRequest(audience,request.getMethod(),raw,BodyHash.sha256(body),route.operation(),
                    BodyHash.userProof(userProof)));
            if(!route.allowedCallers().contains(authenticated.caller()))throw new Rejected(403);
            if(route.userMode()==RoutePolicy.UserMode.FORBIDDEN&&userProof!=null)throw new Rejected(403);
            if(route.userMode()==RoutePolicy.UserMode.REQUIRED&&userProof==null)throw new Rejected(401);
            if(userProof!=null)user=verifyUser(userProof,route.userAudiences(authenticated.caller()));
        }catch(Rejected denied){unsigned(response,denied.status,DENIED);return;}
        catch(ProtocolException invalid){unsigned(response,401,DENIED);return;}
        if(user!=null){
            try {var p=user.proof();if(!sessions.isActive(p.uid(),p.username(),p.accessVersion())){unsigned(response,403,DENIED);return;}}
            catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();unsigned(response,503,UNAVAILABLE);return;}
        }
        var identity=new TrustedRequest(authenticated,user,userProof);
        try {
            var p=authenticated.proof();if(!nonces.claim(authenticated.caller(),p.nonce(),p.expiresAt())){signed(response,identity,409,REPLAY,Map.of());return;}
        }catch(Exception unavailable){signed(response,identity,503,UNAVAILABLE,Map.of());return;}
        BufferedResponse buffered=new BufferedResponse(response,responseLimit);
        try {
            chain.doFilter(new ReplayableRequest(request,body,identity),buffered);buffered.finish();
            if(buffered.exceeded)buffered.replace(500,TOO_LARGE);
            else if(buffered.status>=300&&buffered.status<400)buffered.replace(502,REDIRECT);
        }catch(Exception failure){buffered.replace(500,FAILED);}
        signed(response,identity,buffered.status,buffered.bytes(),buffered.headers);
    }
    private ProtocolVerifier.Verified<UserProof> verifyUser(String compact,Set<String> expectedAudiences) {
        for(String expected:expectedAudiences) {
            try {return verifier.verifyUser(compact,expected);}
            catch(ProtocolException rejected) { /* Only the server's fixed audience allowset is tried. */ }
        }
        throw new ProtocolException("USER proof not valid for this caller route");
    }
    private void signed(HttpServletResponse response,TrustedRequest trusted,int status,byte[] body,Map<String,List<String>> headers) throws IOException {
        var request=trusted.workload.proof();long now=clock.instant().getEpochSecond();String proof;
        if(request.method().equals("HEAD")||status==204||status==205)body=new byte[0];
        try {proof=signer.sign(new WorkloadResponseProof(2,audience,trusted.workload.caller(),request.nonce(),status,
                BodyHash.sha256(body),now,now+timePolicy.workloadMaxTtlSeconds()));}
        catch(RuntimeException unavailable){unsigned(response,503,UNAVAILABLE);return;}
        response.reset();headers.forEach((name,values)->values.forEach(value->response.addHeader(name,value)));
        response.setStatus(status);response.setHeader(SignedHttpClient.RESPONSE_PROOF,proof);
        if(response.getContentType()==null)response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);response.getOutputStream().write(body);response.flushBuffer();
    }
    private static void unsigned(HttpServletResponse response,int status,byte[] body) throws IOException {
        // A connector read timeout can already have committed/closed its own rejection.
        // No authenticated body has been accepted here; never reset that response into a container 500.
        if(response.isCommitted())return;
        response.reset();response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);response.getOutputStream().write(body);response.flushBuffer();
    }
    private static byte[] json(String message){return ("{\"message\":\""+message+"\"}").getBytes(StandardCharsets.UTF_8);}
    private static boolean internal(String path){return path!=null&&path.startsWith("/internal");}
    private static boolean canonical(String path){
        if(path==null||path.length()>1024||!path.startsWith("/internal/v2/")||path.endsWith("/")||!path.matches("/[A-Za-z0-9._~/-]+"))return false;
        for(String part:path.substring(1).split("/",-1))if(part.isEmpty()||part.length()>128||part.equals(".")||part.equals(".."))return false;
        return true;
    }
    static String single(HttpServletRequest request,String name,boolean required){
        var all=Collections.list(request.getHeaders(name));if(all.size()>1||(required&&all.size()!=1))throw new Rejected(401);
        if(all.isEmpty())return null;String value=all.getFirst();
        int maximum=name.toLowerCase(Locale.ROOT).contains("proof")?12288:128;
        if(value==null||value.isEmpty()||value.length()>maximum||value.chars().anyMatch(c->c<32||c>126))throw new Rejected(400);
        return value;
    }
    private static byte[] readBounded(InputStream stream) throws IOException {
        try(stream){var out=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int count;
            while((count=stream.read(chunk))!=-1){if(count>MAX_BODY_BYTES-out.size())throw new Rejected(413);out.write(chunk,0,count);}return out.toByteArray();}
    }
    private static final class Rejected extends RuntimeException {final int status;Rejected(int status){this.status=status;}}
    static boolean hidden(String name){
        String key=name.toLowerCase(Locale.ROOT);return key.startsWith("x-user-")||key.equals("x-internal-token")||key.equals("x-aetheris-identity")
                ||key.equals("authorization")||key.equals("cookie")||key.equals(SignedHttpClient.REQUEST_PROOF.toLowerCase(Locale.ROOT))
                ||key.equals(SignedHttpClient.USER_PROOF.toLowerCase(Locale.ROOT))||key.equals(SignedHttpClient.RESPONSE_PROOF.toLowerCase(Locale.ROOT));
    }
    private static final class ReplayableRequest extends TrustedUserRequest {
        private final byte[] body;private final TrustedRequest trusted;
        ReplayableRequest(HttpServletRequest request,byte[] body,TrustedRequest trusted){super(request,trusted.user==null?null:new TrustedUser(trusted.user,trusted.userProof));this.body=body;this.trusted=trusted;}
        @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(body);return new ServletInputStream(){
            public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}
            public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
            public void setReadListener(ReadListener listener){throw new IllegalStateException("Internal streaming is disabled");}
        };}
        @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        @Override public long getContentLengthLong(){return body.length;}
        @Override public int getContentLength(){return body.length;}
        @Override public String getHeader(String name){return hidden(name)?null:super.getHeader(name);}
        @Override public Enumeration<String> getHeaders(String name){return hidden(name)?Collections.emptyEnumeration():super.getHeaders(name);}
        @Override public Enumeration<String> getHeaderNames(){return Collections.enumeration(Collections.list(super.getHeaderNames()).stream().filter(n->!hidden(n)).toList());}
        @Override public Object getAttribute(String name){return TRUSTED_ATTRIBUTE.equals(name)?trusted:super.getAttribute(name);}
        @Override public Enumeration<String> getAttributeNames(){var names=new HashSet<>(Collections.list(super.getAttributeNames()));names.add(TRUSTED_ATTRIBUTE);return Collections.enumeration(names);}
        @Override public void setAttribute(String name,Object value){if(TRUSTED_ATTRIBUTE.equals(name))throw new IllegalStateException("Trusted identity is immutable");super.setAttribute(name,value);}
        @Override public void removeAttribute(String name){if(TRUSTED_ATTRIBUTE.equals(name))throw new IllegalStateException("Trusted identity is immutable");super.removeAttribute(name);}
        @Override public boolean isAsyncSupported(){return false;}
        @Override public AsyncContext startAsync(){throw new IllegalStateException("Internal async is disabled");}
        @Override public AsyncContext startAsync(ServletRequest request,ServletResponse response){throw new IllegalStateException("Internal async is disabled");}
        @Override public Cookie[] getCookies(){return null;}
        @Override public Principal getUserPrincipal(){return null;}
        @Override public String getRemoteUser(){return null;}
        @Override public String getAuthType(){return null;}
        @Override public boolean isUserInRole(String role){return false;}
        @Override public HttpSession getSession(boolean create){if(create)throw new IllegalStateException("Internal sessions are disabled");return null;}
        @Override public HttpSession getSession(){return getSession(true);}
    }

    private static final class BufferedResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private final int maximum;private int status=200;private boolean exceeded,streamUsed;private PrintWriter writer;
        private final Map<String,List<String>> headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        BufferedResponse(HttpServletResponse original,int maximum){super(original);this.maximum=maximum;}
        private boolean protectedHeader(String name){return name.equalsIgnoreCase(SignedHttpClient.RESPONSE_PROOF)||name.equalsIgnoreCase("Content-Length")
                ||name.equalsIgnoreCase("Transfer-Encoding")||name.equalsIgnoreCase("Location")||name.equalsIgnoreCase("Set-Cookie");}
        @Override public void setHeader(String name,String value){
            if(protectedHeader(name))return;
            if(value==null){headers.remove(name);return;}
            if(name.equalsIgnoreCase("Content-Encoding")&&!"identity".equalsIgnoreCase(value))throw new IllegalStateException("Internal encoding is disabled");
            headers.put(name,new ArrayList<>(List.of(value)));
        }
        @Override public void addHeader(String name,String value){
            if(protectedHeader(name)||value==null)return;
            if(name.equalsIgnoreCase("Content-Encoding")&&!"identity".equalsIgnoreCase(value))throw new IllegalStateException("Internal encoding is disabled");
            headers.computeIfAbsent(name,k->new ArrayList<>()).add(value);
        }
        @Override public boolean containsHeader(String name){return headers.containsKey(name);}
        @Override public String getHeader(String name){var values=headers.get(name);return values==null?null:values.getFirst();}
        @Override public Collection<String> getHeaders(String name){return List.copyOf(headers.getOrDefault(name,List.of()));}
        @Override public Collection<String> getHeaderNames(){return List.copyOf(headers.keySet());}
        @Override public void setIntHeader(String name,int value){setHeader(name,Integer.toString(value));}
        @Override public void addIntHeader(String name,int value){addHeader(name,Integer.toString(value));}
        @Override public void setDateHeader(String name,long value){setHeader(name,Long.toString(value));}
        @Override public void addDateHeader(String name,long value){addHeader(name,Long.toString(value));}
        @Override public void setStatus(int status){if(status<200||status>599)throw new IllegalArgumentException("Invalid final HTTP status");this.status=status;}
        @Override public int getStatus(){return status;}
        @Override public void sendError(int status){replace(status,FAILED);}
        @Override public void sendError(int status,String message){replace(status,FAILED);}
        @Override public void sendRedirect(String location){replace(502,REDIRECT);}
        @Override public void setContentLength(int length){}
        @Override public void setContentLengthLong(long length){}
        @Override public void setContentType(String type){setHeader("Content-Type",type);}
        @Override public String getContentType(){return getHeader("Content-Type");}
        @Override public void setCharacterEncoding(String charset){if(charset!=null&&!charset.equalsIgnoreCase("UTF-8"))throw new IllegalStateException("Internal charset must be UTF-8");}
        @Override public String getCharacterEncoding(){return "UTF-8";}
        @Override public void addCookie(Cookie cookie){throw new IllegalStateException("Internal cookies are disabled");}
        private ServletOutputStream output(){return new ServletOutputStream(){
            public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){throw new IllegalStateException("Internal streaming is disabled");}
            public void write(int value){write(new byte[]{(byte)value},0,1);}
            public void write(byte[] bytes,int offset,int length){Objects.checkFromIndexSize(offset,length,bytes.length);if(length>maximum-buffer.size()){exceeded=true;throw new Rejected(500);}buffer.write(bytes,offset,length);}
        };}
        @Override public ServletOutputStream getOutputStream(){if(writer!=null)throw new IllegalStateException("Writer already selected");streamUsed=true;return output();}
        @Override public PrintWriter getWriter(){if(streamUsed)throw new IllegalStateException("Stream already selected");if(writer==null)writer=new PrintWriter(new OutputStreamWriter(output(),StandardCharsets.UTF_8));return writer;}
        @Override public void flushBuffer(){if(writer!=null)writer.flush();}
        @Override public boolean isCommitted(){return false;}
        @Override public void resetBuffer(){buffer.reset();writer=null;streamUsed=false;}
        @Override public void reset(){resetBuffer();headers.clear();status=200;}
        @Override public void setBufferSize(int size){if(size<0||size>maximum)throw new IllegalArgumentException("Invalid internal buffer size");}
        @Override public int getBufferSize(){return maximum;}
        @Override public ServletResponse getResponse(){throw new IllegalStateException("Internal response cannot be unwrapped");}
        @Override public void setTrailerFields(java.util.function.Supplier<Map<String,String>> supplier){throw new IllegalStateException("Internal trailers are disabled");}
        void finish(){flushBuffer();}
        void replace(int status,byte[] body){reset();setStatus(status);buffer.writeBytes(body);headers.put("Content-Type",new ArrayList<>(List.of("application/json")));}
        byte[] bytes(){return buffer.toByteArray();}
    }
}
