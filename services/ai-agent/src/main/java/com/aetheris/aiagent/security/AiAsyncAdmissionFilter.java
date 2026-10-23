package com.aetheris.aiagent.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;

/** Service-owned REQUEST admission and explicit reauthentication of a DeferredResult ASYNC dispatch. */
public final class AiAsyncAdmissionFilter implements Filter {
    private static final String ADMISSION=AiAsyncAdmissionFilter.class.getName()+".admitted";
    public static final String REQUEST_ID=AiAsyncAdmissionFilter.class.getName()+".requestId";
    private final ProtocolVerifier verifier;private final SessionValidator sessions;private final Clock clock;
    private static final class Admission {
        final TrustedUser user;final String path,method,query;
        Admission(TrustedUser user,HttpServletRequest request){this.user=user;path=request.getRequestURI();method=request.getMethod();query=request.getQueryString();}
    }
    public AiAsyncAdmissionFilter(ProtocolVerifier verifier,SessionValidator sessions,Clock clock){this.verifier=Objects.requireNonNull(verifier);this.sessions=Objects.requireNonNull(sessions);this.clock=Objects.requireNonNull(clock);}
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        var request=(HttpServletRequest)input;var response=(HttpServletResponse)output;
        Object previous=request.getAttribute(ADMISSION);
        if(!api(request.getRequestURI()) && previous==null){chain.doFilter(request,response);return;}
        if(!request.getContextPath().isEmpty()){deny(response,400);return;}
        Admission admission;
        if(request.getDispatcherType()==DispatcherType.REQUEST){
            if(previous!=null || !(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user)){deny(response,401);return;}
            if(!valid(user)){deny(response,401);return;}
            admission=new Admission(user,request);request.setAttribute(ADMISSION,admission);
        }else if(request.getDispatcherType()==DispatcherType.ASYNC){
            if(!(previous instanceof Admission admitted) || request.getAttribute(TrustedUser.ATTRIBUTE)!=admitted.user
                    || !admitted.path.equals(request.getRequestURI()) || !admitted.method.equals(request.getMethod())
                    || !Objects.equals(admitted.query,request.getQueryString()) || !valid(admitted.user)){deny(response,403);return;}
            admission=admitted;
            try {var user=admission.user.proof();if(!sessions.isActive(user.uid(),user.username(),user.accessVersion())){deny(response,403);return;}}
            catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();deny(response,503);return;}
        }else{deny(response,400);return;}
        chain.doFilter(new AdmittedRequest(request,response,admission),response);
    }
    private boolean valid(TrustedUser user){try{verifier.verifyUser(user.compactProof(),"ai-agent");return user.proof().expiresAt()>clock.instant().getEpochSecond();}catch(RuntimeException rejected){return false;}}
    private static boolean api(String path){return path!=null&&(path.equals("/api/ai")||path.startsWith("/api/ai/"));}
    private static void deny(HttpServletResponse response,int status) throws IOException {
        if(response.isCommitted())throw new IllegalStateException("AI async response already committed");
        response.reset();response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"AI identity admission rejected\"}");
    }
    private static final class AdmittedRequest extends HttpServletRequestWrapper {
        final Admission admission;final HttpServletResponse response;
        AdmittedRequest(HttpServletRequest request,HttpServletResponse response,Admission admission){super(request);this.response=response;this.admission=admission;}
        private boolean immutable(String name){return name.equals(TrustedUser.ATTRIBUTE)||name.equals(ADMISSION)||name.equals(REQUEST_ID);}
        @Override public Object getAttribute(String name){if(name.equals(TrustedUser.ATTRIBUTE))return admission.user;if(name.equals(ADMISSION))return admission;if(name.equals(REQUEST_ID))return admission.user.proof().requestId();return super.getAttribute(name);}
        @Override public void setAttribute(String name,Object value){if(immutable(name))throw new IllegalStateException("AI identity is immutable");super.setAttribute(name,value);}
        @Override public void removeAttribute(String name){if(immutable(name))throw new IllegalStateException("AI identity is immutable");super.removeAttribute(name);}
        @Override public Enumeration<String> getAttributeNames(){var names=new HashSet<>(Collections.list(super.getAttributeNames()));names.addAll(Set.of(TrustedUser.ATTRIBUTE,ADMISSION,REQUEST_ID));return Collections.enumeration(names);}
        @Override public String getHeader(String name){return name.equalsIgnoreCase("X-Request-Id")?admission.user.proof().requestId():super.getHeader(name);}
        @Override public Enumeration<String> getHeaders(String name){return name.equalsIgnoreCase("X-Request-Id")?Collections.enumeration(List.of(admission.user.proof().requestId())):super.getHeaders(name);}
        @Override public AsyncContext startAsync(){return super.startAsync(this,response);}
        @Override public AsyncContext startAsync(ServletRequest request,ServletResponse response){
            if(request!=this)throw new IllegalStateException("AI async must preserve the admitted request");
            return super.startAsync(request,response);
        }
    }
}
