package com.aetheris.workflow.security;

import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.function.Function;
import java.util.concurrent.*;

/** Request-local permissions use the current exact proof, never a substituted subject. */
@Component
public class ResourceAuthorization {
    private static final Set<String> RESOURCES=Set.of("workflow_task");
    private final AuthProtocolClient auth;
    /** Deny-only standalone default; Spring selects the explicit production constructor. */
    public ResourceAuthorization(){this.auth=null;}
    @org.springframework.beans.factory.annotation.Autowired
    public ResourceAuthorization(AuthProtocolClient auth){this.auth=Objects.requireNonNull(auth);}
    private HttpServletRequest request(){return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a?a.getRequest():null;}
    private TrustedUser subject(){
        var r=request();if(r==null || !(r.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user))return null;
        Object name=r.getAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE);
        if(name!=null && !user.proof().username().equals(name))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"身份主体不匹配");
        return user;
    }
    public String username(){var u=subject();return u==null?null:u.proof().username();}
    public TrustedUser currentUser(){var user=subject();if(user==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"需要已验签用户");return user;}
    public boolean allowed(String type,Long id,String action){
        var u=subject();if(u==null || auth==null || type==null || !RESOURCES.contains(type) || (id!=null&&id<=0) || action==null || action.isBlank())return false;
        var decisions=cache(u);String key=type+":"+id+":"+action;
        if(decisions.values.containsKey(key))return decisions.values.get(key);
        boolean result=await(auth.check(u.compactProof(),type,id,action,Map.of())).allowed();decisions.values.put(key,result);return result;
    }
    public void require(String type,Long id,String action){if(!allowed(type,id,action))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"没有 "+type+"."+action+" 权限");}
    /** Only these two datasource actions are used by explicit SQL approval. */
    public void requireDatasource(long id,String action){
        var user=currentUser();if(auth==null||id<=0||!Set.of("query","write").contains(action))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if(!await(auth.check(user.compactProof(),"datasource",id,action,Map.of())).allowed())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"没有数据源批准权限");
    }
    public boolean mayManage(){return coarse(false);}
    public boolean mayMutate(){return coarse(true);}
    private boolean coarse(boolean mutate){
        var u=subject();if(u==null || auth==null)return false;
        var decisions=cache(u);String key=mutate?"mutate":"manage";
        if(decisions.values.containsKey(key))return decisions.values.get(key);
        boolean result=await(mutate?auth.mayMutate(u.compactProof(),"workflow"):auth.mayManage(u.compactProof())).allowed();decisions.values.put(key,result);return result;
    }
    private static final String CACHE=ResourceAuthorization.class.getName()+".proofDecisions";
    private static final class Decisions {final TrustedUser user;final Map<String,Boolean> values=new HashMap<>();Decisions(TrustedUser user){this.user=user;}}
    private Decisions cache(TrustedUser user){var r=request();if(r.getAttribute(CACHE) instanceof Decisions c && c.user==user)return c;var fresh=new Decisions(user);r.setAttribute(CACHE,fresh);return fresh;}
    public void requireManage(){if(!mayManage())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"创建或修改执行定义需要平台管理权限；当前委托权限仅支持固定定义的运行和展示/调度调整");}
    private static <T> T await(CompletableFuture<T> future){
        try{return future.get(2500,TimeUnit.MILLISECONDS);}catch(InterruptedException e){future.cancel(true);Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException | TimeoutException | RuntimeException e){future.cancel(true);throw unavailable();}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"权限服务不可用");}
    /** Reject execution changes, then retain persisted protected fields when an update omits them. */
    public void protectDefinition(Object input, Object persisted, Set<String> editable) {
        var incoming=new org.springframework.beans.BeanWrapperImpl(input);
        Set<String> runtime=Set.of("createdAt","updatedAt","lastRunStatus","lastRunAt","lastRunRows",
                "lastError","lastCheckStatus","lastViolationCount","lastCheckedAt","scheduleVersion","definitionVersion");
        for(String name:runtime) if(incoming.isWritableProperty(name))incoming.setPropertyValue(name,null);
        if(mayManage())return;
        if(persisted==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"资源不存在");
        var existing=new org.springframework.beans.BeanWrapperImpl(persisted);
        for(var property:incoming.getPropertyDescriptors()) {
            String name=property.getName();
            if(runtime.contains(name))continue;
            if(editable.contains(name) || !incoming.isReadableProperty(name) || !incoming.isWritableProperty(name))continue;
            Object value=incoming.getPropertyValue(name), previous=existing.getPropertyValue(name);
            if(value!=null && !Objects.deepEquals(value,previous))throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "委托更新不能修改执行定义字段 "+name+"；请由平台管理员修改");
            incoming.setPropertyValue(name,previous);
        }
    }
    /** Persist only explicitly delegated fields; stale execution snapshots cannot overwrite concurrent admin edits. */
    public void retainEditable(Object input, Set<String> editable) {
        if(mayManage())return;
        var incoming=new org.springframework.beans.BeanWrapperImpl(input);
        for(var property:incoming.getPropertyDescriptors()) {
            String name=property.getName();
            if(!"id".equals(name) && !"definitionVersion".equals(name) && !editable.contains(name)
                    && incoming.isWritableProperty(name))incoming.setPropertyValue(name,null);
        }
    }
    public boolean allTasksAllowed(List<Long> ids,String action){
        var u=subject();if(u==null||auth==null||ids==null||ids.isEmpty()||!Set.of("view","execute").contains(action))return false;
        var required=new LinkedHashSet<Long>();for(Long id:ids){if(id==null||id<=0)return false;required.add(id);}
        var unique=new ArrayList<>(required);var granted=new HashSet<Long>();
        for(int offset=0;offset<unique.size();offset+=500){var items=unique.subList(offset,Math.min(offset+500,unique.size())).stream().map(id->new AuthProtocolClient.Item(id,Map.of())).toList();granted.addAll(await(auth.checkBatch(u.compactProof(),"workflow_task",action,items)));}
        return granted.containsAll(required);
    }
    public <T> List<T> filter(String type,List<T> values,Function<T,Long> id){
        var u=subject();if(values==null || values.isEmpty() || u==null || auth==null || type==null || !RESOURCES.contains(type))return List.of();
        Set<Long> unique=new LinkedHashSet<>();for(T value:values)if(id.apply(value)!=null)unique.add(id.apply(value));
        var ids=new ArrayList<>(unique);Set<Long> allowed=new HashSet<>();
        for(int offset=0;offset<ids.size();offset+=500){
            var items=ids.subList(offset,Math.min(offset+500,ids.size())).stream().map(value->new AuthProtocolClient.Item(value,Map.of())).toList();
            allowed.addAll(await(auth.checkBatch(u.compactProof(),type,"view",items)));
        }
        return values.stream().filter(value->allowed.contains(id.apply(value))).toList();
    }
}
