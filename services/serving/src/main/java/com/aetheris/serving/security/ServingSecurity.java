package com.aetheris.serving.security;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.*;

@Component
public class ServingSecurity {
    public static final String ATTRIBUTE="aetheris.serving.identity";
    private static final String CACHE=ServingSecurity.class.getName()+".permissions";
    private static final Set<String> RESOURCES=Set.of("dataset","dashboard","data_service","metric","datasource","table");
    private final AuthProtocolClient auth;
    @Autowired public ServingSecurity(AuthProtocolClient auth){this.auth=Objects.requireNonNull(auth);}
    /** Deny-only adapter for the retired constructor; HMAC and plain attributes have no authority. */
    @Deprecated ServingSecurity(IdentityVerifier ignored,org.springframework.web.client.RestClient unused){this.auth=null;}
    private static final class Decisions {
        private final TrustedUser user;private final Map<String,Boolean> values=new HashMap<>();
        private Decisions(TrustedUser user){this.user=user;}
    }
    private static TrustedUser subject(HttpServletRequest request){
        if(request==null || !(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user))
            throw new UnauthorizedException("缺少有效的用户身份");
        return user;
    }
    private Decisions decisions(HttpServletRequest request,TrustedUser user){
        Object cached=request.getAttribute(CACHE);
        if(cached instanceof Decisions result && result.user==user)return result;
        var result=new Decisions(user);request.setAttribute(CACHE,result);return result;
    }
    public ServingIdentity requireIdentity(HttpServletRequest request){var proof=subject(request).proof();return new ServingIdentity(proof.uid(),proof.username());}
    public String compactProof(HttpServletRequest request){return subject(request).compactProof();}
    public void requireManage(HttpServletRequest request){
        var user=subject(request);var cached=decisions(request,user);
        boolean allowed=cached.values.computeIfAbsent("manage",ignored->call(()->requireAuth().mayManage(user.compactProof())).allowed());
        if(!allowed)throw new ForbiddenException("需要平台管理权限");
    }
    public void require(HttpServletRequest request,String resource,Long id,String action){
        var user=subject(request);
        if(!RESOURCES.contains(resource) || (id!=null&&id<=0) || action==null || !action.matches("[a-z][a-z0-9_-]{0,63}"))throw new ForbiddenException("无效的资源权限");
        var cache=decisions(request,user);String key=resource+":"+id+":"+action;
        boolean allowed=cache.values.computeIfAbsent(key,ignored->call(()->requireAuth().check(user.compactProof(),resource,id,action,Map.of())).allowed());
        if(!allowed)throw new ForbiddenException("没有所需资源权限");
    }
    public <T> List<T> filter(HttpServletRequest request,String resource,List<T> values,java.util.function.Function<T,Long> id){
        var user=subject(request);
        if(values==null||values.isEmpty()||!RESOURCES.contains(resource))return List.of();
        List<Long> ids=values.stream().map(id).filter(Objects::nonNull).distinct().toList();
        if(ids.stream().anyMatch(value->value<=0))throw new ForbiddenException("无效的资源权限");
        Set<Long> allowed=new HashSet<>();
        for(int offset=0;offset<ids.size();offset+=500){
            var items=ids.subList(offset,Math.min(offset+500,ids.size())).stream().map(value->new AuthProtocolClient.Item(value,Map.of())).toList();
            allowed.addAll(call(()->requireAuth().checkBatch(user.compactProof(),resource,"view",items)));
        }
        return values.stream().filter(value->allowed.contains(id.apply(value))).toList();
    }
    private AuthProtocolClient requireAuth(){if(auth==null)throw unavailable();return auth;}
    private static <T> T call(java.util.function.Supplier<CompletableFuture<T>> operation){
        try{return await(operation.get());}catch(ResponseStatusException unavailable){throw unavailable;}catch(RuntimeException rejected){throw unavailable();}
    }
    private static <T> T await(CompletableFuture<T> future){
        try{return future.get(2500,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();future.cancel(true);throw unavailable();}
        catch(ExecutionException|TimeoutException|RuntimeException unavailable){future.cancel(true);throw unavailable();}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"权限服务不可用");}
    public static class UnauthorizedException extends RuntimeException{public UnauthorizedException(String m){super(m);}}
    public static class ForbiddenException extends RuntimeException{public ForbiddenException(String m){super(m);}}
}
