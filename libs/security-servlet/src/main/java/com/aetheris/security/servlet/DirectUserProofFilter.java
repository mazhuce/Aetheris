package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Direct user authentication for explicit /api policy. Does not sign responses or implement business RBAC. */
public final class DirectUserProofFilter implements Filter {
    private final String audience;
    private final ProtocolVerifier verifier;
    private final SessionValidator sessions;
    private final DirectRoutePolicy routes;
    public DirectUserProofFilter(String audience,ProtocolVerifier verifier,SessionValidator sessions,DirectRoutePolicy routes){
        if(audience==null||!audience.matches("[a-z][a-z0-9-]{0,63}")||verifier==null||sessions==null||routes==null)
            throw new IllegalArgumentException("Incomplete direct user filter configuration");
        this.audience=audience;this.verifier=verifier;this.sessions=sessions;this.routes=routes;
    }
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain) throws IOException,ServletException {
        if(!(input instanceof HttpServletRequest request)||!(output instanceof HttpServletResponse response))throw new ServletException("HTTP required");
        String raw=request.getRequestURI();
        if(!api(raw)&&!api(request.getServletPath())&&!api(request.getPathInfo())){chain.doFilter(request,response);return;}
        if(request instanceof TrustedUserRequest){deny(response,400);return;}
        request.removeAttribute(TrustedUser.ATTRIBUTE);
        if(!request.getContextPath().isEmpty()||!api(raw)||request.getDispatcherType()!=DispatcherType.REQUEST){deny(response,400);return;}
        DirectRoutePolicy.Decision decision;
        try {decision=routes.resolve(request.getMethod(),raw);}catch(RuntimeException unavailable){deny(response,503);return;}
        if(decision==DirectRoutePolicy.Decision.PUBLIC){chain.doFilter(request,response);return;}
        if(decision!=DirectRoutePolicy.Decision.PROTECTED){deny(response,403);return;}
        ProtocolVerifier.Verified<UserProof> verified;String token;
        try {token=WorkloadProofFilter.single(request,SignedHttpClient.USER_PROOF,true);verified=verifier.verifyUser(token,audience);}
        catch(RuntimeException invalid){deny(response,401);return;}
        try {var user=verified.proof();if(!sessions.isActive(user.uid(),user.username(),user.accessVersion())){deny(response,403);return;}}
        catch(Exception unavailable){if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();deny(response,503);return;}
        var trusted=new TrustedUser(verified,token);request.setAttribute(TrustedUser.ATTRIBUTE,trusted);
        chain.doFilter(new TrustedUserRequest(request,trusted),response);
    }
    private static boolean api(String path){return path!=null&&(path.equals("/api")||path.startsWith("/api/")||path.startsWith("/api;"));}
    private static void deny(HttpServletResponse response,int status) throws IOException {
        if(response.isCommitted())throw new IllegalStateException("User response already committed");
        byte[] body="{\"message\":\"User authentication rejected\"}".getBytes(StandardCharsets.UTF_8);
        response.reset();response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setContentLength(body.length);response.getOutputStream().write(body);
    }
}
