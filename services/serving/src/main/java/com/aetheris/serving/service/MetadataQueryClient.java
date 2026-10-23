package com.aetheris.serving.service;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.serving.security.ServingSecurity;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.concurrent.*;

/** Fixed v2 operations; original USER forwarding cannot be replaced by arbitrary headers or usernames. */
@Component
public class MetadataQueryClient {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(200000).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SignedHttpClient transport;
    @Autowired public MetadataQueryClient(@Qualifier("servingMetadataTransport") SignedHttpClient transport){this.transport=Objects.requireNonNull(transport);}
    /** Deny-only compatibility constructor; never installs the retired shared-token transport. */
    @Deprecated public MetadataQueryClient(org.springframework.web.client.RestClient.Builder ignored,String base,String token){this.transport=null;}
    public String currentUserProof(){
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !(attributes.getRequest().getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser user))
            throw new ServingSecurity.UnauthorizedException("缺少有效的用户身份");
        return user.compactProof();
    }
    private String user(String explicit){String current=currentUserProof();if(explicit==null||!current.equals(explicit))throw new ServingSecurity.UnauthorizedException("用户身份不匹配");return current;}
    public Map<String,Object> query(Long datasourceId,String sql){return query(datasourceId,sql,currentUserProof());}
    public Map<String,Object> query(Long datasourceId,String sql,String proof){
        if(datasourceId==null||datasourceId<=0||sql==null||sql.isBlank()||sql.length()>200000)throw new IllegalArgumentException("查询参数无效");
        return invoke("agent/query","metadata.user.sql-query",Map.of("datasourceId",datasourceId,"sql",sql),user(proof));
    }
    public Map<String,Object> queryMetric(String code,Object query){return queryMetric(code,query,currentUserProof());}
    public Map<String,Object> queryMetric(String code,Object query,String proof){
        if(code==null||!code.matches("[A-Za-z][A-Za-z0-9_]{1,63}"))throw new IllegalArgumentException("指标编码格式无效");
        return invoke("semantic/metrics/query","metadata.user.metric-query",Map.of("metricCode",code,"query",query==null?Map.of():query),user(proof));
    }
    public Map<String,Object> queryTable(Long id,Object query){return queryTable(id,query,currentUserProof());}
    public Map<String,Object> queryTable(Long id,Object query,String proof){
        if(id==null||id<=0)throw new IllegalArgumentException("表ID无效");
        return invoke("tables/query","metadata.user.table-query",Map.of("tableId",id,"query",query==null?Map.of():query),user(proof));
    }
    public Map<String,Object> publishedApiAsset(String code){
        if(code==null||!code.matches("[A-Za-z][A-Za-z0-9_]{1,63}"))throw new IllegalArgumentException("API资产编码格式无效");
        var body=invoke("published-api-assets/resolve","metadata.published-asset.resolve",Map.of("assetCode",code),null);
        if(!body.keySet().equals(Set.of("id","assetCode","version","targetType","targetRef")) || !code.equals(body.get("assetCode")))throw unavailable();
        Object id=body.get("id");
        if(!(id instanceof Number number)||number.longValue()<=0||!(id instanceof Integer||id instanceof Long)
                ||!(body.get("version") instanceof String version)||!version.matches("v[1-9][0-9]*")
                ||!Set.of("table","metric").contains(body.get("targetType"))||!(body.get("targetRef") instanceof String))throw unavailable();
        return PublishedKeyAdmissionService.asset(body);
    }
    public Map<String,Object> publishedKeyQuery(PublishedKeyAdmissionService.Reference reference){
        return invoke("published-api-assets/key-query","metadata.published-key.query",reference.map(),null);
    }
    private Map<String,Object> invoke(String path,String operation,Object body,String proof){
        if(transport==null)throw unavailable();
        CompletableFuture<SignedHttpClient.Response> future=null;
        try{
            future=transport.sendAsync(new SignedHttpClient.Request("metadata","POST","/internal/v2/"+path,operation,JSON.writeValueAsBytes(body),proof));
            var response=future.get(45000,TimeUnit.MILLISECONDS);
            if(response.status()==401){if(operation.equals("metadata.published-key.query"))throw unavailable();throw new ServingSecurity.UnauthorizedException("下游查询身份无效");}
            if(response.status()==403)throw new ServingSecurity.ForbiddenException("无权查询此数据资源");
            if(response.status()==404&&(operation.equals("metadata.published-asset.resolve")||operation.equals("metadata.published-key.query")))throw new ApiAssetNotFoundException();
            if(response.status()==409&&operation.equals("metadata.published-key.query"))throw new PublishedKeyAdmissionService.Conflict();
            if(response.status()!=200)throw unavailable();
            var result=JSON.readValue(response.body(),new TypeReference<Map<String,Object>>(){});
            if(result==null)throw unavailable();
            if(result.containsKey("error"))throw new IllegalArgumentException("查询请求无法执行");
            return result;
        }catch(ServingSecurity.UnauthorizedException|ServingSecurity.ForbiddenException|ApiAssetNotFoundException|ResponseStatusException|IllegalArgumentException expected){throw expected;}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(Exception invalid){throw unavailable();}
        finally{if(future!=null&&!future.isDone())future.cancel(true);}
    }
    private static ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"元数据服务暂不可用");}
    public static class ApiAssetNotFoundException extends RuntimeException{public ApiAssetNotFoundException(){super("API资产不存在或未发布");}}
}
