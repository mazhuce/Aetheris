package com.aetheris.serving.security;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

@Component
public class ServingSecurity {
    public static final String ATTRIBUTE="aetheris.serving.identity";
    private final IdentityVerifier verifier; private final RestClient auth;
    public ServingSecurity(IdentityVerifier verifier, RestClient.Builder builder, @Value("${aetheris.auth.base-url:http://localhost:8087}") String base, @Value("${aetheris.internal-token:}") String token){this.verifier=verifier;SimpleClientHttpRequestFactory f=new SimpleClientHttpRequestFactory();f.setConnectTimeout(500);f.setReadTimeout(1500);this.auth=builder.requestFactory(f).baseUrl(base).defaultHeader("X-Internal-Token",token).build();}
    public ServingIdentity requireIdentity(HttpServletRequest request){Object cached=request.getAttribute(ATTRIBUTE);if(cached instanceof ServingIdentity i)return i;ServingIdentity i=verifier.verify(request);if(i==null||i.username()==null||i.username().isBlank())throw new UnauthorizedException("缺少有效的网关身份签名");request.setAttribute(ATTRIBUTE,i);return i;}
    public void requireManage(HttpServletRequest request){ServingIdentity i=requireIdentity(request);try{Permission response=auth.post().uri("/internal/permissions/may-manage").body(Map.of("username",i.username())).retrieve().body(Permission.class);if(response==null||!response.allowed())throw new ForbiddenException(response==null?"权限服务不可用":response.reason());}catch(ForbiddenException e){throw e;}catch(RuntimeException e){throw new ForbiddenException("权限服务不可用");}}
    public void require(HttpServletRequest request,String resource,Long id,String action){ServingIdentity i=requireIdentity(request);check(i.username(),resource,id,action,Map.of());}
    private void check(String user,String resource,Long id,String action,Map<String,String> ctx){try{Map<String,Object> body=new java.util.LinkedHashMap<>();body.put("username",user);body.put("resourceType",resource);body.put("resourceId",id);body.put("action",action);body.put("ctx",ctx);Permission response=auth.post().uri("/internal/permissions/check").body(body).retrieve().body(Permission.class);if(response==null||!response.allowed())throw new ForbiddenException(response==null?"权限服务不可用":response.reason());}catch(ForbiddenException e){throw e;}catch(RuntimeException e){throw new ForbiddenException("权限服务不可用");}}
    public static class UnauthorizedException extends RuntimeException{public UnauthorizedException(String m){super(m);}}
    public static class ForbiddenException extends RuntimeException{public ForbiddenException(String m){super(m);}}
    private record Permission(boolean allowed,String reason){}
}
