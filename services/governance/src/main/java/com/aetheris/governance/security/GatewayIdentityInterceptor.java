package com.aetheris.governance.security;

import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;

/** MVC adapter for the already verified v2 identity. Raw identity headers have no authority. */
@Component
public class GatewayIdentityInterceptor implements HandlerInterceptor {
    public static final String USERNAME_ATTRIBUTE="aetheris.username";
    private final ResourceAuthorization authorization;
    public GatewayIdentityInterceptor(ResourceAuthorization authorization){this.authorization=authorization;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
        if(!(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user))return reject(response,401);
        request.setAttribute(USERNAME_ATTRIBUTE,user.proof().username());
        String path=request.getRequestURI();
        boolean resource=segment(path,"/api/governance/policies")||segment(path,"/api/governance/standards");
        if(isMutation(request.getMethod()) && !(resource?authorization.mayMutate():authorization.mayManage()))return reject(response,403);
        return true;
    }
    private static boolean segment(String path,String prefix){return path.equals(prefix)||path.startsWith(prefix+"/");}
    private static boolean isMutation(String method){return java.util.Set.of("POST","PUT","PATCH","DELETE").contains(method);}
    private static boolean reject(HttpServletResponse response,int status) throws IOException {
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"缺少有效身份或权限\"}");return false;
    }
}
